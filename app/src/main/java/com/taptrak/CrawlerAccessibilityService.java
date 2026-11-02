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
import android.util.DisplayMetrics;
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
 * The capture engine: a depth-first walk of the target app that tries every
 * interaction, and screenshots new pages and any new UI that an interaction reveals.
 * Filling a field or selecting an option isn't captured unless something new appears.
 *
 * <p>On each screen it tries every element, then scrolls for more, then moves on
 * to tabs, and finally presses Back. If Back leaves the app, it reopens the app and
 * returns to what's unfinished. Sign-in screens and permission dialogs are left to
 * the user: the crawl waits, then carries on by itself.
 */
public class CrawlerAccessibilityService extends AccessibilityService {

    public static volatile CrawlerAccessibilityService instance;

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String FOLDER_HOME = "Home";
    private static final String FOLDER_SIGN_IN = "Sign-in";
    private static final String FOLDER_DEFAULT = "Screen";
    private static final String DUMMY_TEXT = "test";

    private static final long FIRST_STEP_DELAY = 1000;
    private static final long AFTER_ACTION_DELAY = 40;
    private static final long LOADING_POLL_DELAY = 100;
    private static final long WAIT_POLL_DELAY = 500;
    /** A screen counts as settled once no new frame has been drawn for this long. */
    private static final long QUIET_MS = 90;
    private static final long MAX_QUIET_WAIT_MS = 600;

    private static final int MAX_STEPS = 1000000;
    /** At most two full scrolls per screen, then move on. */
    private static final int MAX_SCROLLS_PER_SCREEN = 2;
    private static final int MAX_TAPS_PER_SCREEN = 2000;
    /**
     * Interactions in a row without a new screenshot before the app counts as finished.
     * Kept high so a big app with many already-seen screens between new ones isn't cut short;
     * the run still ends once there is genuinely nothing unexplored left to reach.
     */
    private static final int MAX_FRUITLESS_INTERACTIONS = 8000;
    private static final int MAX_FRUITLESS_RELAUNCHES = 40;
    private static final int MAX_ROUTE_TRIES = 60;
    private static final int MAX_BACKS_ON_SCREEN = 12;
    private static final int MAX_FOREIGN_BACKS = 6;
    private static final int MAX_LOADING_POLLS = 10;
    private static final int CLEAR_POLLS_TO_RESUME = 2;
    private static final int MAX_FOLDER_DEPTH = 6;

    /** Screens exposing fewer elements than this also get tapped on a grid of points. */
    private static final int SPARSE_ELEMENTS = 3;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 8;
    private static final float GRID_TOP = 0.1f;
    private static final float GRID_BOTTOM = 0.92f;

    private static final float SAME_FOLDER_STRUCTURE = 0.8f;

    private static final int HASH_WIDTH = 33;
    private static final int HASH_HEIGHT = 32;
    /** Differing bits out of 1024 below which two images are the same picture. */
    private static final int SAME_IMAGE_BITS = 6;
    /** A scroll smaller than this hasn't shown enough new content to capture. */
    private static final int MIN_SCROLL_CHANGE_BITS = 80;
    private static final int MIN_NEW_LABELS = 2;
    private static final float STATUS_BAR_FRACTION = 0.05f;
    private static final int BLANK_LUMA_RANGE = 6;

    private static final class Screen {
        final Set<String> structure = new HashSet<>();
        final Set<String> labels = new HashSet<>();
        final Set<String> selected = new HashSet<>();
        final Set<String> tried = new HashSet<>();
        /** Where each tap from this screen has led. */
        final Map<String, Screen> edges = new HashMap<>();
        String activity;
        String folder;
        String tabParentFolder;
        /** The last image saved of this screen, which scrolling is measured against. */
        long[] lastImage;
        int taps;
        int scrolls;
        int gridTaps;
        int routeTries;
        boolean exhausted;
    }

    private static final class Tap {
        Screen source;
        String signature;
        String label;
        boolean nav;
        boolean bottomBar;
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
    private int loadingPolls;
    private int steps;
    private int fruitlessInteractions;
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
        if (store != null) store.close();
        if (engineThread != null) engineThread.quitSafely();
        super.onDestroy();
    }

