package com.taptrak;

import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads one window's accessibility tree in a single pass and reports what the
 * engine needs: an identity for the screen, the elements worth tapping, and
 * whether the screen is asking the user to sign in.
 *
 * <p>A screen's identity is its structure (view types and ids), its visible labels
 * and which tabs are selected. Positions and numbers are left out, so a counter
 * ticking over doesn't make a screen look new, while different text or a tab does.
 */
final class ScreenAnalyzer {

    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 2500;
    private static final int MAX_TEXT_TOKEN = 30;
    /** Full on-screen text dumped alongside each screenshot. */
    private static final int MAX_SCREEN_TEXT_LINES = 600;
    private static final int MAX_SCREEN_TEXT_LEN = 300;
    private static final int MAX_LIST_TEXT_TOKENS = 12;
    private static final int MAX_TAB_GROUP = 12;
    /** Tab bars in the lower part of the screen stay put across the app's sections. */
    private static final float BOTTOM_BAR_FROM = 0.6f;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NAV_CONTAINER = Pattern.compile(
            "bottomnav|bottom_nav|bottombar|bottom_bar|navigationbar|navigationrail|tablayout"
                    + "|tab_layout|tabrow|tabbar|tab_bar|navigationview|nav_view|navview");
    private static final Pattern TOGGLE = Pattern.compile("Switch|CheckBox|RadioButton|ToggleButton");
    /** Never tapped, because they end the session or destroy data. */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\b(log ?out|sign ?out|delete|uninstall|deactivate|close account|remove account)\\b");
    /** Only exact labels: "Pay" commits an action, while "Payments" just opens a screen. */
    private static final Pattern COMMITS = Pattern.compile(
            "^(pay( now)?|buy( now)?|send|confirm( payment)?|place order|complete purchase"
                    + "|subscribe( now)?|checkout|check out|call|call now|block|reset)$");
    /** Pressing Back does the same job, without spending a tap. */
    private static final Pattern RETREAT = Pattern.compile("^(back|navigate up|close|cancel|dismiss)$");
    private static final Pattern CREDENTIAL_FIELD = Pattern.compile(
            "e-?mail|user ?name|phone|mobile number|login|account");
    private static final Pattern CODE_FIELD = Pattern.compile(
            "\\botp\\b|one.time|verification code|verify code|passcode|\\bpin\\b|captcha|2fa");
    private static final Pattern AUTH_ACTION = Pattern.compile(
            "sign ?in|log ?in|login|sign ?up|register|create account|verify");
    private static final Pattern AUTH_PROVIDER = Pattern.compile(
            "(continue|sign in|sign up|log in) with");

    static final class Candidate {
        AccessibilityNodeInfo node;
        String signature;
        String label;
        int priority;
        int top;
        boolean editable;
        boolean nav;
        boolean bottomBar;
        boolean selected;
        boolean listItem;
    }

    static final class Result {
        String hash;
        /** Screen identity for the graph: structure and tabs only, no text or values. */
        String structuralKey;
        String activity;
        final Set<String> structure = new TreeSet<>();
        final Set<String> labels = new TreeSet<>();
        final Set<String> selected = new TreeSet<>();
        /** Every visible piece of text and content-description, in reading order. */
        final List<String> text = new ArrayList<>();
        String title = "";
        final List<Candidate> candidates = new ArrayList<>();
        AccessibilityNodeInfo scrollable;
        boolean auth;
        boolean loading;
    }

    private final Result result = new Result();
    private final List<String> listTexts = new ArrayList<>();
    private final int screenHeight;
    private int visited;
    private int titleTop = Integer.MAX_VALUE;
    private boolean hasPassword, hasCredentialField, hasCodeField, hasAuthAction, hasProvider;
    private boolean hasProgress;

    private ScreenAnalyzer(int screenHeight) { this.screenHeight = screenHeight; }

