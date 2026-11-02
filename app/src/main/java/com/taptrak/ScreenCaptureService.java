package com.taptrak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Foreground service that owns the MediaProjection session and produces a
 * {@link Bitmap} of the current screen on demand. It only holds on to the newest
 * frame and copies pixels when one is requested, so it costs almost nothing
 * between screenshots.
 *
 * <p>The projection consent dialog shown by Android before this starts is the
 * user's signal that capture is active; it is deliberately not bypassed.
 */
public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "capture";
    private static final int NOTIF_ID = 42;
    private static final long FRESH_FRAME_TIMEOUT = 300;
    private static final long FRESH_FRAME_POLL = 16;

    /** Live instance, so the engine can request a frame. Null when capture isn't granted. */
    public static volatile ScreenCaptureService instance;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private final Object frameLock = new Object();
    private Image latestFrame;
    private long latestFrameAt;

    private int width, height, densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        captureThread = new HandlerThread("capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        startForegroundCompat();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        // Required on recent Android: a registered callback, or the session throws.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); teardownProjection(); }
        }, captureHandler);

        computeMetrics();
        imageReader = newReader();
        virtualDisplay = projection.createVirtualDisplay(
                "taptrak",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        instance = this;
        Crawler.notifyChanged();
        return START_NOT_STICKY;
    }

    private ImageReader newReader() {
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onFrame, captureHandler);
        return reader;
    }

    private void onFrame(ImageReader reader) {
        synchronized (frameLock) {
            if (reader != imageReader) return;
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            if (latestFrame != null) latestFrame.close();
            latestFrame = image;
            latestFrameAt = SystemClock.uptimeMillis();
        }
    }

    private void computeMetrics() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        densityDpi = dm.densityDpi;
    }

    /** Follows rotation, so landscape screens aren't letterboxed inside a portrait frame. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (virtualDisplay == null) return;
        int oldWidth = width, oldHeight = height;
        computeMetrics();
        if (width == oldWidth && height == oldHeight) return;
        synchronized (frameLock) {
            closeLatestFrame();
            ImageReader old = imageReader;
            imageReader = newReader();
            virtualDisplay.resize(width, height, densityDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
            old.close();
        }
    }

    /**
     * The newest frame shown at or after {@code uptimeMillis}, falling back to the
     * newest frame after a short wait because a static screen produces no new
     * frames. Call from a background thread.
     */
    public Bitmap captureFrameAfter(long uptimeMillis) {
        long deadline = SystemClock.uptimeMillis() + FRESH_FRAME_TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            synchronized (frameLock) {
                if (latestFrame != null && latestFrameAt >= uptimeMillis) break;
            }
            SystemClock.sleep(FRESH_FRAME_POLL);
        }
        synchronized (frameLock) {
            return latestFrame == null ? null : imageToBitmap(latestFrame);
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int imageWidth = image.getWidth(), imageHeight = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowPadding = plane.getRowStride() - pixelStride * imageWidth;

        Bitmap padded = Bitmap.createBitmap(
                imageWidth + rowPadding / pixelStride, imageHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return padded;
        // Crop away the row padding so the bitmap is exactly the screen size.
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight);
        padded.recycle();
        return cropped;
    }

    private void startForegroundCompat() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void closeLatestFrame() {
        if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
    }

    private void teardownProjection() {
        if (instance == this) instance = null;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        synchronized (frameLock) {
            closeLatestFrame();
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null) p.stop();
        Crawler.notifyChanged();
    }

    @Override
    public void onDestroy() {
        teardownProjection();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