    // ---- Controls, called from the main screen and the overlay --------------

    public void startCrawl() {
        overlay.attach();
        engine.post(() -> {
            if (store != null) store.close();
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
        currentActivity = "";
        rootScreen = null;
        authScreen = null;
        lastBackScreen = null;
        scrolledScreen = null;
        lastTap = null;
        loadingPolls = 0;
        steps = 0;
        fruitlessInteractions = 0;
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
        Crawler.status(status);
        reschedule(AFTER_ACTION_DELAY);
    }

    private void finish(String why) {
        if (!Crawler.isActive()) return;
        engine.removeCallbacks(stepRunnable);
        Crawler.state = Crawler.State.IDLE;
        Crawler.lastSummary = getString(R.string.summary_format, why, Crawler.shotCount,
                Crawler.folderCount, Crawler.duplicatesSkipped, Crawler.permissionsGranted);
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
            if (state == Crawler.State.RUNNING) {
                ScreenCaptureService capture = ScreenCaptureService.instance;
                if (capture != null) capture.awaitQuietScreen(QUIET_MS, MAX_QUIET_WAIT_MS);
            }
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
            reschedule(LOADING_POLL_DELAY);
        }
    }

    private void stepRunning(AccessibilityNodeInfo root, String pkg) {
        if (Crawler.shotCount >= Crawler.maxShots) { finish(getString(R.string.summary_limit)); return; }
        if (++steps > MAX_STEPS || fruitlessInteractions > MAX_FRUITLESS_INTERACTIONS) {
            finish(getString(R.string.summary_complete));
            return;
        }
        if (root == null) { reschedule(LOADING_POLL_DELAY); return; }

        if (pkg.equals(getPackageName()) || pkg.equals(SYSTEM_UI) && !isBiometricPrompt(root)) {
            reschedule(LOADING_POLL_DELAY);
        } else if (isPermissionDialog(pkg)) {
            enterPermissionWait();
        } else if (isExternalAuth(pkg) || pkg.equals(SYSTEM_UI)) {
            enterAuthWait(null);
        } else if (pkg.equals(launcherPkg) || pkg.isEmpty()) {
            if (seenTarget) relaunchOrFinish(); else reschedule(LOADING_POLL_DELAY);
        } else if (!pkg.equals(Crawler.targetPackage)) {
            onForeignApp();
        } else {
            seenTarget = true;
            foreignBacks = 0;
            exploreTarget(root);
        }
    }

    private void onForeignApp() {
        if (++foreignBacks > MAX_FOREIGN_BACKS) { relaunchOrFinish(); return; }
        performGlobalAction(GLOBAL_ACTION_BACK);
        reschedule(AFTER_ACTION_DELAY);
    }

    private void relaunchOrFinish() {
        if (hasUnfinished() && fruitlessRelaunches < MAX_FRUITLESS_RELAUNCHES && launchTarget()) {
            fruitlessRelaunches++;
            foreignBacks = 0;
            backsOnScreen = 0;
            lastTap = null;
            reschedule(FIRST_STEP_DELAY);
        } else {
            finish(getString(R.string.summary_complete));
        }
    }

    private boolean hasUnfinished() {
        for (Screen s : screens) if (!s.exhausted) return true;
        return false;
    }

    private void exploreTarget(AccessibilityNodeInfo root) {
        ScreenAnalyzer.Result a = analyze(root);
        if (a.loading && loadingPolls < MAX_LOADING_POLLS) {
            loadingPolls++;
            reschedule(LOADING_POLL_DELAY);
            return;
        }
        loadingPolls = 0;

        Tap arrivedBy = lastTap;
        Screen from = arrivedBy != null ? arrivedBy.source : scrolledScreen;
        boolean scrolled = arrivedBy == null && scrolledScreen != null;
        lastTap = null;
        Screen screen = findKnownScreen(a);
        scrolledScreen = null;
        boolean isNew = screen == null;
        if (isNew) screen = addScreen(a, arrivedBy);
        if (arrivedBy != null && arrivedBy.source != screen) {
            arrivedBy.source.edges.put(arrivedBy.signature, screen);
        }
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.selected) screen.tried.add(c.signature);
        }