    static Result analyze(AccessibilityNodeInfo root, int screenHeight, String activity) {
        ScreenAnalyzer a = new ScreenAnalyzer(screenHeight);
        a.result.activity = activity;
        a.walk(root, 0, false, false);
        return a.finish();
    }

    private Result finish() {
        result.labels.addAll(listTexts);
        result.hash = Integer.toHexString((result.activity + "|"
                + TextUtils.join("|", result.structure) + "|"
                + TextUtils.join("|", result.labels) + "|"
                + TextUtils.join("|", result.selected)).hashCode());
        // Identity without labels: same layout with different data stays one node.
        result.structuralKey = Integer.toHexString((result.activity + "|S|"
                + TextUtils.join("|", result.structure) + "|T|"
                + TextUtils.join("|", result.selected)).hashCode());
        result.auth = hasPassword || hasCodeField || hasProvider
                || (hasCredentialField && hasAuthAction);
        result.loading = hasProgress && result.structure.size() < 8;
        Collections.sort(result.candidates, (a, b) -> a.priority != b.priority
                ? b.priority - a.priority
                : a.top - b.top);
        return result;
    }

    private void walk(AccessibilityNodeInfo node, int depth, boolean inList, boolean inNav) {
        if (node == null || depth > MAX_DEPTH || visited >= MAX_NODES) return;
        visited++;
        if (!node.isVisibleToUser()) return;

        String cls = simpleClass(node);
        String id = viewId(node);
        boolean editable = node.isEditable();
        boolean scrollable = node.isScrollable();
        boolean navContainer = NAV_CONTAINER.matcher((cls + " " + id).toLowerCase(Locale.US)).find();

        result.structure.add(id.isEmpty() ? cls : cls + "#" + id);
        if (cls.contains("ProgressBar")) hasProgress = true;
        if (scrollable && result.scrollable == null) result.scrollable = node;

        String text = editable ? "" : trimmed(node.getText());
        String desc = trimmed(node.getContentDescription());
        addScreenText(trimmed(node.getText()));
        addScreenText(desc);
        addScreenText(hint(node));
        addLabel(text, inList);
        checkAuth(node, id, text, desc, editable);
        if (inNav && (node.isSelected() || node.isChecked())) {
            String label = !text.isEmpty() ? text : !desc.isEmpty() ? desc : firstText(node, 3);
            if (!label.isEmpty()) result.selected.add(DIGITS.matcher(label).replaceAll("#"));
        }
        if (node.isClickable() || editable) addCandidate(node, cls, id, text, desc, editable, inList, inNav);
        considerTitle(node, text, inList);

        // Scrollable tab strips shouldn't count as list content.
        boolean childNav = inNav || navContainer || isTabGroup(node);
        boolean childList = (inList || scrollable) && !childNav;
        for (int i = 0, n = node.getChildCount(); i < n; i++) {
            walk(node.getChild(i), depth + 1, childList, childNav);
        }
    }

