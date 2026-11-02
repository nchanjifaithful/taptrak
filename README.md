# TapTrak

A lightweight Android **automated UI-exploration tool**. You point it at one app;
it launches that app and walks through it on its own — tapping interactive
elements in a depth-first order, screenshotting each new screen, filling text
fields with dummy text to get past forms, and pressing Back to retreat when a
screen has nothing new left to try. It's the same idea as Google's *TapTrak*,
*DroidBot* and *Fastbot*, meant for **QA, regression and accessibility testing of
apps you own or are authorised to test**.

- No third-party libraries — only the Android framework, so the APK is tiny.
- Screenshots and a `crawl_log.csv` are written to the app's own folder:
  `Android/data/com.taptrak/files/crawls/<package>_<timestamp>/`.

## How it works

| Piece | Role |
|---|---|
| `CrawlerAccessibilityService` | The engine. Reads the layout via `AccessibilityNodeInfo`, hashes each screen's **structure** to tell new screens from already-seen ones (the "visited" set), ranks elements (buttons/tabs/list items above checkboxes/switches), taps them, fills fields, scrolls, and backs out of dead ends. |
| `ScreenCaptureService` | Holds the `MediaProjection` session and returns a `Bitmap` of the current screen on request. Runs as a foreground service with an ongoing notification. |
| `MainActivity` | Control screen: enable the service, grant capture, set the target package and screenshot cap, start/stop. |

Design choices that match your request: DFS with Back-based retreat, structural
**layout-hash deduplication**, a **differential-capture** guard that discards a
shot less than ~5% different from the previous one, heuristic element
prioritisation, and automatic form-filling.

## Deliberate limitation — no `FLAG_SECURE` bypass

Windows that set `FLAG_SECURE` (banking apps, DRM video, some login screens) opt
out of screen capture at the OS level; `MediaProjection` captures them as black.
This tool **does not** defeat that protection (no Xposed/LSPosed hook, no root
requirement). That protection exists specifically to stop capture of sensitive
screens, so it's left intact.

## Permissions it requests, and why

- **Accessibility service** — to read the layout and perform taps/gestures. You
  enable it manually in Settings > Accessibility; Android cannot grant it silently.
- **Screen capture** (`MediaProjection`) — the one-time "Allow screen recording"
  dialog. The ongoing notification is your signal that capture is on.
- **Foreground service / notifications** — required by Android to run the capture
  service and show that notification.

It uses scoped storage only (no broad file permission) and does nothing in the
background once you stop the crawl.

## Building it

You need an Android SDK somewhere; this project has no other dependencies.
Three ways, easiest first:

### 1. Cloud build + Release (nothing to install)
Push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds the APK with Google's official tools on
GitHub's servers and **publishes it to the repo's Releases** automatically.
Open the repo's **Releases** page and download **TapTrak.apk** from the latest
release. (The same file is also under **Actions → the run → Artifacts**.)

### 2. Android Studio / Gradle
Open the folder in Android Studio and Run, or:
```
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

### 3. Standalone tools (no IDE, no Gradle)
Install the official *command-line tools* + a *build-tools* package, point the
variables in `build-standalone.sh` at them, and run it. Produces `TapTrak.apk`.

## Using it

1. Install the APK and open it.
2. **1. Enable accessibility service** → turn on *TapTrak engine*.
3. **2. Grant screen capture** → accept the dialog.
4. Enter the **target package** (e.g. `com.example.myapp`) and a screenshot cap.
5. **3. Start crawl.** The target app opens and the crawler drives it. Collect
   the screenshots from the folder above when it stops (it stops at the cap, on
   returning to the home screen, or when you tap **Stop**).

> Use this only on apps you own or have permission to test. Driving or capturing
> someone else's app or account without authorisation may be unlawful.

## Signing
Debug-signed by default — fine for your own devices, not for the Play Store.
Swap in your own release key (see the `signingConfig` in `app/build.gradle.kts`,
or the `KEYSTORE` variables in `build-standalone.sh`) before distributing.
