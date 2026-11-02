package com.taptrak;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The capture engine. Explores the target app depth-first through the
 * accessibility tree, saves one screenshot per distinct screen, and files each
 * under the feature it belongs to.
 *
 * <p>A feature starts when the crawler taps something on the app's home screen, or
 * a navigation element anywhere; screens reached from there inherit it. Sign-in
 * screens and permission dialogs are left to the user: the crawl waits, then
 * carries on by itself.
 */
public class CrawlerAccessibilityService extends AccessibilityService {

    public static volatile CrawlerAccessibilityService instance;

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String FEATURE_HOME = "Home";
    private static final String FEATURE_SIGN_IN = "Sign-in";
    private static final String DUMMY_TEXT = "test";

    private static final long FIRST_STEP_DELAY = 1500;
    private static final long AFTER_TAP_DELAY = 700;
    private static final long AFTER_BACK_DELAY = 500;
    private static final long SETTLE_DELAY = 300;
    private static final long WAIT_POLL_DELAY = 800;

    private static final int MAX_STEPS = 1500;
    private static final int MAX_SCROLLS_PER_SCREEN = 6;
    private static final int MAX_TAPS_PER_SCREEN = 40;
    private static final int MAX_SHOTS_PER_FEATURE = 15;
    /** Two list rows leading nowhere new means the rest won't either. */
    private static final int MAX_KIND_MISSES = 2;
    private static final int MAX_RELAUNCHES = 3;
    private static final int MAX_BACKS_ON_SCREEN = 3;
    private static final int MAX_FOREIGN_BACKS = 4;
    private static final int MAX_SETTLE_TRIES = 4;
    private static final int MAX_LOADING_TRIES = 10;
    private static final int CLEAR_POLLS_TO_RESUME = 2;
    private static final float SAME_SCREEN_SIMILARITY = 0.85f;
    /** Differing bits out of 256 below which two images count as the same picture. */
    private static final int SAME_IMAGE_BITS = 12;
    private static final int BLANK_LUMA_RANGE = 6;

    private static final class Screen {
        final Set<String> tokens = new HashSet<>();
        final Set<String> tried = new HashSet<>();
        final Map<String, Integer> kindMisses = new HashMap<>();
        String feature;
        int taps;
        int scrolls;
        boolean exhausted;
    }

    private static final class Tap {
        Screen source;
        String kind;
        String label;
        boolean nav;
    }

    private HandlerThread engineThread;
    private Handler engine;
    private OverlayController overlay;
    private ScreenshotStore store;
    private final Runnable stepRunnable = this::doStep;

    private final Map<String, Screen> screensByHash = new HashMap<>();
    private final List<Screen> screens = new ArrayList<>();
    private final List<long[]> savedImages = new ArrayList<>();
    private final Set<Screen> skippedAuth = new HashSet<>();

    private String launcherPkg = "";
    private String currentActivity = "";
    private Screen rootScreen;
    private Screen authScreen;
    private Screen lastBackScreen;
    private Screen scrolledScreen;
    private Tap lastTap;
    private String settleHash;
    private int settleTries;
    private int steps;
    private int relaunches;
    private int backsOnScreen;
    private int foreignBacks;
    private int clearPolls;
    private int grantedBeforePrompt;
    private boolean seenTarget;