        captureIfSignificant(screen, a, from, scrolled, isNew);
        if (a.auth && !skippedAuth.contains(screen)) {
            enterAuthWait(screen);
            return;
        }
        if (a.auth) {
            screen.exhausted = true;
            retreat(screen);
            return;
        }

        // Depth first: this screen's content, then more of it by scrolling, then tabs.
        ScreenAnalyzer.Candidate next = nextElement(screen, a, false);
        if (next != null) {
            act(screen, next);
        } else if (scrollOn(screen, a)) {
            scrolledScreen = screen;
            fruitlessInteractions++;
            reschedule(AFTER_ACTION_DELAY);
        } else if (a.candidates.size() < SPARSE_ELEMENTS && screen.gridTaps < GRID_COLUMNS * GRID_ROWS) {
            tapGridPoint(screen);
        } else if ((next = nextElement(screen, a, true)) != null) {
            act(screen, next);
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
        backsOnScreen = 0;
        fruitlessInteractions++;
        reschedule(AFTER_ACTION_DELAY);
    }

    private boolean scrollOn(Screen screen, ScreenAnalyzer.Result a) {
        if (screen.scrolls >= MAX_SCROLLS_PER_SCREEN || a.scrollable == null) return false;
        if (!a.scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false;
        screen.scrolls++;
        return true;
    }

    /** Nothing left here: go back up, or head straight for the nearest unfinished screen. */
    private void leaveExhausted(Screen screen, ScreenAnalyzer.Result a) {
        Screen target = unfinishedScreen();
        if (target == null) {
            finish(getString(R.string.summary_complete));
            return;
        }
        ScreenAnalyzer.Candidate step = routeStep(screen, target, a);
        if (step != null) {
            tap(screen, step, false);
            reschedule(AFTER_ACTION_DELAY);
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
        performGlobalAction(GLOBAL_ACTION_BACK);
        reschedule(AFTER_ACTION_DELAY);
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

    // ---- Screens and folders ------------------------------------------------

    /** Screens only merge on an exact match. After a scroll, it's still the same screen. */
    private Screen findKnownScreen(ScreenAnalyzer.Result a) {
        Screen known = screensByHash.get(a.hash);
        if (known == null && scrolledScreen != null && scrolledScreen.selected.equals(a.selected)) {
            known = scrolledScreen;
            screensByHash.put(a.hash, known);
        }
        return known;
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
        assignFolder(s, a, arrivedBy);
        if (rootScreen == null && !a.auth) rootScreen = s;
        screens.add(s);
        screensByHash.put(a.hash, s);
        fruitlessRelaunches = 0;
        return s;
    }

    /** Folders mirror the path taken: each tab at the top, then one level per screen opened. */
    private void assignFolder(Screen s, ScreenAnalyzer.Result a, Tap by) {
        if (a.auth) {
            s.folder = FOLDER_SIGN_IN;
        } else if (rootScreen == null) {
            s.folder = FOLDER_HOME;
        } else if (by == null) {
            s.folder = firstNonEmpty(a.title, a.activity, FOLDER_DEFAULT);
        } else {
            String name = firstNonEmpty(by.label, a.title, a.activity, FOLDER_DEFAULT);
            Screen from = by.source;
            if (by.bottomBar) {
                s.folder = name;
            } else if (by.nav) {
                // Sibling tabs sit side by side instead of nesting inside each other.
                s.tabParentFolder = from.tabParentFolder != null ? from.tabParentFolder : from.folder;
                s.folder = nest(s.tabParentFolder, name);
            } else if (similarity(from.structure, a.structure) >= SAME_FOLDER_STRUCTURE) {
                s.folder = from.folder;
            } else {
                s.folder = nest(from.folder, name);
            }
        }
    }

    private static String nest(String parent, String name) {
        int depth = parent.split(ScreenshotStore.SEPARATOR).length;
        return depth >= MAX_FOLDER_DEPTH ? parent : parent + ScreenshotStore.SEPARATOR
                + name.replace(ScreenshotStore.SEPARATOR, " ");
    }

    private static String firstNonEmpty(String... options) {
        for (String s : options) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    // ---- Interacting --------------------------------------------------------

    private ScreenAnalyzer.Candidate nextElement(Screen screen, ScreenAnalyzer.Result a, boolean bottomBar) {
        if (screen.taps >= MAX_TAPS_PER_SCREEN) return null;
        // Touch everything: every untried button, field, list row and tab.
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.bottomBar == bottomBar && !screen.tried.contains(c.signature)) return c;
        }
        return null;
    }

    private void tap(Screen screen, ScreenAnalyzer.Candidate c, boolean exploring) {
        if (exploring) {
            screen.tried.add(c.signature);
            screen.taps++;
        }

        Tap t = new Tap();
        t.source = screen;
        t.signature = c.signature;
        t.label = c.label;
        t.nav = c.nav;
        t.bottomBar = c.bottomBar;
        lastTap = t;

        if (c.editable) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, DUMMY_TEXT);
            if (c.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return;
        }
        AccessibilityNodeInfo clickable = c.node;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable == null || !clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect r = new Rect();
            c.node.getBoundsInScreen(r);
            tapAt(r.centerX(), r.centerY());
        }
    }

