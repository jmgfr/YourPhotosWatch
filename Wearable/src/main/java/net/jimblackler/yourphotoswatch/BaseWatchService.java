package net.jimblackler.yourphotoswatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public abstract class BaseWatchService extends CanvasWatchFaceService {

  private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);


  static float fractional(float number) {
    return number - (long) number;
  }

  private void extraWake() {
    PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
    WakeLock wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Watch");
    wakeLock.acquire(12 * 1000);  // Hold the screen bright for some extra time.
  }

  @Override
  public Engine onCreateEngine() {
    return new Engine();
  }

  private class Engine extends CanvasWatchFaceService.Engine
      implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks {
    private static final int MINUTES_PER_HOUR = 60;
    private static final int MSG_UPDATE_TIME = 0;
    final Handler updateTimeHandler = new Handler() {
      @Override
      public void handleMessage(Message message) {
        switch (message.what) {
          case MSG_UPDATE_TIME:
            invalidate();
            if (shouldTimerBeRunning()) {
              long timeMs = System.currentTimeMillis();
              long delayMs = INTERACTIVE_UPDATE_RATE_MS
                  - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
              updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
            break;
        }
      }
    };
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = MINUTES_PER_HOUR * SECONDS_PER_MINUTE;
    private static final int SECONDS_PER_HALF_DAY = SECONDS_PER_HOUR * 12;
    final BroadcastReceiver timeZoneReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        time.clear(intent.getStringExtra("time-zone"));
        time.setToNow();
      }
    };
    boolean lowBitAmbient;
    private Paint allFill;
    private Paint handStroke;
    private Paint textStroke;
    private Paint secondFill;
    private Bitmap backgroundBitmap;
    private Bitmap backgroundScaledBitmap;
    private String currentPhoto;
    private Map<String, Long> shownAt = new HashMap<>();
    private GoogleApiClient googleApiClient;
    private boolean mute;
    private Map<String, DataItem> photos;
    private boolean registeredTimeZoneReceiver = false;
    private Time time;

    @Override
    public void onAmbientModeChanged(boolean inAmbientMode) {
      super.onAmbientModeChanged(inAmbientMode);
      if (isInAmbientMode()) {
        setEmptyBackground();
      } else {
        extraWake();
      }

      if (lowBitAmbient) {
        boolean antiAlias = !inAmbientMode;
        handStroke.setAntiAlias(antiAlias);
        textStroke.setAntiAlias(antiAlias);
        allFill.setAntiAlias(antiAlias);
        secondFill.setAntiAlias(antiAlias);
      }
      invalidate();

      // Whether the timer should be running depends on whether we're in ambient mode (as well
      // as whether we're visible), so we may need to start or stop the timer.
      updateTimer();
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
      super.onInterruptionFilterChanged(interruptionFilter);
      boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);
      if (mute != inMuteMode) {
        mute = inMuteMode;
        allFill.setAlpha(inMuteMode ? 100 : 255);
        secondFill.setAlpha(inMuteMode ? 80 : 255);
        invalidate();
      }
    }

    @Override
    public void onPropertiesChanged(Bundle properties) {
      super.onPropertiesChanged(properties);
      lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
    }

    @Override
    public void onTimeTick() {
      super.onTimeTick();
      invalidate();
    }

    @Override
    public void onCreate(SurfaceHolder holder) {

      super.onCreate(holder);

      setWatchFaceStyle(new WatchFaceStyle.Builder(BaseWatchService.this)
          .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
          .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
          .setShowSystemUiTime(false)
          .build());

      setEmptyBackground();

      allFill = new Paint();
      allFill.setStyle(Paint.Style.FILL);

      allFill.setColor(Color.HSVToColor(255, new float[]{238, 0.75f, 0.50f}));
      allFill.setStrokeWidth(5f);
      allFill.setAntiAlias(true);
      allFill.setStrokeCap(Paint.Cap.ROUND);
      allFill.setTextSize(getDigitalTextSize());

      handStroke = new Paint();
      handStroke.setStyle(Paint.Style.STROKE);
      handStroke.setColor(Color.WHITE);
      handStroke.setStrokeWidth(10f);
      handStroke.setAntiAlias(true);
      handStroke.setStrokeCap(Paint.Cap.ROUND);

      textStroke = new Paint();
      textStroke.setStyle(Paint.Style.FILL);
      textStroke.setColor(Color.WHITE);
      textStroke.setAntiAlias(true);
      textStroke.setTextSize(getDigitalTextSize());

      secondFill = new Paint();
      secondFill.setColor(Color.WHITE);
      secondFill.setStrokeWidth(2.f);
      secondFill.setAntiAlias(true);
      secondFill.setStrokeCap(Paint.Cap.ROUND);

      photos = new HashMap<>();

      time = new Time();
      googleApiClient = new GoogleApiClient.Builder(BaseWatchService.this)
          .addApi(Wearable.API)
          .addConnectionCallbacks(this)
          .build();
      googleApiClient.connect();

    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);

      if (visible) {
        registerReceiver();
        time.clear(TimeZone.getDefault().getID());
        time.setToNow();
      } else {
        unregisterReceiver();
      }
      updateTimer();
    }

    private void registerReceiver() {
      if (registeredTimeZoneReceiver)
        return;

      registeredTimeZoneReceiver = true;
      IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
      BaseWatchService.this.registerReceiver(timeZoneReceiver, filter);
    }

    private void unregisterReceiver() {
      if (!registeredTimeZoneReceiver)
        return;

      registeredTimeZoneReceiver = false;
      BaseWatchService.this.unregisterReceiver(timeZoneReceiver);
    }

    private void setEmptyBackground() {
      Drawable backgroundDrawable = getResources().getDrawable(R.drawable.background);
      backgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();
      backgroundScaledBitmap = null;
      currentPhoto = null;
      invalidate();
    }

    private void updateTimer() {
      updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
      if (shouldTimerBeRunning()) {
        updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
      }
    }

    private boolean shouldTimerBeRunning() {
      return isVisible() && !isInAmbientMode();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
      Wearable.DataApi.addListener(googleApiClient, this);

      Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
        @Override
        public void onResult(DataItemBuffer dataItems) {
          for (DataItem dataItem : dataItems) {
            String[] parts = dataItem.getUri().getPath().split("/");
            switch (parts[1]) {
              case "image":
                photos.put(parts[2], dataItem);
                break;
              default:
                break;
            }
          }
          invalidate();
        }
      });
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
      ArrayList<DataEvent> dataEvents1 = FreezableUtils.freezeIterable(dataEvents);
      dataEvents.close();
      for (DataEvent event : dataEvents1) {
        DataItem dataItem = event.getDataItem();
        String[] parts = dataItem.getUri().getPath().split("/");
        switch (parts[1]) {
          case "image":
            String photoId = parts[2];
            switch (event.getType()) {
              case DataEvent.TYPE_CHANGED:
                photos.put(photoId, dataItem);
                // Show new image immediately (even in ambient mode).
                updateImage(dataItem);
                extraWake();
                break;
              case DataEvent.TYPE_DELETED:
                photos.remove(photoId);
                if (currentPhoto.equals(photoId))
                  setEmptyBackground();
                break;
            }
        }
      }
    }

    void updateImage(DataItem dataItem) {
      String[] parts = dataItem.getUri().getPath().split("/");
      currentPhoto = parts[2];
      shownAt.put(currentPhoto, System.currentTimeMillis());
      Wearable.DataApi.getFdForAsset(googleApiClient,
          DataMapItem.fromDataItem(dataItem).getDataMap().getAsset("photo")).
          setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
            @Override
            public void onResult(DataApi.GetFdForAssetResult fd) {
              Bitmap bitmap = BitmapFactory.decodeStream(fd.getInputStream());
              if (bitmap == null)
                return;
              backgroundBitmap = bitmap;
              backgroundScaledBitmap = null;
              invalidate();
            }
          });
    }

    @Override
    public void onDestroy() {
      updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
      Wearable.DataApi.removeListener(googleApiClient, this);
      super.onDestroy();
    }

    @Override
    public void onDraw(Canvas canvas, Rect bounds) {
      time.setToNow();

      if (false) {  // Screenshot mode.
        time.set(36, 10, 10, 1, 1, 2014);
      }
      int width = bounds.width();
      int height = bounds.height();

      Bitmap clockBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas clockCanvas = new Canvas(clockBitmap);

      float centerX = width / 2f;
      float centerY = height / 2f;

      if (isAnalog()) {

        float innerTickRadius = centerX - 9;
        float topInnerTickRadius = centerX - 20;
        float outerTickRadius = centerX - 6;

        for (int tickIndex = 0; tickIndex < 12; tickIndex++) {
          float tickRotation = (float) (tickIndex * Math.PI * 2 / 12);
          float useInnerTickRadius = tickIndex == 0 ? topInnerTickRadius : innerTickRadius;
          float innerX = (float) Math.sin(tickRotation) * useInnerTickRadius;
          float innerY = (float) -Math.cos(tickRotation) * useInnerTickRadius;
          float outerX = (float) Math.sin(tickRotation) * outerTickRadius;
          float outerY = (float) -Math.cos(tickRotation) * outerTickRadius;
          clockCanvas.drawLine(centerX + innerX, centerY + innerY,
              centerX + outerX, centerY + outerY, handStroke);
          clockCanvas.drawLine(centerX + innerX, centerY + innerY,
              centerX + outerX, centerY + outerY, allFill);
        }

        float totalMinutes = time.minute + time.hour * MINUTES_PER_HOUR;
        float totalSeconds = time.second + totalMinutes * SECONDS_PER_MINUTE;
        float twoPi = (float) (Math.PI * 2);
        float secondRotation = fractional(totalSeconds / SECONDS_PER_MINUTE) * twoPi;
        float minuteRotation = fractional(totalSeconds / SECONDS_PER_HOUR) * twoPi;
        float hourRotation = fractional(totalSeconds / SECONDS_PER_HALF_DAY) * twoPi;

        float secondLength = centerX - 20;
        float secondLengthReverse = -25;
        float minuteLength = centerX - 35;
        float hourLength = centerX - 90;

        if (!isInAmbientMode()) {
          float secondX0 = (float) Math.sin(secondRotation) * secondLengthReverse;
          float secondY0 = (float) -Math.cos(secondRotation) * secondLengthReverse;

          float secondX1 = (float) Math.sin(secondRotation) * secondLength;
          float secondY1 = (float) -Math.cos(secondRotation) * secondLength;

          clockCanvas.drawLine(centerX + secondX0, centerY + secondY0,
              centerX + secondX1, centerY + secondY1, secondFill);
        }

        float minuteX = (float) Math.sin(minuteRotation) * minuteLength;
        float minuteY = (float) -Math.cos(minuteRotation) * minuteLength;

        float hourX = (float) Math.sin(hourRotation) * hourLength;
        float hourY = (float) -Math.cos(hourRotation) * hourLength;

        clockCanvas.drawLine(centerX, centerY, centerX + minuteX, centerY + minuteY, handStroke);
        clockCanvas.drawLine(centerX, centerY, centerX + hourX, centerY + hourY, handStroke);
        clockCanvas.drawLine(centerX, centerY, centerX + minuteX, centerY + minuteY, allFill);
        clockCanvas.drawLine(centerX, centerY, centerX + hourX, centerY + hourY, allFill);
      }

      if (!isInAmbientMode() && currentPhoto == null && !photos.isEmpty()) {
        long earliestShown = System.currentTimeMillis();
        String photoToShow = null;
        int unshownCount = 0;
        Random random = new Random();
        for (String photoId : photos.keySet()) {
          if (shownAt.containsKey(photoId)) {
            if (unshownCount > 0)
              continue;
            long shown = shownAt.get(photoId);
            if (shown > earliestShown)
              continue;
            photoToShow = photoId;
            earliestShown = shown;
          } else {
            unshownCount++;
            if (random.nextInt(unshownCount) == 0)
              photoToShow = photoId;
          }
        }
        updateImage(photos.get(photoToShow));
      }

      if (isDigital()) {

        int hours;

        if (true) { // 12 hour
          hours = time.hour % 12;
          if (hours == 0)
            hours += 12;
        }

        String text;

        if (isAmPm()) {
          text = String.format("%d:%02d %s", hours, time.minute, (time.hour < 12) ? "AM" : "PM");
        } else {
          text = String.format("%d:%02d", hours, time.minute);
        }

        float textWidth = textStroke.measureText(text);
        clockCanvas.drawText(text, centerX - textWidth / 2, centerY - getDigitalOffset(), textStroke);
      }

      if (backgroundScaledBitmap == null
          || backgroundScaledBitmap.getWidth() != width
          || backgroundScaledBitmap.getHeight() != height) {
        backgroundScaledBitmap =
            Bitmap.createScaledBitmap(backgroundBitmap, width, height, true);
      }

      canvas.drawBitmap(backgroundScaledBitmap, 0, 0, null);

      Bitmap shadowed = BitmapEffect.createShadow(clockBitmap);
      canvas.drawBitmap(shadowed, 0, 0, null);
      Paint draw = new Paint();
      if (currentPhoto != null) {
        draw.setAlpha(180);
      }
      canvas.drawBitmap(clockBitmap, 0, 0, draw);

      shadowed.recycle();
      clockBitmap.recycle();
    }


  }

  protected abstract boolean isAmPm();
  protected abstract float getDigitalTextSize();
  protected abstract float getDigitalOffset();
  protected abstract boolean isAnalog();
  protected abstract boolean isDigital();
}
