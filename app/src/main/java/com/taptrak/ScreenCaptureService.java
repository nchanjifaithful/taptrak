package com.taptrak;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Foreground service that owns the MediaProjection session for the run and
 * produces a {@link Bitmap} of the current screen on demand.
 *
 * <p>The projection consent dialog shown by Android before this starts is the
 * user's signal that capture is active; it is deliberately not bypassed.
 */
public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "capture";
    private static final int NOTIF_ID = 42;

    /** Live instance, so the accessibility engine can request a frame. */
    public static volatile ScreenCaptureService instance;

    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread captureThread;
    private Handler captureHandler;

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
            @Override public void onStop() { teardownProjection(); }
        }, captureHandler);

        computeMetrics();
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "taptrak",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        instance = this;
        Crawler.status("Screen capture ready (" + width + "x" + height + ").");
        return START_NOT_STICKY;
    }

    private void computeMetrics() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        densityDpi = dm.densityDpi;
    }

    /**
     * Grab the most recent frame as a Bitmap. Blocks briefly waiting for a frame,
     * so call it from a background thread (the engine does).
     */
    public Bitmap captureBitmap() {
        ImageReader reader = imageReader;
        if (reader == null) return null;
        for (int attempt = 0; attempt < 12; attempt++) {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                try {
                    return imageToBitmap(image);
                } finally {
                    image.close();
                }
            }
            try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return padded;
        // Crop away the row padding so the bitmap is exactly the screen size.
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private void startForegroundCompat() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b
                .setContentTitle("TapTrak is capturing the screen")
                .setContentText("Capture stops when you stop the crawl.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void teardownProjection() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (projection != null) { projection.stop(); projection = null; }
    }

    @Override
    public void onDestroy() {
        teardownProjection();
        if (captureThread != null) captureThread.quitSafely();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