    /** For games, canvases and web content, which expose little to tap by name. */
    private void tapGridPoint(Screen screen) {
        int index = screen.gridTaps++;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float cellWidth = dm.widthPixels / (float) GRID_COLUMNS;
        float top = dm.heightPixels * GRID_TOP;
        float cellHeight = dm.heightPixels * (GRID_BOTTOM - GRID_TOP) / GRID_ROWS;

        Tap t = new Tap();
        t.source = screen;
        t.signature = "grid|" + index;
        t.label = "";
        lastTap = t;

        tapAt(cellWidth * (index % GRID_COLUMNS + 0.5f),
                top + cellHeight * (index / GRID_COLUMNS + 0.5f));
        fruitlessInteractions++;
        reschedule(AFTER_ACTION_DELAY);
    }

    private void tapAt(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    // ---- Capture ------------------------------------------------------------

    /**
     * Every newly-seen screen (a layout the crawl hasn't recorded before) is captured, and
     * every scroll that reveals enough new content. Returning to a known screen by Back, or a
     * tap that changes nothing, is not. The perceptual image hash in {@link #capture} is the
     * final guard that drops anything that still looks the same as a shot already saved, so
     * this errs toward capturing — the goal is the most useful shots without duplicates.
     */
    private void captureIfSignificant(Screen screen, ScreenAnalyzer.Result a, Screen from,
                                      boolean scrolled, boolean isNew) {
        if (scrolled) {
            capture(screen, a, screen.lastImage);
        } else if (isNew) {
            capture(screen, a, null);
        }
    }

    private void capture(Screen screen, ScreenAnalyzer.Result a, long[] mustDifferFrom) {
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

        long[] image = imageHash(bitmap);
        if (image == null || isDuplicateImage(image)
                || mustDifferFrom != null && differingBits(image, mustDifferFrom) < MIN_SCROLL_CHANGE_BITS) {
            if (image != null) Crawler.duplicatesSkipped++;
            bitmap.recycle();
            Crawler.notifyChanged();
            return;
        }
        screen.lastImage = image;
        // The store saves in the background and recycles the bitmap when it's done.
        store.save(bitmap, screen.folder, firstNonEmpty(a.title, a.activity, FOLDER_DEFAULT));
        savedImages.add(image);
        fruitlessInteractions = 0;
        Crawler.shotCount++;
        Crawler.folderCount = store.folderCount();
        Crawler.status(getString(R.string.status_saved, screen.folder));
    }

    private boolean isDuplicateImage(long[] image) {
        for (long[] other : savedImages) {
            if (differingBits(image, other) <= SAME_IMAGE_BITS) return true;
        }
        return false;
    }

    private static int differingBits(long[] a, long[] b) {
        int bits = 0;
        for (int i = 0; i < a.length; i++) bits += Long.bitCount(a[i] ^ b[i]);
        return bits;
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
