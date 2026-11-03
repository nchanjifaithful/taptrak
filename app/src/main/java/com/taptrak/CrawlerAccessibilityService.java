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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Navigation-graph capture engine.
 *
 * <p>Each distinct screen is a node, keyed by its <em>structure</em> (activity, view ids and
 * role skeleton, and which tabs are selected) rather than its text — so the same layout showing
 * different data is one node, and every row of a list leads back to the same detail node instead
 * of a hundred near-identical screens. Each tappable element is an edge. The engine grows a
 * transition graph, always choosing the untried action most likely to reveal a node it hasn't
 * seen, and screenshots a node exactly once.
 *
 * <p>Transition frames are avoided by a settle gate: after every action it waits until
 * accessibility events fall silent, the view tree is stable across consecutive polls, and no
 * progress spinner is showing, then confirms with two still frames before saving. Anything still
 * animating is rejected and retried.
 *
 * <p>Navigation is hybrid: to reach the next unexplored screen it first tries the system Back
 * button, and when that fails or it ends up somewhere unexpected it relaunches the app and
 * replays the recorded tap-path to the target.
 *
 * <p>Scope: for testing apps you own or are authorised to test. FLAG_SECURE is not bypassed —
 * protected windows capture blank and are skipped.
 */
public class CrawlerAccessibilityService extends AccessibilityService {

    public static volatile CrawlerAccessibilityService instance;

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String FOLDER_HOME = "Home";
    private static final String FOLDER_SIGN_IN = "Sign-in";
    private static final String FOLDER_DEFAULT = "Screen";
    private static final String DUMMY_TEXT = "test";

    // Timing.
    private static final long FIRST_STEP_DELAY = 1200;
    private static final long STEP_DELAY = 60;
    private static final long AFTER_BACK_DELAY = 250;
    private static final long WAIT_POLL_DELAY = 500;
    private static final long LOADING_POLL_DELAY = 150;

    // Settle gate.
    private static final long EVENT_DEBOUNCE = 350;   // a11y events must be silent this long
    private static final long SETTLE_POLL = 100;
    private static final int TREE_STABLE_POLLS = 2;   // equal structural hashes in a row
    private static final long MAX_SETTLE = 4000;      // give up settling (animation/video)
    private static final long PIXEL_QUIET = 120;
    private static final long PIXEL_MAX_WAIT = 1200;
    private static final long CONFIRM_DELAY = 160;    // gap between the two confirmation frames
    private static final int CONFIRM_ATTEMPTS = 3;
    private static final int MAX_CAPTURE_FAILS = 3;   // blank/secure screens: stop retrying

    // Exploration bounds.
    private static final int MAX_STEPS = 1000000;
    private static final int MAX_SCROLLS_PER_NODE = 2;
    private static final int MAX_TAPS_PER_NODE = 400;
    private static final int MAX_BACKS_SEARCH = 4;    // Back tries before falling back to replay
    private static final int MAX_FOREIGN_BACKS = 4;
    private static final int MAX_REPLAY_FAILS = 3;
    private static final int MAX_RELAUNCHES = 80;
    private static final int MAX_FRONTIER_TRIES = 4;  // give up on an unreachable frontier node
    private static final int MAX_LOADING_POLLS = 12;
    private static final int CLEAR_POLLS_TO_RESUME = 2;

    // Blind-tap grid for games / canvas / WebView that expose little to tap by name.
    private static final int SPARSE_ELEMENTS = 3;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 8;
    private static final float GRID_TOP = 0.1f;
    private static final float GRID_BOTTOM = 0.92f;

    // Image fingerprint (difference hash) for the confirm step and duplicate backstop.
    private static final int HASH_WIDTH = 33;
    private static final int HASH_HEIGHT = 32;
    private static final int SAME_IMAGE_BITS = 6;
    private static final float STATUS_BAR_FRACTION = 0.05f;
    private static final int BLANK_LUMA_RANGE = 6;

    private enum Nav { EXPLORE, BACK_SEARCH, REPLAY }

    private static final class Node {
        String key;
        String title;
        String folder;
        List<String> pathFromRoot;            // action signatures from the root node
        final Set<String> actions = new HashSet<>();
        final Set<String> tried = new HashSet<>();
        final Map<String, String> edges = new HashMap<>(); // action signature -> target node key
        boolean captured;
        boolean exhausted;
        boolean listDone;                     // a list row here already led to a known screen
        int taps;
        int scrolls;
        int gridTaps;
        int captureFails;
        int frontierTries;
    }

    private static final class Action {
        String sourceKey;
        String signature;
        String label;
        boolean listItem;
    }

