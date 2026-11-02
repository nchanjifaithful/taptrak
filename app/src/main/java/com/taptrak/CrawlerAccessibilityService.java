package com.taptrak;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The crawl engine. Runs as an Android AccessibilityService so it can read the
 * on-screen layout of the app under test and drive it: it taps interactive
 * elements in a depth-first order, fills text fields with dummy text to get past
 * forms, asks {@link ScreenCaptureService} to screenshot each newly seen screen,
 * and presses Back to retreat when a screen has no untried elements left.
 *
 * <p>Screens are identified by a structural hash of their layout (the "visited"
 * set), so the crawler does not loop and does not re-screenshot a screen it has
 * already recorded.
 */
public class CrawlerAccessibilityService extends AccessibilityService {

    public static volatile CrawlerAccessibilityService instance;

    private HandlerThread engineThread;
    private Handler engine;
    private final Runnable stepRunnable = new Runnable() {
        @Override public void run() { doStep(); }
    };

    // Downscaled luminance of the last saved screenshot, for the differential guard.
    private int[] lastThumb;

    private String launcherPkg = "";
    private static final String SYSTEM_UI = "com.android.systemui";

    // How many times we've scrolled a given screen, to bound scroll exploration.
    private final Map<String, Integer> scrollCounts = new HashMap<String, Integer>();

