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
 * <p>The identity is a <em>set</em> of structural tokens with no bounds, counts or
 * list content, so scrolling, new list rows and changing values do not make a
 * screen look new.
 */
final class ScreenAnalyzer {

    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 1500;
    private static final int MAX_TEXT_TOKEN = 30;
    private static final int MAX_LIST_TEXT_TOKENS = 12;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NAV_CONTAINER = Pattern.compile(
            "bottomnav|navigationbar|navigationrail|tablayout|tabrow|tabbar|navigationview"
                    + "|drawer|nav_|_nav|bottom_bar");
    /** Actions the crawler must never trigger by itself. */
    private static final Pattern DANGEROUS = Pattern.compile(
            "\\b(log ?out|sign ?out|delete|remove|uninstall|deactivate|close account|purchase|buy"
                    + "|subscribe|upgrade|pay|checkout|check out|place order|send|call|report"
                    + "|block|reset)\\b");
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
        /** Rows of one list share a kind; the engine gives up on kinds that lead nowhere new. */
        String kind;
        String label;
        int priority;
        int top;
        boolean editable;
        boolean nav;
    }

    static final class Result {
        String hash;
        final Set<String> tokens = new TreeSet<>();
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
    private int idTokens;
    private int titleTop = Integer.MAX_VALUE;
    private boolean hasPassword, hasCredentialField, hasCodeField, hasAuthAction, hasProvider;
    private boolean hasProgress;

    private ScreenAnalyzer(int screenHeight) { this.screenHeight = screenHeight; }

    static Result analyze(AccessibilityNodeInfo root, int screenHeight) {
        ScreenAnalyzer a = new ScreenAnalyzer(screenHeight);
        a.walk(root, 0, false, false);
        return a.finish();
    }

    private Result finish() {
        // Compose and web screens expose few ids, so structure alone can't tell them apart.
        if (idTokens < 3) result.tokens.addAll(listTexts);
        result.hash = Integer.toHexString(TextUtils.join("|", result.tokens).hashCode())
                + "_" + result.tokens.size();
        result.auth = hasPassword || hasCodeField || hasProvider
                || (hasCredentialField && hasAuthAction);
        result.loading = hasProgress && result.tokens.size() < 8;
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
        boolean nav = inNav || NAV_CONTAINER.matcher((cls + " " + id).toLowerCase(Locale.US)).find();
        boolean scrollable = node.isScrollable();

        if (id.isEmpty()) {
            result.tokens.add(cls);
        } else if (result.tokens.add(cls + "#" + id)) {
            idTokens++;
        }
        if (cls.contains("ProgressBar")) hasProgress = true;
        if (scrollable && result.scrollable == null) result.scrollable = node;

        String text = editable ? "" : trimmed(node.getText());
        String desc = trimmed(node.getContentDescription());
        addTextTokens(node, text, inList, nav);
        checkAuth(node, id, text, desc, editable);
        if (node.isClickable() || editable) addCandidate(node, cls, id, text, desc, editable, inList, nav);
        considerTitle(node, text, inList);

        for (int i = 0, n = node.getChildCount(); i < n; i++) {
            walk(node.getChild(i), depth + 1, inList || scrollable, nav);
        }
    }

    private void addTextTokens(AccessibilityNodeInfo node, String text, boolean inList, boolean nav) {
        if (text.isEmpty() || text.length() > MAX_TEXT_TOKEN) return;
        String normal = DIGITS.matcher(text).replaceAll("#");
        if (!inList) {
            result.tokens.add("T:" + normal);
        } else if (listTexts.size() < MAX_LIST_TEXT_TOKENS) {
            listTexts.add("T:" + normal);
        }
        if (nav && node.isSelected()) result.tokens.add("S:" + normal);
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
                              String desc, boolean editable, boolean inList, boolean inNav) {
        // Toggles only change state on the screen, which is the same kind of change every time.
        if (node.isCheckable() || node.isPassword()) return;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return;

        String label = !text.isEmpty() ? text : !desc.isEmpty() ? desc : firstText(node, 3);
        String lower = label.toLowerCase(Locale.US);
        if (DANGEROUS.matcher(lower).find() || DANGEROUS.matcher(id.replace('_', ' ')).find()) return;
        if (RETREAT.matcher(lower).matches()) return;

        boolean nav = inNav || (!inList && r.top > screenHeight * 0.86f && r.height() < screenHeight * 0.16f);

        Candidate c = new Candidate();
        c.node = node;
        c.label = label;
        c.editable = editable;
        c.nav = nav;
        c.top = r.top;
        c.signature = cls + "|" + id + "|" + DIGITS.matcher(label).replaceAll("#")
                + (label.isEmpty() ? "|" + r.left + "," + r.top : "");
        c.kind = inList && !editable && !nav ? "row|" + cls + "|" + id : c.signature;
        c.priority = nav ? 5 : editable ? 4 : inList ? 2 : label.isEmpty() ? 1 : 3;
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