    private HandlerThread engineThread;
    private Handler engine;
    private OverlayController overlay;
    private ScreenshotStore store;
    private final Runnable stepRunnable = this::doStep;

    private final Map<String, Node> nodes = new HashMap<>();

    private String launcherPkg = "";
    private String currentActivity = "";
    private volatile long lastEventAt;

    private Node root;
    private Node current;
    private Node authNode;
    private final Set<String> skippedAuth = new HashSet<>();
    private Action lastAction;

    private Nav nav = Nav.EXPLORE;
    private int backsLeft;
    private List<String> replayPath;
    private int replayIndex;
    private Node replayTarget;
    private int replayFails;

    private long[] lastCapturedImage;
    private int steps;
    private int relaunches;
    private int foreignBacks;
    private int loadingPolls;
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
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        if (pkg != null && Crawler.targetPackage.contentEquals(pkg)
                && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            lastEventAt = SystemClock.uptimeMillis();
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
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

    // ---- Controls -----------------------------------------------------------

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
                    if (authNode != null) skippedAuth.add(authNode.key);
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
        nodes.clear();
        skippedAuth.clear();
        currentActivity = "";
        root = null;
        current = null;
        authNode = null;
        lastAction = null;
        nav = Nav.EXPLORE;
        replayPath = null;
        replayTarget = null;
        replayIndex = 0;
        replayFails = 0;
        lastCapturedImage = null;
        steps = 0;
        relaunches = 0;
        foreignBacks = 0;
        loadingPolls = 0;
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
        reschedule(STEP_DELAY);
    }

    private void finish(String why) {
        if (!Crawler.isActive()) return;
        engine.removeCallbacks(stepRunnable);
        Crawler.state = Crawler.State.IDLE;
        Crawler.lastSummary = getString(R.string.summary_format, why, Crawler.shotCount,
                Crawler.folderCount, Crawler.duplicatesSkipped, Crawler.permissionsGranted);
        Crawler.status(why);
        overlay.detach();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void reschedule(long delay) {
        if (Crawler.isActive()) engine.postDelayed(stepRunnable, delay);
    }

    // ---- Step loop ----------------------------------------------------------

    private void doStep() {
        Crawler.State state = Crawler.state;
        if (state == Crawler.State.IDLE || state == Crawler.State.PAUSED) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String pkg = pkgOf(root);
            if (state != Crawler.State.RUNNING) { stepWaiting(state, root, pkg); return; }

            if (Crawler.shotCount >= Crawler.maxShots) { finish(getString(R.string.summary_limit)); return; }
            if (++steps > MAX_STEPS) { finish(getString(R.string.summary_complete)); return; }
            if (root == null) { reschedule(LOADING_POLL_DELAY); return; }

            if (pkg.equals(getPackageName()) || (pkg.equals(SYSTEM_UI) && !isBiometricPrompt(root))) {
                reschedule(LOADING_POLL_DELAY);
            } else if (isPermissionDialog(pkg)) {
                enterPermissionWait();
            } else if (isExternalAuth(pkg) || pkg.equals(SYSTEM_UI)) {
                enterAuthWait(null);
            } else if (pkg.equals(launcherPkg) || pkg.isEmpty()) {
                if (seenTarget) onLost(); else reschedule(LOADING_POLL_DELAY);
            } else if (!pkg.equals(Crawler.targetPackage)) {
                onForeign();
            } else {
                seenTarget = true;
                foreignBacks = 0;
                onTarget();
            }
        } catch (RuntimeException e) {
            Crawler.status("Step error: " + e.getClass().getSimpleName());
            reschedule(LOADING_POLL_DELAY);
        }
    }

    /** On the target app: settle, identify the node, capture it, then act. */
    private void onTarget() {
        boolean settled = settleOnTarget();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !pkgOf(root).equals(Crawler.targetPackage)) {
            reschedule(LOADING_POLL_DELAY);
            return;
        }
        ScreenAnalyzer.Result a = analyze(root);
        if (a.loading && loadingPolls < MAX_LOADING_POLLS && !settled) {
            loadingPolls++;
            reschedule(LOADING_POLL_DELAY);
            return;
        }
        loadingPolls = 0;

        Node node = resolveNode(a);
        current = node;

        if (a.auth && !skippedAuth.contains(node.key)) {
            if (!node.captured) node.captured = captureStable(node, a);
            enterAuthWait(node);
            return;
        }

        if (!node.captured) node.captured = captureStable(node, a);