    private static final long FIRST_STEP_DELAY = 1500;
    private static final long AFTER_ACTION_DELAY = 750;
    private static final long AFTER_BACK_DELAY = 550;
    private static final int MAX_SCROLLS_PER_SCREEN = 8;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null) launcherPkg = ri.activityInfo.packageName;
        Crawler.status("Accessibility service connected.");
    }

    /** Called from the control UI to begin a run. */
    public static boolean startCrawl() {
        CrawlerAccessibilityService s = instance;
        if (s == null) return false;
        s.beginCrawl();
        return true;
    }

    /** Called from the control UI to stop a run. */
    public static void stopCrawl() {
        CrawlerAccessibilityService s = instance;
        if (s != null) s.endCrawl("Stopped.");
    }

    private void beginCrawl() {
        Crawler.reset();
        lastThumb = null;
        scrollCounts.clear();
        Crawler.running = true;

        if (engineThread == null) {
            engineThread = new HandlerThread("engine");
            engineThread.start();
            engine = new Handler(engineThread.getLooper());
        }

        // Launch the app under test.
        Intent launch = getPackageManager().getLaunchIntentForPackage(Crawler.targetPackage);
        if (launch == null) {
            endCrawl("Could not launch " + Crawler.targetPackage + " — is it installed?");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);

        Crawler.status("Crawling " + Crawler.targetPackage + "…");
        engine.postDelayed(stepRunnable, FIRST_STEP_DELAY);
    }

    private void endCrawl(String why) {
        Crawler.running = false;
        if (engine != null) engine.removeCallbacks(stepRunnable);
        Crawler.status(why + " Captured " + Crawler.shotCount
                + " screens in " + Crawler.stepCount + " steps.");
    }

    /** One interaction step of the depth-first crawl. Never throws out of the loop. */
    private void doStep() {
        if (!Crawler.running) return;
        if (Crawler.shotCount >= Crawler.maxShots) { endCrawl("Reached screenshot limit."); return; }
        if (Crawler.stepCount >= Crawler.maxSteps) { endCrawl("Reached step limit."); return; }
        Crawler.stepCount++;

        long nextDelay = AFTER_ACTION_DELAY;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { reschedule(AFTER_BACK_DELAY); return; }

            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();

            // Decide whether this window is in scope for interaction.
            boolean inScope = pkg.equals(Crawler.targetPackage)
                    || pkg.equals(SYSTEM_UI)
                    || pkg.contains("permission")
                    || pkg.contains("packageinstaller");

            if (!inScope) {
                if (pkg.equals(launcherPkg) || pkg.isEmpty()) {
                    endCrawl("Returned to home — exploration complete.");
                    return;
                }
                // Wandered into another app; step back toward the target.
                performGlobalAction(GLOBAL_ACTION_BACK);
                reschedule(AFTER_BACK_DELAY);
                return;
            }

            String stateHash = hashLayout(root);

            // Screenshot the first time we see this screen.
            if (!Crawler.visitedStates.contains(stateHash)) {
                Crawler.visitedStates.add(stateHash);
                captureScreen(pkg);
            }

            Set<String> tried = Crawler.triedElements.get(stateHash);
            if (tried == null) {
                tried = Collections.synchronizedSet(new java.util.HashSet<String>());
                Crawler.triedElements.put(stateHash, tried);
            }

            List<Candidate> candidates = new ArrayList<Candidate>();
            collect(root, candidates);
            sortByPriority(candidates);

            Candidate chosen = null;
            for (Candidate c : candidates) {
                if (!tried.contains(c.signature)) { chosen = c; break; }
            }

            if (chosen != null) {
                tried.add(chosen.signature);
                act(chosen);
                nextDelay = AFTER_ACTION_DELAY;
            } else if (tryScroll(root, stateHash)) {
                // Revealed more content by scrolling; keep going on this screen.
                nextDelay = AFTER_ACTION_DELAY;
            } else {
                // Dead end: retreat one screen (depth-first back-out).
                performGlobalAction(GLOBAL_ACTION_BACK);
                nextDelay = AFTER_BACK_DELAY;
            }
        } catch (Throwable t) {
            Crawler.status("Step error: " + t.getClass().getSimpleName());
        }
        reschedule(nextDelay);
    }

    private void reschedule(long delay) {
        if (Crawler.running && engine != null) engine.postDelayed(stepRunnable, delay);
    }

    // ---- Element collection & interaction -----------------------------------

    private static final class Candidate {
        AccessibilityNodeInfo node;
        String signature;
        int priority;
        int top;
        boolean editable;
    }

    private void collect(AccessibilityNodeInfo node, List<Candidate> out) {
        if (node == null) return;
        if (node.isVisibleToUser()) {
            boolean editable = node.isEditable();
            boolean actionable = node.isClickable() || node.isLongClickable()
                    || node.isCheckable() || editable;
            if (actionable) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    Candidate c = new Candidate();
                    c.node = node;
                    c.editable = editable;
                    c.signature = signatureOf(node, r);
                    c.priority = priorityOf(node);
                    c.top = r.top;
                    out.add(c);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out);
        }
    }

    /**
     * Heuristic edge weighting: prefer elements that tend to open new screens
     * (buttons, list items, tabs) over ones that only toggle state on the current
     * screen (checkboxes, switches). Text fields sit in between so forms get filled.
     */
    private int priorityOf(AccessibilityNodeInfo node) {
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        if (node.isCheckable() || cls.contains("CheckBox") || cls.contains("Switch")
                || cls.contains("Toggle") || cls.contains("RadioButton")) {
            return 0;
        }
        if (node.isEditable()) return 2;
        boolean hasText = !TextUtils.isEmpty(node.getText());
        if (cls.contains("Button") || cls.contains("TabView") || cls.contains("MenuItem")) return 4;
        if (node.isClickable() && hasText) return 3;
        if (node.isClickable()) return 2;
        return 1;
    }

    private void sortByPriority(List<Candidate> list) {
        Collections.sort(list, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                if (a.priority != b.priority) return b.priority - a.priority; // high first
                return a.top - b.top;                                        // then top-to-bottom
            }
        });
    }

    private void act(Candidate c) {
        if (c.editable) {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, Crawler.dummyText);
            if (!c.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                tapCenter(c.node);
            }
            return;
        }
        AccessibilityNodeInfo clickable = c.node;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        tapCenter(c.node);
    }

    private void tapCenter(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build();
        dispatchGesture(g, null, null);
    }

    private boolean tryScroll(AccessibilityNodeInfo root, String stateHash) {
        int count = scrollCounts.containsKey(stateHash) ? scrollCounts.get(stateHash) : 0;
        if (count >= MAX_SCROLLS_PER_SCREEN) return false;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable == null) return false;
        boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (ok) scrollCounts.put(stateHash, count + 1);
        return ok;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    // ---- Screen identity & capture ------------------------------------------

    /** Structural hash of the layout: class, id, clickability and bounds, but not
     *  volatile text — so a clock or changing label doesn't look like a new screen. */
    private String hashLayout(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        appendStructure(root, sb);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private void appendStructure(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        sb.append(node.getClassName()).append(':')
          .append(node.getViewIdResourceName()).append(':')
          .append(node.isClickable() ? 1 : 0).append(':')
          .append(r.left).append(',').append(r.top).append(',')
          .append(r.right).append(',').append(r.bottom).append(';');
        for (int i = 0; i < node.getChildCount(); i++) {
            appendStructure(node.getChild(i), sb);
        }
    }

    private void captureScreen(String pkg) {
        ScreenCaptureService cap = ScreenCaptureService.instance;
        if (cap == null) { Crawler.status("Capture service not ready."); return; }
        Bitmap bmp = cap.captureBitmap();
        if (bmp == null) { Crawler.status("No frame to capture."); return; }

        int[] thumb = thumbnail(bmp);
        if (lastThumb != null && diffRatio(thumb, lastThumb) < 0.05) {
            bmp.recycle();
            return; // near-identical to the previous screenshot: discard
        }
        lastThumb = thumb;

        File dir = Crawler.outputDir;
        if (dir == null) { bmp.recycle(); return; }
        dir.mkdirs();
        String safePkg = pkg.replaceAll("[^A-Za-z0-9._]", "_");
        String name = String.format("%04d_%s.png", Crawler.shotCount + 1, safePkg);
        File out = new File(dir, name);
        try {
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            Crawler.shotCount++;
            appendLog(name, pkg);
            Crawler.status("Captured " + Crawler.shotCount + ": " + name);
        } catch (Exception e) {
            Crawler.status("Save failed: " + e.getMessage());
        } finally {
            bmp.recycle();
        }
    }

    private void appendLog(String file, String pkg) {
        try {
            File log = new File(Crawler.outputDir, "crawl_log.csv");
            boolean fresh = !log.exists();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(log, true), "UTF-8");
            if (fresh) w.write("index,file,package,step\n");
            w.write(Crawler.shotCount + "," + file + "," + pkg + "," + Crawler.stepCount + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    private int[] thumbnail(Bitmap bmp) {
        Bitmap small = Bitmap.createScaledBitmap(bmp, 64, 64, true);
        int[] px = new int[64 * 64];
        small.getPixels(px, 0, 64, 0, 0, 64, 64);
        small.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            px[i] = (r * 30 + g * 59 + b * 11) / 100; // luminance 0..255
        }
        return px;
    }

    private double diffRatio(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return (double) sum / (a.length * 255.0);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* engine-driven */ }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        endCrawl("Service destroyed.");
        if (engineThread != null) engineThread.quitSafely();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private String signatureOf(AccessibilityNodeInfo node, Rect r) {
        return node.getClassName() + "|" + node.getViewIdResourceName() + "|"
                + node.getText() + "|" + r.left + "," + r.top + "," + r.right + "," + r.bottom;
    }
}