    /** Tappable siblings with one selected: tabs, whatever the app built them from. */
    private static boolean isTabGroup(AccessibilityNodeInfo parent) {
        int n = parent.getChildCount();
        if (n < 2 || n > MAX_TAB_GROUP) return false;
        int tappable = 0;
        boolean anySelected = false;
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null || !child.isClickable()) continue;
            if (TOGGLE.matcher(simpleClass(child)).find()) return false;
            tappable++;
            if (child.isSelected() || child.isChecked()) anySelected = true;
        }
        return anySelected && tappable >= 2;
    }

    /** Keeps the real words shown on screen, not folded like the identity tokens. */
    private void addScreenText(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.isEmpty() || s.length() > MAX_SCREEN_TEXT_LEN) return;
        if (result.text.size() >= MAX_SCREEN_TEXT_LINES) return;
        if (!result.text.isEmpty() && result.text.get(result.text.size() - 1).equals(s)) return;
        result.text.add(s);
    }

    private void addLabel(String text, boolean inList) {
        if (text.isEmpty() || text.length() > MAX_TEXT_TOKEN) return;
        String normal = DIGITS.matcher(text).replaceAll("#");
        if (!inList) {
            result.labels.add(normal);
        } else if (listTexts.size() < MAX_LIST_TEXT_TOKENS) {
            listTexts.add(normal);
        }
    }

    private void checkAuth(AccessibilityNodeInfo node, String id, String text, String desc, boolean editable) {
        if (node.isPassword()) hasPassword = true;
        if (editable) {
            String field = (id + " " + desc + " " + hint(node) + " " + trimmed(node.getText()))
                    .toLowerCase(Locale.US);
            if (CODE_FIELD.matcher(field).find()) hasCodeField = true;
            if (CREDENTIAL_FIELD.matcher(field).find()) hasCredentialField = true;
        }
        if (text.isEmpty() && desc.isEmpty()) return;
        String label = (text + " " + desc).toLowerCase(Locale.US);
        if (label.length() < 60) {
            if (AUTH_ACTION.matcher(label).find()) hasAuthAction = true;
            if (AUTH_PROVIDER.matcher(label).find()) hasProvider = true;
        }
    }

    private void addCandidate(AccessibilityNodeInfo node, String cls, String id, String text,
                              String desc, boolean editable, boolean inList, boolean nav) {
        if (node.isPassword()) return;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return;

        String label = !text.isEmpty() ? text : !desc.isEmpty() ? desc : firstText(node, 3);
        String lower = label.toLowerCase(Locale.US);
        if (DESTRUCTIVE.matcher(lower).find() || DESTRUCTIVE.matcher(id.replace('_', ' ')).find()) return;
        if (COMMITS.matcher(lower).matches() || RETREAT.matcher(lower).matches()) return;

        Candidate c = new Candidate();
        c.node = node;
        c.label = label;
        c.editable = editable;
        c.nav = nav;
        c.bottomBar = nav && r.top > screenHeight * BOTTOM_BAR_FROM;
        c.selected = nav && (node.isSelected() || node.isChecked());
        c.listItem = inList && !nav;
        c.top = r.top;
        // Every labelled row is its own element; unlabelled ones are told apart by position.
        c.signature = cls + "|" + id + "|" + label
                + (label.isEmpty() ? "|" + r.left + "," + r.top : "");
        // Tabs come last: a tab's content is walked to the end before moving to the next tab.
        boolean toggle = node.isCheckable() || TOGGLE.matcher(cls).find();
        c.priority = nav ? 0 : editable ? 5 : toggle ? 1 : inList ? 3 : label.isEmpty() ? 2 : 4;
        result.candidates.add(c);
    }

    /** The screen's title is the highest short, non-interactive text outside any list. */
    private void considerTitle(AccessibilityNodeInfo node, String text, boolean inList) {
        if (inList || text.isEmpty() || text.length() > 40 || node.isClickable()) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.top < titleTop) {
            titleTop = r.top;
            result.title = text;
        }
    }

    private static String firstText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth < 0) return "";
        for (int i = 0, n = node.getChildCount(); i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            String text = trimmed(child.getText());
            if (text.isEmpty()) text = trimmed(child.getContentDescription());
            if (text.isEmpty()) text = firstText(child, depth - 1);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String simpleClass(AccessibilityNodeInfo node) {
        CharSequence c = node.getClassName();
        if (c == null) return "";
        String s = c.toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /** Resource id without its package, and with digits folded so item_1 and item_2 match. */
    private static String viewId(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return "";
        int slash = id.indexOf('/');
        return DIGITS.matcher(slash >= 0 ? id.substring(slash + 1) : id).replaceAll("#");
    }

    private static String hint(AccessibilityNodeInfo node) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? trimmed(node.getHintText()) : "";
    }

    private static String trimmed(CharSequence s) { return s == null ? "" : s.toString().trim(); }
}