        chooseAndAct(node, a);
    }

    /** Find or create the node for this screen, and record the edge that reached it. */
    private Node resolveNode(ScreenAnalyzer.Result a) {
        Node node = nodes.get(a.structuralKey);
        boolean isNew = node == null;
        Node source = lastAction != null ? nodes.get(lastAction.sourceKey) : null;
        if (isNew) {
            node = new Node();
            node.key = a.structuralKey;
            node.title = firstNonEmpty(a.title, a.activity, FOLDER_DEFAULT);
            if (root == null && !a.auth) { root = node; node.pathFromRoot = new ArrayList<>(); }
            node.folder = folderFor(a, source);
            if (node.pathFromRoot == null && source != null && source.pathFromRoot != null) {
                node.pathFromRoot = append(source.pathFromRoot, lastAction.signature);
            }
            nodes.put(node.key, node);
        }
        if (source != null && lastAction != null) {
            source.edges.put(lastAction.signature, node.key);
            if (lastAction.listItem && !isNew) source.listDone = true; // row led to a known screen
        }
        lastAction = null;
        // Don't re-toggle the tab we're already on.
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.selected) node.tried.add(c.signature);
        }
        return node;
    }

    private String folderFor(ScreenAnalyzer.Result a, Node source) {
        if (a.auth) return FOLDER_SIGN_IN;
        if (root == null || source == null) return FOLDER_HOME;
        String name = firstNonEmpty(lastAction != null ? lastAction.label : null,
                a.title, a.activity, FOLDER_DEFAULT);
        return nest(source.folder, name);
    }

    private void chooseAndAct(Node node, ScreenAnalyzer.Result a) {
        // Record the elements this screen exposes.
        for (ScreenAnalyzer.Candidate c : a.candidates) node.actions.add(c.signature);

        if (nav == Nav.REPLAY) { continueReplay(node, a); return; }

        // Returned to a node with work left: resume normal exploration.
        if (nav == Nav.BACK_SEARCH && untriedCandidate(node, a) != null) nav = Nav.EXPLORE;

        ScreenAnalyzer.Candidate next = untriedCandidate(node, a);
        if (next != null) { execute(node, next); reschedule(STEP_DELAY); return; }

        if (node.scrolls < MAX_SCROLLS_PER_NODE && scroll(node, a)) { reschedule(STEP_DELAY); return; }

        if (a.candidates.size() < SPARSE_ELEMENTS && node.gridTaps < GRID_COLUMNS * GRID_ROWS) {
            gridTap(node);
            reschedule(STEP_DELAY);
            return;
        }

        node.exhausted = true;
        goToFrontier();
    }

    /** The next element worth tapping here, skipping dead ends and repeated list rows. */
    private ScreenAnalyzer.Candidate untriedCandidate(Node node, ScreenAnalyzer.Result a) {
        if (node.taps >= MAX_TAPS_PER_NODE) return null;
        for (ScreenAnalyzer.Candidate c : a.candidates) {        // already priority-sorted
            if (node.tried.contains(c.signature)) continue;
            if (c.listItem && node.listDone) continue;            // one row stands in for the list
            String target = node.edges.get(c.signature);
            if (target != null) {
                Node tn = nodes.get(target);
                if (tn != null && tn.exhausted && tn.captured) { node.tried.add(c.signature); continue; }
            }
            return c;
        }
        return null;
    }

    private void execute(Node node, ScreenAnalyzer.Candidate c) {
        node.tried.add(c.signature);
        node.taps++;
        lastAction = new Action();
        lastAction.sourceKey = node.key;
        lastAction.signature = c.signature;
        lastAction.label = c.label;
        lastAction.listItem = c.listItem;
        tapCandidate(c);
    }

    private void tapCandidate(ScreenAnalyzer.Candidate c) {
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

    private boolean scroll(Node node, ScreenAnalyzer.Result a) {
        if (a.scrollable == null) return false;
        if (!a.scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false;
        node.scrolls++;
        lastAction = null;    // a scroll is not a graph edge
        return true;
    }

    private void gridTap(Node node) {
        int index = node.gridTaps++;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float cellWidth = dm.widthPixels / (float) GRID_COLUMNS;
        float top = dm.heightPixels * GRID_TOP;
        float cellHeight = dm.heightPixels * (GRID_BOTTOM - GRID_TOP) / GRID_ROWS;
        lastAction = null;
        tapAt(cellWidth * (index % GRID_COLUMNS + 0.5f),
                top + cellHeight * (index / GRID_COLUMNS + 0.5f));
    }

    private void tapAt(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    // ---- Navigation to the next unexplored screen ---------------------------

    private void goToFrontier() {
        Node target = frontier();
        if (target == null) { finish(getString(R.string.summary_complete)); return; }

        if (nav != Nav.BACK_SEARCH) { nav = Nav.BACK_SEARCH; backsLeft = MAX_BACKS_SEARCH; }
        if (backsLeft > 0) {
            backsLeft--;
            performGlobalAction(GLOBAL_ACTION_BACK);     // cheap retreat; may land on work to do
            reschedule(AFTER_BACK_DELAY);
        } else {
            startReplay(target);                          // reliable: relaunch and replay the path
        }
    }

    /** The unexplored node reachable with the shortest recorded path from the root. */
    private Node frontier() {
        Node best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Node n : nodes.values()) {
            if (n.exhausted || n.pathFromRoot == null) continue;
            if (n.frontierTries > MAX_FRONTIER_TRIES) { n.exhausted = true; continue; }
            if (n.tried.containsAll(n.actions)) continue;    // nothing left to try here
            if (n.pathFromRoot.size() < bestLen) { best = n; bestLen = n.pathFromRoot.size(); }
        }
        return best;
    }

    private void startReplay(Node target) {
        if (++relaunches > MAX_RELAUNCHES || !launchTarget()) {
            finish(getString(R.string.summary_complete));
            return;
        }
        nav = Nav.REPLAY;
        replayTarget = target;
        replayPath = target.pathFromRoot == null ? new ArrayList<>() : new ArrayList<>(target.pathFromRoot);
        replayIndex = 0;
        replayFails = 0;
        Crawler.status(getString(R.string.status_exploring));
        reschedule(FIRST_STEP_DELAY);
    }

    private void continueReplay(Node node, ScreenAnalyzer.Result a) {
        if (replayPath == null || replayIndex >= replayPath.size()) {
            nav = Nav.EXPLORE;                 // arrived at (or past) the target
            ScreenAnalyzer.Candidate next = untriedCandidate(node, a);
            if (next != null) { execute(node, next); reschedule(STEP_DELAY); }
            else { node.exhausted = true; goToFrontier(); }
            return;
        }
        ScreenAnalyzer.Candidate step = findBySignature(a, replayPath.get(replayIndex));
        if (step != null) {
            replayIndex++;
            execute(node, step);
            reschedule(STEP_DELAY);
            return;
        }
        // Path no longer matches the live UI.
        if (++replayFails > MAX_REPLAY_FAILS) {
            if (replayTarget != null) { replayTarget.frontierTries++; replayTarget.exhausted = replayTarget.frontierTries > MAX_FRONTIER_TRIES; }
            nav = Nav.EXPLORE;
            ScreenAnalyzer.Candidate next = untriedCandidate(node, a);
            if (next != null) { execute(node, next); reschedule(STEP_DELAY); }
            else goToFrontier();
        } else {
            startReplay(replayTarget);         // relaunch and try the path again from the top
        }
    }

    private static ScreenAnalyzer.Candidate findBySignature(ScreenAnalyzer.Result a, String signature) {
        for (ScreenAnalyzer.Candidate c : a.candidates) {
            if (c.signature.equals(signature)) return c;
        }
        return null;
    }

    private void onForeign() {
        if (++foreignBacks > MAX_FOREIGN_BACKS) { onLost(); return; }
        performGlobalAction(GLOBAL_ACTION_BACK);
        reschedule(AFTER_BACK_DELAY);
    }

    private void onLost() {
        Node target = frontier();
        if (target == null) finish(getString(R.string.summary_complete));
        else startReplay(target);
    }

    // ---- Waiting on the user ------------------------------------------------

    private void enterAuthWait(Node node) {
        authNode = node;
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

    // ---- Settle gate --------------------------------------------------------

    /**
     * Blocks until the screen has stopped changing: accessibility events quiet for
     * {@link #EVENT_DEBOUNCE}, the structural hash stable across {@link #TREE_STABLE_POLLS}
     * polls, no progress spinner, and finally the pixels quiet. Returns false if it never
     * settled within {@link #MAX_SETTLE} (constant animation/video) or the window changed.
     */
    private boolean settleOnTarget() {
        long deadline = SystemClock.uptimeMillis() + MAX_SETTLE;
        String lastKey = null;
        int stable = 0;
        while (SystemClock.uptimeMillis() < deadline && Crawler.state == Crawler.State.RUNNING) {
            if (SystemClock.uptimeMillis() - lastEventAt < EVENT_DEBOUNCE) {
                SystemClock.sleep(SETTLE_POLL);
                continue;
            }
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) { SystemClock.sleep(SETTLE_POLL); continue; }
            if (!pkgOf(r).equals(Crawler.targetPackage)) return false;
            ScreenAnalyzer.Result a = analyze(r);
            if (a.loading) { SystemClock.sleep(SETTLE_POLL); continue; }
            if (a.structuralKey.equals(lastKey)) stable++;
            else { lastKey = a.structuralKey; stable = 0; }
            if (stable >= TREE_STABLE_POLLS) {
                ScreenCaptureService c = ScreenCaptureService.instance;
                if (c != null) c.awaitQuietScreen(PIXEL_QUIET, PIXEL_MAX_WAIT);
                return true;
            }
            SystemClock.sleep(SETTLE_POLL);
        }
        return false;
    }

    // ---- Capture ------------------------------------------------------------

    /**
     * Saves one screenshot of this node, plus a text file of its on-screen text. Confirms the
     * screen is static with two frames taken {@link #CONFIRM_DELAY} apart so a mid-transition
     * frame is never saved. Returns true once the node is captured (or is blank/secure and
     * should stop being retried).
     */
    private boolean captureStable(Node node, ScreenAnalyzer.Result a) {
        ScreenCaptureService capture = ScreenCaptureService.instance;
        if (capture == null) {
            engine.removeCallbacks(stepRunnable);
            Crawler.state = Crawler.State.PAUSED;
            Crawler.status(getString(R.string.status_capture_lost));
            return false;
        }

        for (int attempt = 0; attempt < CONFIRM_ATTEMPTS; attempt++) {
            long hiddenAt = overlay.hideForCapture();
            Bitmap first = capture.captureFrameAfter(hiddenAt);
            if (first == null) { overlay.showAfterCapture(); return failCapture(node); }
            long[] h1 = imageHash(first);
            SystemClock.sleep(CONFIRM_DELAY);
            Bitmap second = capture.captureFrameAfter(hiddenAt + CONFIRM_DELAY);
            overlay.showAfterCapture();
            long[] h2 = second == null ? null : imageHash(second);
            if (second != null) second.recycle();

            if (h1 == null) { first.recycle(); return failCapture(node); }   // blank / FLAG_SECURE
            if (h2 != null && differingBits(h1, h2) > SAME_IMAGE_BITS) {
                first.recycle();                                             // still animating: retry
                continue;
            }
            return saveShot(node, a, first, h1);
        }
        // Never went still (animation). Save a best-effort frame so the screen isn't lost.
        long hiddenAt = overlay.hideForCapture();
        Bitmap frame = capture.captureFrameAfter(hiddenAt);
        overlay.showAfterCapture();
        if (frame == null) return failCapture(node);
        long[] h = imageHash(frame);
        if (h == null) { frame.recycle(); return failCapture(node); }
        return saveShot(node, a, frame, h);
    }

    private boolean saveShot(Node node, ScreenAnalyzer.Result a, Bitmap bitmap, long[] image) {
        if (lastCapturedImage != null && differingBits(image, lastCapturedImage) <= SAME_IMAGE_BITS) {
            Crawler.duplicatesSkipped++;
            bitmap.recycle();
            Crawler.notifyChanged();
            return true;                              // identical to the last shot: count as done
        }
        lastCapturedImage = image;
        store.save(bitmap, node.folder, firstNonEmpty(a.title, a.activity, FOLDER_DEFAULT),
                TextUtils.join("\n", a.text));
        Crawler.shotCount++;
        Crawler.folderCount = store.folderCount();
        Crawler.status(getString(R.string.status_saved, node.folder));
        return true;
    }

    private boolean failCapture(Node node) {
        return ++node.captureFails >= MAX_CAPTURE_FAILS;   // stop retrying a window we can't capture
    }

    // ---- Helpers ------------------------------------------------------------

    private ScreenAnalyzer.Result analyze(AccessibilityNodeInfo root) {
        return ScreenAnalyzer.analyze(
                root, getResources().getDisplayMetrics().heightPixels, currentActivity);
    }

    private static String pkgOf(AccessibilityNodeInfo root) {
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private static List<String> append(List<String> path, String step) {
        List<String> copy = new ArrayList<>(path);
        copy.add(step);
        return copy;
    }

    private static String nest(String parent, String name) {
        if (parent == null) return name;
        return parent + ScreenshotStore.SEPARATOR + name.replace(ScreenshotStore.SEPARATOR, " ");
    }

    private static String firstNonEmpty(String... options) {
        for (String s : options) if (s != null && !s.isEmpty()) return s;
        return "";
    }

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

    private static int differingBits(long[] a, long[] b) {
        int bits = 0;
        for (int i = 0; i < a.length; i++) bits += Long.bitCount(a[i] ^ b[i]);
        return bits;
    }
}
