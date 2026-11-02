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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The capture engine. Walks the target app depth-first through the accessibility
 * tree and saves one screenshot of every distinct screen, for an inventory of the
 * whole app.
 *
 * <p>Each tab is its own journey: the current tab's content is explored to the end,
 * then the next tab is opened and gets its own feature folder. The engine remembers
 * how screens connect, so when Back leaves the app, it reopens the app and returns
 * to whatever is still unexplored. Sign-in screens and permission dialogs are left
 * to the user: the crawl waits, then carries on by itself.
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

    private static final int MAX_STEPS = 100000;
    private static final int MAX_SCROLLS_PER_SCREEN = 25;
    private static final int MAX_TAPS_PER_SCREEN = 200;
    /** Rows of a list that lead to a screen already seen before giving up on the list. */
    private static final int MAX_KIND_MISSES = 3;
    /** Distinct-looking screens with one template, from one list, before it counts as a feed. */
    private static final int MAX_SAME_TEMPLATE = 5;
    /** Reopening the app without finding anything new. Any new screen resets the count. */
    private static final int MAX_FRUITLESS_RELAUNCHES = 8;
    private static final int MAX_ROUTE_TRIES = 6;
    private static final int MAX_BACKS_ON_SCREEN = 3;
    private static final int MAX_FOREIGN_BACKS = 4;
    private static final int MAX_SETTLE_TRIES = 4;
    private static final int MAX_LOADING_TRIES = 10;
    private static final int CLEAR_POLLS_TO_RESUME = 2;

    private static final float SAME_STRUCTURE = 0.85f;
    private static final float SAME_LABELS = 0.6f;
    private static final float SAME_TEMPLATE_STRUCTURE = 0.9f;
    private static final float SAME_TEMPLATE_LABELS = 0.5f;

    private static final int HASH_WIDTH = 33;
    private static final int HASH_HEIGHT = 32;
    /** Differing bits out of 1024 below which two images are the same picture. */
    private static final int SAME_IMAGE_BITS = 6;
    private static final float STATUS_BAR_FRACTION = 0.05f;
    private static final int BLANK_LUMA_RANGE = 6;

    private static final class Screen {
        final Set<String> structure = new HashSet<>();
        final Set<String> labels = new HashSet<>();
        final Set<String> selected = new HashSet<>();
        final Set<String> tried = new HashSet<>();
        final Map<String, Integer> kindMisses = new HashMap<>();
        final Map<String, Integer> kindTemplates = new HashMap<>();
        final Map<String, Screen> kindFirstTarget = new HashMap<>();
        /** Where each tap from this screen has led. */
        final Map<String, Screen> edges = new HashMap<>();
        String activity;
        String feature;
        int taps;
        int scrolls;
        int routeTries;
        boolean exhausted;
    }

    private static final class Tap {
        Screen source;
        String signature;
        String kind;
        String label;
        boolean nav;
        boolean row;
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
    /** Bottom-bar tabs are the same everywhere, so each one is only opened once per run. */
    private final Set<String> triedBottomTabs = new HashSet<>();
    private final Map<String, Screen> bottomTabHomes = new HashMap<>();

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
    private int fruitlessRelaunches;
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
        triedBottomTabs.clear();
        bottomTabHomes.clear();
        currentActivity = "";
        rootScreen = null;
        authScreen = null;
        lastBackScreen = null;
        scrolledScreen = null;
        lastTap = null;
        settleHash = null;
        settleTries = 0;
        steps = 0;
        fruitlessRelaunches = 0;
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
        if (hasUnexplored() && fruitlessRelaunches < MAX_FRUITLESS_RELAUNCHES && launchTarget()) {
            fruitlessRelaunches++;
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
        ScreenAnalyzer.Result a = analyze(root);

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

        Tap arrivedBy = lastTap;
        lastTap = null;
        Screen screen = findKnownScreen(a, arrivedBy);
        scrolledScreen = null;
        boolean isNew = screen == null;
        if (isNew) {
            screen = addScreen(a, arrivedBy);
            fruitlessRelaunches = 0;
        }
        recordArrival(arrivedBy, screen, isNew);
        markSelectedTabs(screen, a);

        if (a.auth && !skippedAuth.contains(screen)) {
            if (isNew) capture(screen, a);
            enterAuthWait(screen);
            return;
        }
        if (isNew) capture(screen, a);
        if (a.auth) {
            screen.exhausted = true;
            retreat(screen);
            return;
        }

        // Content first, then more content by scrolling, and only then the next tab.
        ScreenAnalyzer.Candidate next = nextContent(screen, a);
        if (next != null) {
            act(screen, next);
        } else if (scrollOn(screen, a)) {
            scrolledScreen = screen;
            settleHash = null;
            reschedule(AFTER_TAP_DELAY);
        } else if ((next = nextBottomTab(screen, a)) != null) {
            act(screen, next);
            // With no tabs left to open from here, nothing is pending on this screen.
            if (nextBottomTab(screen, a) == null) screen.exhausted = true;
        } else {
            screen.exhausted = true;
            leaveExhausted(screen, a);
        }
    }

    private ScreenAnalyzer.Result analyze(AccessibilityNodeInfo root) {
        return ScreenAnalyzer.analyze(
                root, getResources().getDisplayMetrics().heightPixels, currentActivity);
    }

    private void act(Screen screen, ScreenAnalyzer.Candidate next) {
        tap(screen, next, true);
        settleHash = null;
        backsOnScreen = 0;
        reschedule(AFTER_TAP_DELAY);
    }

    private boolean scrollOn(Screen screen, ScreenAnalyzer.Result a) {
        if (screen.scrolls >= MAX_SCROLLS_PER_SCREEN || a.scrollable == null) return false;
        if (!a.scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false;
        screen.scrolls++;
        return true;
    }

    /** Nothing left here: head for the most recent unfinished screen, or finish. */
    private void leaveExhausted(Screen screen, ScreenAnalyzer.Result a) {
        Screen target = unfinishedScreen();
        if (target == null) {
            finish(getString(R.string.summary_complete));
            return;
        }
        ScreenAnalyzer.Candidate step = routeStep(screen, target, a);
        if (step != null) {
            tap(screen, step, false);
            settleHash = null;
            reschedule(AFTER_TAP_DELAY);
        } else {
            retreat(screen);
        }
    }

    private Screen unfinishedScreen() {
        for (int i = screens.size() - 1; i >= 0; i--) {
            Screen s = screens.get(i);
            if (s.exhausted) continue;
            if (++s.routeTries > MAX_ROUTE_TRIES) {
                s.exhausted = true; // Can't get back to it, so don't let it stall the run.
                continue;
            }
            return s;
        }
        return null;
    }

    /** The element on this screen that leads toward {@code target}, if the way is known. */
    private ScreenAnalyzer.Candidate routeStep(Screen from, Screen target, ScreenAnalyzer.Result a) {
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.bottomBar && bottomTabHomes.get(c.signature) == target) return c;
        }
        String first = firstStepToward(from, target);
        if (first == null) return null;
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (first.equals(c.signature)) return c;
        }
        return null;
    }

    /** Breadth-first search over the taps recorded so far. */
    private static String firstStepToward(Screen from, Screen target) {
        Map<Screen, String> firstStep = new HashMap<>();
        ArrayDeque<Screen> queue = new ArrayDeque<>();
        Set<Screen> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            Screen s = queue.poll();
            for (Map.Entry<String, Screen> edge : s.edges.entrySet()) {
                Screen next = edge.getValue();
                if (!seen.add(next)) continue;
                String step = s == from ? edge.getKey() : firstStep.get(s);
                if (next == target) return step;
                firstStep.put(next, step);
                queue.add(next);
            }
        }
        return null;
    }

    private void retreat(Screen screen) {
        backsOnScreen = screen == lastBackScreen ? backsOnScreen + 1 : 1;
        lastBackScreen = screen;
        if (backsOnScreen > MAX_BACKS_ON_SCREEN) {
            backsOnScreen = 0;
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
            clearPolls = analyze(root).auth ? 0 : clearPolls + 1;
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

    private Screen findKnownScreen(ScreenAnalyzer.Result a, Tap arrivedBy) {
        Screen known = screensByHash.get(a.hash);
        if (known != null) return known;
        if (scrolledScreen != null && scrolledScreen.selected.equals(a.selected)) {
            // Scrolling reveals more of the same screen.
            known = scrolledScreen;
            known.structure.addAll(a.structure);
            known.labels.addAll(a.labels);
        } else {
            for (Screen s : screens) {
                if (isSameScreen(s, a)) { known = s; break; }
            }
        }
        if (known == null && arrivedBy != null && arrivedBy.row) {
            Screen sibling = arrivedBy.source.kindFirstTarget.get(arrivedBy.kind);
            if (sibling != null
                    && similarity(sibling.structure, a.structure) >= SAME_TEMPLATE_STRUCTURE
                    && similarity(sibling.labels, a.labels) >= SAME_TEMPLATE_LABELS) {
                known = sibling; // Another item's detail page: the same screen for an inventory.
            }
        }
        if (known != null) screensByHash.put(a.hash, known);
        return known;
    }

    private static boolean isSameScreen(Screen s, ScreenAnalyzer.Result a) {
        return s.selected.equals(a.selected)
                && s.activity.equals(a.activity)
                && similarity(s.structure, a.structure) >= SAME_STRUCTURE
                && similarity(s.labels, a.labels) >= SAME_LABELS;
    }

    private static float similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1f;
        int shared = 0;
        for (String t : b) if (a.contains(t)) shared++;
        return shared / (float) (a.size() + b.size() - shared);
    }

    private Screen addScreen(ScreenAnalyzer.Result a, Tap arrivedBy) {
        Screen s = new Screen();
        s.structure.addAll(a.structure);
        s.labels.addAll(a.labels);
        s.selected.addAll(a.selected);
        s.activity = a.activity;
        s.feature = featureFor(a, arrivedBy);
        if (rootScreen == null && !a.auth) rootScreen = s;
        screens.add(s);
        screensByHash.put(a.hash, s);
        return s;
    }

    private void recordArrival(Tap arrivedBy, Screen screen, boolean isNew) {
        if (arrivedBy == null) return;
        Screen source = arrivedBy.source;
        if (source != screen) source.edges.put(arrivedBy.signature, screen);
        if (!arrivedBy.row) return;
        if (isNew) {
            Screen first = source.kindFirstTarget.get(arrivedBy.kind);
            if (first == null) {
                source.kindFirstTarget.put(arrivedBy.kind, screen);
            } else if (similarity(first.structure, screen.structure) >= SAME_TEMPLATE_STRUCTURE
                    && source.kindTemplates.merge(arrivedBy.kind, 1, Integer::sum) >= MAX_SAME_TEMPLATE) {
                source.kindMisses.put(arrivedBy.kind, MAX_KIND_MISSES);
            }
        } else if (source != screen) {
            // A row that does nothing says little, but one reaching a known screen suggests a feed.
            source.kindMisses.merge(arrivedBy.kind, 1, Integer::sum);
        }
    }

    private void markSelectedTabs(Screen screen, ScreenAnalyzer.Result a) {
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (!c.selected) continue;
            screen.tried.add(c.signature);
            if (c.bottomBar) {
                triedBottomTabs.add(c.signature);
                bottomTabHomes.put(c.signature, screen);
            }
        }
    }

    private String featureFor(ScreenAnalyzer.Result a, Tap arrivedBy) {
        if (a.auth) return FEATURE_SIGN_IN;
        if (rootScreen == null) return FEATURE_HOME;
        if (arrivedBy == null) return firstNonEmpty(a.title, a.activity, FEATURE_HOME);
        if (arrivedBy.source == rootScreen || arrivedBy.nav) {
            return firstNonEmpty(arrivedBy.label, a.title, a.activity);
        }
        return arrivedBy.source.feature;
    }

    private static String firstNonEmpty(String... options) {
        for (String s : options) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    // ---- Tapping ------------------------------------------------------------

    private ScreenAnalyzer.Candidate nextContent(Screen screen, ScreenAnalyzer.Result a) {
        if (screen.taps >= MAX_TAPS_PER_SCREEN) return null;
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.bottomBar || screen.tried.contains(c.signature)) continue;
            Integer misses = screen.kindMisses.get(c.kind);
            if (misses != null && misses >= MAX_KIND_MISSES) continue;
            return c;
        }
        return null;
    }

    private ScreenAnalyzer.Candidate nextBottomTab(Screen screen, ScreenAnalyzer.Result a) {
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.bottomBar && !triedBottomTabs.contains(c.signature)) return c;
        }
        return null;
    }

    private void tap(Screen screen, ScreenAnalyzer.Candidate c, boolean exploring) {
        if (exploring) {
            screen.tried.add(c.signature);
            screen.taps++;
            if (c.bottomBar) triedBottomTabs.add(c.signature);
        }

        Tap t = new Tap();
        t.source = screen;
        t.signature = c.signature;
        t.kind = c.kind;
        t.label = c.label;
        t.nav = c.nav;
        t.row = exploring && c.row;
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
            } else if (store.save(bitmap, screen.feature, firstNonEmpty(a.title, a.activity))) {
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

    /**
     * A difference hash that only matches truly identical pictures, or null for a blank
     * frame such as a FLAG_SECURE window. The status bar is left out, because of its clock.
     */
    private static long[] imageHash(Bitmap bitmap) {
        int top = (int) (bitmap.getHeight() * STATUS_BAR_FRACTION);
        Bitmap body = Bitmap.createBitmap(bitmap, 0, top, bitmap.getWidth(), bitmap.getHeight() - top);
        Bitmap small = Bitmap.createScaledBitmap(body, HASH_WIDTH, HASH_HEIGHT, true);
        if (body != bitmap) body.recycle();
        int[] pixels = new int[HASH_WIDTH * HASH_HEIGHT];
        small.getPixels(pixels, 0, HASH_WIDTH, 0, 0, HASH_WIDTH, HASH_HEIGHT);
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

        long[] hash = new long[(HASH_WIDTH - 1) * HASH_HEIGHT / 64];
        int bit = 0;
        for (int y = 0; y < HASH_HEIGHT; y++) {
            for (int x = 0; x < HASH_WIDTH - 1; x++, bit++) {
                if (pixels[y * HASH_WIDTH + x] > pixels[y * HASH_WIDTH + x + 1]) {
                    hash[bit / 64] |= 1L << (bit % 64);
                }
            }
        }
        return hash;
    }
}