    // ---- Lifecycle ----------------------------------------------------------

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        engineThread = new HandlerThread("engine");
        engineThread.start();
        engine = new Handler(engineThread.getLooper());
        overlay = new OverlayController(this);
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) launcherPkg = home.activityInfo.packageName;
        Crawler.notifyChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !Crawler.targetPackage.contentEquals(pkg)) return;
        String name = cls.toString();
        if (name.startsWith("android.")) return;
        currentActivity = name.substring(name.lastIndexOf('.') + 1)
                .replace("Activity", "").replace("Fragment", "");
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (Crawler.isActive()) {
            Crawler.state = Crawler.State.IDLE;
            Crawler.status("Accessibility service stopped.");
        }
        Crawler.notifyChanged();
        if (overlay != null) overlay.detach();
        if (engineThread != null) engineThread.quitSafely();
        super.onDestroy();
    }

    // ---- Controls, called from the main screen and the overlay --------------

    public void startCrawl() {
        overlay.attach();
        engine.post(() -> {
            store = new ScreenshotStore(this, Crawler.targetLabel);
            beginRun();
        });
    }

    public void togglePause() {
        engine.post(() -> {
            switch (Crawler.state) {
                case RUNNING:
                    engine.removeCallbacks(stepRunnable);
                    Crawler.state = Crawler.State.PAUSED;
                    Crawler.status(getString(R.string.status_paused));
                    break;
                case PAUSED:
                    resumeRunning(getString(R.string.status_resuming));
                    break;
                case WAITING_AUTH:
                    // The user chose not to sign in: back out and never touch this screen.
                    if (authScreen != null) skippedAuth.add(authScreen);
                    resumeRunning(getString(R.string.status_auth_skipped));
                    break;
                case WAITING_PERMISSION:
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    break;
                default:
                    break;
            }
        });
    }

    public void restartCrawl() {
        engine.post(() -> {
            if (!Crawler.isActive()) return;
            engine.removeCallbacks(stepRunnable);
            store.deleteRunShots();
            beginRun();
        });
    }

    public void stopCrawl() {
        engine.post(() -> finish(getString(R.string.summary_stopped)));
    }

    private void beginRun() {
        Crawler.resetCounters();
        screens.clear();
        screensByHash.clear();
        savedImages.clear();
        skippedAuth.clear();
        rootScreen = null;
        authScreen = null;
        lastBackScreen = null;
        scrolledScreen = null;
        lastTap = null;
        settleHash = null;
        settleTries = 0;
        steps = 0;
        relaunches = 0;
        backsOnScreen = 0;
        foreignBacks = 0;
        seenTarget = false;
        Crawler.state = Crawler.State.RUNNING;
        if (launchTarget()) {
            Crawler.status(getString(R.string.status_exploring));
            engine.postDelayed(stepRunnable, FIRST_STEP_DELAY);
        } else {
            finish(getString(R.string.summary_launch_failed));
        }
    }

    private boolean launchTarget() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Crawler.targetPackage);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        return true;
    }

    private void resumeRunning(String status) {
        Crawler.state = Crawler.State.RUNNING;
        settleHash = null;
        Crawler.status(status);
        reschedule(SETTLE_DELAY);
    }

    private void finish(String why) {
        if (!Crawler.isActive()) return;
        engine.removeCallbacks(stepRunnable);
        Crawler.state = Crawler.State.IDLE;
        Crawler.lastSummary = getString(R.string.summary_format, why, Crawler.shotCount,
                Crawler.featureCount, Crawler.duplicatesSkipped, Crawler.permissionsGranted);
        Crawler.status(why);
        overlay.detach();
        Intent back = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(back);
    }

    // ---- The step loop ------------------------------------------------------

    private void reschedule(long delay) {
        if (Crawler.isActive()) engine.postDelayed(stepRunnable, delay);
    }

    private void doStep() {
        Crawler.State state = Crawler.state;
        if (state == Crawler.State.IDLE || state == Crawler.State.PAUSED) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String pkg = root == null || root.getPackageName() == null
                    ? "" : root.getPackageName().toString();
            if (state == Crawler.State.RUNNING) {
                stepRunning(root, pkg);
            } else {
                stepWaiting(state, root, pkg);
            }
        } catch (RuntimeException e) {
            Crawler.status("Step error: " + e.getClass().getSimpleName());
            reschedule(AFTER_TAP_DELAY);
        }
    }

    private void stepRunning(AccessibilityNodeInfo root, String pkg) {
        if (Crawler.shotCount >= Crawler.maxShots) { finish(getString(R.string.summary_limit)); return; }
        if (++steps > MAX_STEPS) { finish(getString(R.string.summary_steps)); return; }
        if (root == null) { reschedule(AFTER_BACK_DELAY); return; }

        if (pkg.equals(getPackageName()) || pkg.equals(SYSTEM_UI) && !isBiometricPrompt(root)) {
            reschedule(AFTER_BACK_DELAY);
        } else if (isPermissionDialog(pkg)) {
            enterPermissionWait();
        } else if (isExternalAuth(pkg) || pkg.equals(SYSTEM_UI)) {
            enterAuthWait(null);
        } else if (pkg.equals(launcherPkg) || pkg.isEmpty()) {
            onLeftToHome();
        } else if (!pkg.equals(Crawler.targetPackage)) {
            onForeignApp();
        } else {
            seenTarget = true;
            foreignBacks = 0;
            exploreTarget(root);
        }
    }

    private void onLeftToHome() {
        if (!seenTarget) { reschedule(AFTER_TAP_DELAY); return; }
        relaunchOrFinish();
    }

    private void onForeignApp() {
        if (++foreignBacks > MAX_FOREIGN_BACKS) { relaunchOrFinish(); return; }
        performGlobalAction(GLOBAL_ACTION_BACK);
        reschedule(AFTER_BACK_DELAY);
    }

    private void relaunchOrFinish() {
        if (hasUnexplored() && relaunches < MAX_RELAUNCHES && launchTarget()) {
            relaunches++;
            foreignBacks = 0;
            backsOnScreen = 0;
            lastTap = null;
            settleHash = null;
            reschedule(FIRST_STEP_DELAY);
        } else {
            finish(getString(R.string.summary_complete));
        }
    }

    private boolean hasUnexplored() {
        for (Screen s : screens) if (!s.exhausted) return true;
        return false;
    }

    private void exploreTarget(AccessibilityNodeInfo root) {
        ScreenAnalyzer.Result a = ScreenAnalyzer.analyze(root, getResources().getDisplayMetrics().heightPixels);

        // Don't judge or photograph a screen until its layout has stopped changing.
        if (!a.hash.equals(settleHash) && settleTries < MAX_SETTLE_TRIES) {
            settleHash = a.hash;
            settleTries++;
            reschedule(SETTLE_DELAY);
            return;
        }
        if (a.loading && settleTries < MAX_LOADING_TRIES) {
            settleTries++;
            reschedule(SETTLE_DELAY);
            return;
        }
        settleTries = 0;

        Screen screen = findKnownScreen(a);
        Tap arrivedBy = lastTap;
        lastTap = null;
        scrolledScreen = null;
        boolean isNew = screen == null;
        if (isNew) {
            screen = addScreen(a, arrivedBy);
        } else if (arrivedBy != null && arrivedBy.kind != null) {
            arrivedBy.source.kindMisses.merge(arrivedBy.kind, 1, Integer::sum);
        }

        if (a.auth && !skippedAuth.contains(screen)) {
            if (isNew) capture(screen, a);
            enterAuthWait(screen);
            return;
        }
        if (isNew) capture(screen, a);
        if (a.auth) { retreat(screen); return; }

        ScreenAnalyzer.Candidate next = screen.taps < MAX_TAPS_PER_SCREEN ? nextCandidate(screen, a) : null;
        if (next != null) {
            tap(screen, next);
            settleHash = null;
            backsOnScreen = 0;
            reschedule(AFTER_TAP_DELAY);
        } else if (screen.scrolls < MAX_SCROLLS_PER_SCREEN && a.scrollable != null
                && a.scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            screen.scrolls++;
            scrolledScreen = screen;
            settleHash = null;
            reschedule(AFTER_TAP_DELAY);
        } else {
            screen.exhausted = true;
            if (hasUnexplored()) {
                retreat(screen);
            } else {
                finish(getString(R.string.summary_complete));
            }
        }
    }

    private void retreat(Screen screen) {
        backsOnScreen = screen == lastBackScreen ? backsOnScreen + 1 : 1;
        lastBackScreen = screen;
        if (backsOnScreen > MAX_BACKS_ON_SCREEN) {
            screen.exhausted = true;
            relaunchOrFinish();
            return;
        }
        settleHash = null;
        performGlobalAction(GLOBAL_ACTION_BACK);
        reschedule(AFTER_BACK_DELAY);
    }

    // ---- Waiting on the user ------------------------------------------------

    private void enterAuthWait(Screen screen) {
        authScreen = screen;
        clearPolls = 0;
        Crawler.state = Crawler.State.WAITING_AUTH;
        Crawler.status(getString(R.string.status_auth_wait));
        reschedule(WAIT_POLL_DELAY);
    }

    private void enterPermissionWait() {
        grantedBeforePrompt = grantedPermissionCount();
        Crawler.state = Crawler.State.WAITING_PERMISSION;
        Crawler.status(getString(R.string.status_permission_wait));
        reschedule(WAIT_POLL_DELAY);
    }

    private void stepWaiting(Crawler.State state, AccessibilityNodeInfo root, String pkg) {
        if (state == Crawler.State.WAITING_PERMISSION) {
            if (root != null && !isPermissionDialog(pkg)) {
                int gained = grantedPermissionCount() - grantedBeforePrompt;
                if (gained > 0) Crawler.permissionsGranted += gained;
                resumeRunning(getString(gained > 0
                        ? R.string.status_permission_granted : R.string.status_permission_declined));
                return;
            }
        } else if (root != null && pkg.equals(Crawler.targetPackage)) {
            boolean stillAuth = ScreenAnalyzer.analyze(
                    root, getResources().getDisplayMetrics().heightPixels).auth;
            clearPolls = stillAuth ? 0 : clearPolls + 1;
            if (clearPolls >= CLEAR_POLLS_TO_RESUME) {
                resumeRunning(getString(R.string.status_auth_done));
                return;
            }
        }
        reschedule(WAIT_POLL_DELAY);
    }

    private static boolean isPermissionDialog(String pkg) {
        return pkg.contains("permissioncontroller") || pkg.contains("packageinstaller");
    }

    private static boolean isExternalAuth(String pkg) {
        return pkg.startsWith("com.google.android.gms") || pkg.contains("credentialmanager");
    }

    private static boolean isBiometricPrompt(AccessibilityNodeInfo root) {
        return !root.findAccessibilityNodeInfosByViewId(SYSTEM_UI + ":id/biometric_icon").isEmpty()
                || !root.findAccessibilityNodeInfosByViewId(SYSTEM_UI + ":id/biometric_scrollview").isEmpty();
    }

    private int grantedPermissionCount() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    Crawler.targetPackage, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissionsFlags == null) return 0;
            int granted = 0;
            for (int flags : info.requestedPermissionsFlags) {
                if ((flags & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted++;
            }
            return granted;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    // ---- Screen identity and features ---------------------------------------

    /** The same screen after a scroll, or a near match, is not a new screen. */
    private Screen findKnownScreen(ScreenAnalyzer.Result a) {
        Screen known = screensByHash.get(a.hash);
        if (known != null) return known;
        if (scrolledScreen != null) {
            known = scrolledScreen;
            known.tokens.addAll(a.tokens);
        } else {
            for (Screen s : screens) {
                if (similarity(s.tokens, a.tokens) >= SAME_SCREEN_SIMILARITY) { known = s; break; }
            }
        }
        if (known != null) screensByHash.put(a.hash, known);
        return known;
    }

    private static float similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0f;
        int shared = 0;
        for (String t : b) if (a.contains(t)) shared++;
        return shared / (float) (a.size() + b.size() - shared);
    }

    private Screen addScreen(ScreenAnalyzer.Result a, Tap arrivedBy) {
        Screen s = new Screen();
        s.tokens.addAll(a.tokens);
        s.feature = featureFor(a, arrivedBy);
        if (rootScreen == null && !a.auth) rootScreen = s;
        screens.add(s);
        screensByHash.put(a.hash, s);
        return s;
    }

    private String featureFor(ScreenAnalyzer.Result a, Tap arrivedBy) {
        if (a.auth) return FEATURE_SIGN_IN;
        if (rootScreen == null) return FEATURE_HOME;
        if (arrivedBy == null) return firstNonEmpty(a.title, currentActivity, FEATURE_HOME);
        if (arrivedBy.source == rootScreen || arrivedBy.nav) {
            return firstNonEmpty(arrivedBy.label, a.title, currentActivity);
        }
        return arrivedBy.source.feature;
    }

    private static String firstNonEmpty(String... options) {
        for (String s : options) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    // ---- Tapping ------------------------------------------------------------

    private ScreenAnalyzer.Candidate nextCandidate(Screen screen, ScreenAnalyzer.Result a) {
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (screen.tried.contains(c.signature)) continue;
            Integer misses = screen.kindMisses.get(c.kind);
            if (misses != null && misses >= MAX_KIND_MISSES) continue;
            return c;
        }
        return null;
    }

    private void tap(Screen screen, ScreenAnalyzer.Candidate c) {
        screen.tried.add(c.signature);
        screen.taps++;

        Tap t = new Tap();
        t.source = screen;
        t.kind = c.kind;
        t.label = c.label;
        t.nav = c.nav;
        lastTap = t;

        if (c.editable) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, DUMMY_TEXT);
            if (c.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return;
        }
        AccessibilityNodeInfo clickable = c.node;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable == null || !clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tapCenter(c.node);
        }
    }

    private void tapCenter(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    // ---- Capture ------------------------------------------------------------

    private void capture(Screen screen, ScreenAnalyzer.Result a) {
        if (store.shotsIn(screen.feature) >= MAX_SHOTS_PER_FEATURE) {
            Crawler.duplicatesSkipped++;
            return;
        }
        ScreenCaptureService capture = ScreenCaptureService.instance;
        if (capture == null) {
            engine.removeCallbacks(stepRunnable);
            Crawler.state = Crawler.State.PAUSED;
            Crawler.status(getString(R.string.status_capture_lost));
            return;
        }

        long hiddenAt = overlay.hideForCapture();
        Bitmap bitmap = capture.captureFrameAfter(hiddenAt);
        overlay.showAfterCapture();
        if (bitmap == null) return;

        try {
            long[] image = imageHash(bitmap);
            if (image == null) {
                Crawler.status(getString(R.string.status_protected));
            } else if (isDuplicateImage(image)) {
                Crawler.duplicatesSkipped++;
                Crawler.notifyChanged();
            } else if (store.save(bitmap, screen.feature, firstNonEmpty(a.title, currentActivity))) {
                savedImages.add(image);
                Crawler.shotCount++;
                Crawler.featureCount = store.featureCount();
                Crawler.status(getString(R.string.status_saved, screen.feature));
            } else {
                Crawler.status(getString(R.string.status_save_failed));
            }
        } finally {
            bitmap.recycle();
        }
    }

    private boolean isDuplicateImage(long[] image) {
        for (long[] other : savedImages) {
            int bits = 0;
            for (int i = 0; i < image.length; i++) bits += Long.bitCount(image[i] ^ other[i]);
            if (bits <= SAME_IMAGE_BITS) return true;
        }
        return false;
    }

    /** A 256-bit difference hash, or null for a blank frame such as a FLAG_SECURE window. */
    private static long[] imageHash(Bitmap bitmap) {
        final int w = 17, h = 16;
        Bitmap small = Bitmap.createScaledBitmap(bitmap, w, h, true);
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        small.recycle();

        int min = 255, max = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int luma = (((c >> 16) & 0xFF) * 30 + ((c >> 8) & 0xFF) * 59 + (c & 0xFF) * 11) / 100;
            pixels[i] = luma;
            if (luma < min) min = luma;
            if (luma > max) max = luma;
        }
        if (max - min < BLANK_LUMA_RANGE) return null;

        long[] hash = new long[4];
        int bit = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w - 1; x++, bit++) {
                if (pixels[y * w + x] > pixels[y * w + x + 1]) hash[bit / 64] |= 1L << (bit % 64);
            }
        }
        return hash;
    }
}
