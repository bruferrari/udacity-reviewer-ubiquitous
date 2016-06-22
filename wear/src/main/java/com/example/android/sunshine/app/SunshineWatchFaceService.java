/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private final String TAG = SunshineWatchFaceService.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final long TIMEOUT_MS = 150000;
        GoogleApiClient mGoogleApiClient;
        private String path;
        String weatherMaxTemp;
        String weatherMinTemp;
        Bitmap weatherIconBitmap;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mTempPaint;
        boolean mAmbient;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        float mYDateOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();

            createPaintings(resources);

            mCalendar = Calendar.getInstance();
        }

        private void createPaintings(Resources res) {
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(res.getColor(R.color.background, null));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(res.getColor(R.color.digital_text, null));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(res.getColor(R.color.digital_text, null));
            mDatePaint.setTextSize(getResources().getDimension(R.dimen.digital_date_text_size));

            mTempPaint = new Paint();
            mTempPaint = createTextPaint(res.getColor(R.color.digital_text, null));
            mTempPaint.setTextSize(getResources().getDimension(R.dimen.digital_temp_text_size));
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mGoogleApiClient.disconnect();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);
            mYDateOffset = resources.getDimension(isRound
                    ? R.dimen.digital_date_y_offset_round : R.dimen.digital_date_y_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mTextPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    //TODO: maybe tab to refresh or tap to change color, some ideas
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            String hourString;
            String minuteString;
            int dateYOffset = 90;
            int tempOffset = 45;

            mCalendar.setTimeInMillis(System.currentTimeMillis());
            int hour = mCalendar.get(Calendar.HOUR);
            if (hour == 0)
                hour = 12;
            hourString = String.valueOf(hour);
            minuteString = String.valueOf(mCalendar.get(Calendar.MINUTE));

            String temps = null;
            if (weatherMaxTemp != null && weatherMinTemp != null)
                temps = String.format(Locale.getDefault(), "%s %s", weatherMaxTemp, weatherMinTemp);
            else
                temps = String.format(Locale.getDefault(), "%s %s"
                        , "- -" + "\u00B0", "- -" + "\u00B0");

            float tempCenterX = bounds.centerX() - (mTempPaint.measureText(temps)) / 2;

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                if (notExistsOverlayingPeekCard())
                    canvas.drawText(temps, tempCenterX, mYDateOffset+dateYOffset, mTempPaint);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                if (weatherIconBitmap != null) {
                    canvas.drawBitmap(weatherIconBitmap,
                            tempCenterX-30, mYDateOffset+45, null);
                }
                float lineCenterY = mYDateOffset+30;

                canvas.drawLine(bounds.exactCenterX() - 30, lineCenterY,
                        bounds.exactCenterX() + 30, lineCenterY+1, mTempPaint);

                canvas.drawText(temps, tempCenterX+tempOffset, mYDateOffset+dateYOffset, mTempPaint);
            }

            String time = String.format(Locale.getDefault(), "%d:%02d", Integer.parseInt(hourString),
                    Integer.parseInt(minuteString));

            float centerX = bounds.centerX() - (mTextPaint.measureText(time)) / 2;

            canvas.drawText(time, centerX , mYOffset, mTextPaint);

            SimpleDateFormat sdf = new SimpleDateFormat("ccc, MMM d yyyy", Locale.getDefault());

            String dateString = sdf.format(Calendar.getInstance().getTime());
            float centerDateX = bounds.centerX() - (mDatePaint.measureText(dateString)) / 2;

            canvas.drawText(dateString.toUpperCase(), centerDateX, mYDateOffset, mDatePaint);

        }

        private boolean notExistsOverlayingPeekCard() {
            return getPeekCardPosition().isEmpty();
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "GoogleApiClient connection suspended, cause: " + cause);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                Log.v(TAG, "DataMap received on watch: " + DataMapItem.fromDataItem(event.getDataItem())
                        .getDataMap());
                if (event.getType() == DataEvent.TYPE_CHANGED) {

                    path = event.getDataItem().getUri().getPath();
                    if (path.equals(getResources().getString(R.string.WEATHER_DATA_PATH))) {
                        try {
                            DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                            weatherMaxTemp = dataMap.getString(getResources().getString(R.string.WEATHER_TEMP_MAX));
                            weatherMinTemp = dataMap.getString(getResources().getString(R.string.WEATHER_TEMP_MIN));
                            Asset weatherIcon = dataMap.getAsset(getResources().getString(R.string.WEATHER_IMG));
                            weatherIconBitmap = new WeatherIconTask().execute(weatherIcon).get();
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    } else
                        Log.e(TAG, "Unknown path: " + path);
                } else
                    Log.e(TAG, "Unknown data event type: " + event.getType());
            }
        }

        class WeatherIconTask extends AsyncTask<Asset, String, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... asset) {
                if (asset == null)
                    throw new IllegalArgumentException("Asset must be non-null");
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!result.isSuccess())
                    return null;

                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset[0]).await().getInputStream();
                mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);
            }

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, "GoogleApiClient connection failed: " + connectionResult);
        }
    }
}
