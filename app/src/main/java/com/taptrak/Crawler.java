package com.taptrak;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared state and control surface for a crawl run.
 *
 * <p>The three components of the app live in the same process:
 * {@link MainActivity} (control UI), {@link ScreenCaptureService}
 * (holds the MediaProjection session) and {@link CrawlerAccessibilityService}
 * (the exploration engine). This class is the small amount of state they share.
 *
 * <p>Scope note: this is an automated UI-exploration tool intended for testing
 * apps you own or are authorised to test. It does not defeat FLAG_SECURE — windows
 * that opt out of screen capture (banking, DRM video, some login screens) will
 * capture as black, which is the platform behaviour and is left intact on purpose.
 */
public final class Crawler {

    private Crawler() {}

    /** Package name of the app under test. */
    public static volatile String targetPackage = "";

    /** Upper bound on saved screenshots; also the main stop condition. */
    public static volatile int maxShots = 200;

    /** Hard cap on interaction steps, so a pathological app can't loop forever. */
    public static volatile int maxSteps = 4000;

    /** Dummy text injected into editable fields to get past forms. */
    public static volatile String dummyText = "test";

    /** Where screenshots and the layout log for this run are written. */
    public static volatile File outputDir;

    public static volatile boolean running = false;
    public static volatile int shotCount = 0;
    public static volatile int stepCount = 0;

    /** Structural hashes of screens we have already captured ("visited" set). */
    public static final Set<String> visitedStates =
            Collections.synchronizedSet(new HashSet<String>());

    /** Per-screen set of element signatures we have already acted on. */
    public static final Map<String, Set<String>> triedElements =
            new ConcurrentHashMap<String, Set<String>>();

    public interface StatusListener { void onStatus(String text); }

    private static volatile StatusListener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void setListener(StatusListener l) { listener = l; }

    public static void status(final String text) {
        final StatusListener l = listener;
        if (l != null) MAIN.post(new Runnable() {
            @Override public void run() { l.onStatus(text); }
        });
    }

    public static void reset() {
        visitedStates.clear();
        triedElements.clear();
        shotCount = 0;
        stepCount = 0;
    }
}
