package com.taptrak;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared state for a capture run. {@link MainActivity}, the overlay,
 * {@link ScreenCaptureService} and {@link CrawlerAccessibilityService} all live
 * in one process and observe this class.
 *
 * <p>Scope note: this tool is for testing apps you own or are authorised to test.
 * It does not defeat FLAG_SECURE: protected windows capture as black and are skipped.
 */
public final class Crawler {

    private Crawler() {}

    public enum State { IDLE, RUNNING, PAUSED, WAITING_AUTH, WAITING_PERMISSION }

    public interface Listener { void onCrawlerChanged(); }

    public static volatile String targetPackage = "";
    public static volatile String targetLabel = "";
    public static volatile int maxShots = 100;

    public static volatile State state = State.IDLE;
    public static volatile String statusText = "";
    public static volatile int shotCount = 0;
    public static volatile int featureCount = 0;
    public static volatile int duplicatesSkipped = 0;
    public static volatile int permissionsGranted = 0;

    /** Summary of the last finished run, or null if there has not been one. */
    public static volatile String lastSummary;
    public static volatile Uri lastShotUri;

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable DISPATCH = () -> {
        for (Listener l : LISTENERS) l.onCrawlerChanged();
    };

    public static boolean isActive() { return state != State.IDLE; }

    public static void addListener(Listener l) { LISTENERS.addIfAbsent(l); }

    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    /** Coalesced, so a burst of updates causes one UI refresh. */
    public static void notifyChanged() {
        MAIN.removeCallbacks(DISPATCH);
        MAIN.post(DISPATCH);
    }

    public static void status(String text) {
        statusText = text;
        notifyChanged();
    }

    public static void resetCounters() {
        shotCount = 0;
        featureCount = 0;
        duplicatesSkipped = 0;
        permissionsGranted = 0;
    }
}
