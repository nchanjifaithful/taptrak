package com.taptrak;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The floating control panel shown during a run. An accessibility overlay needs
 * no extra permission, and it is hidden for the instant of each screenshot so it
 * never appears in one. Drag to move; tap the panel to collapse or expand it.
 */
final class OverlayController implements Crawler.Listener {

    /** Compositing margin, so a frame already in flight isn't taken as overlay-free. */
    private static final long HIDE_SETTLE_MS = 48;
    private static final long HIDE_TIMEOUT_MS = 200;

    private final CrawlerAccessibilityService service;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View root;
    private View dot;
    private View details;
    private TextView counts;
    private TextView status;
    private ImageButton pause;
    private WindowManager.LayoutParams params;

    OverlayController(CrawlerAccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void attach() {
        main.post(() -> {
            if (root != null) return;
            buildViews();
            windowManager.addView(root, params);
            Crawler.addListener(this);
            onCrawlerChanged();
        });
    }

    void detach() {
        main.post(() -> {
            if (root == null) return;
            Crawler.removeListener(this);
            windowManager.removeView(root);
            root = null;
        });
    }

    /** Hides the panel and returns the time from which frames are free of it. */
    long hideForCapture() {
        CountDownLatch hidden = new CountDownLatch(1);
        main.post(() -> {
            if (root != null) root.setVisibility(View.INVISIBLE);
            hidden.countDown();
        });
        try {
            hidden.await(HIDE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return SystemClock.uptimeMillis() + HIDE_SETTLE_MS;
    }

    void showAfterCapture() {
        main.post(() -> { if (root != null) root.setVisibility(View.VISIBLE); });
    }

    @Override
    public void onCrawlerChanged() {
        if (root == null) return;
        Crawler.State state = Crawler.state;
        counts.setText(service.getString(R.string.overlay_counts, Crawler.shotCount, Crawler.featureCount));
        status.setText(Crawler.statusText);

        int dotColor;
        int actionIcon;
        int actionLabel;
        switch (state) {
            case PAUSED:
                dotColor = R.color.overlay_paused;
                actionIcon = R.drawable.ic_play;
                actionLabel = R.string.overlay_resume;
                break;
            case WAITING_AUTH:
            case WAITING_PERMISSION:
                dotColor = R.color.overlay_waiting;
                actionIcon = R.drawable.ic_skip;
                actionLabel = R.string.overlay_skip;
                break;
            default:
                dotColor = R.color.overlay_running;
                actionIcon = R.drawable.ic_pause;
                actionLabel = R.string.overlay_pause;
                break;
        }
        dot.setBackgroundTintList(ColorStateList.valueOf(service.getColor(dotColor)));
        pause.setImageResource(actionIcon);
        pause.setContentDescription(service.getString(actionLabel));

        // A waiting panel asks for attention, so don't leave it collapsed.
        if (state == Crawler.State.WAITING_AUTH || state == Crawler.State.WAITING_PERMISSION) {
            details.setVisibility(View.VISIBLE);
        }
    }

    private void buildViews() {
        Context themed = new ContextThemeWrapper(service, R.style.Theme_TapTrak);
        root = LayoutInflater.from(themed).inflate(R.layout.overlay_controls, null);
        dot = root.findViewById(R.id.overlayDot);
        details = root.findViewById(R.id.overlayDetails);
        counts = root.findViewById(R.id.overlayCounts);
        status = root.findViewById(R.id.overlayStatus);
        pause = root.findViewById(R.id.overlayPause);

        pause.setOnClickListener(v -> service.togglePause());
        root.findViewById(R.id.overlayRestart).setOnClickListener(v -> service.restartCrawl());
        root.findViewById(R.id.overlayStop).setOnClickListener(v -> service.stopCrawl());

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        float density = service.getResources().getDisplayMetrics().density;
        params.x = (int) (16 * density);
        params.y = (int) (96 * density);

        root.setOnTouchListener(new DragListener());
    }

    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(service).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) < slop) return true;
                    dragging = true;
                    params.x = startX + (int) dx;
                    params.y = startY + (int) dy;
                    windowManager.updateViewLayout(root, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        details.setVisibility(details.getVisibility() == View.VISIBLE
                                ? View.GONE : View.VISIBLE);
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
