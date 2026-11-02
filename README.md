# TapTrak

A lightweight Android tool that explores an app by itself and saves **one
screenshot per distinct screen, organised by feature**. It's meant for QA,
regression and accessibility testing of **apps you own or are authorised to test**.

- No third-party libraries, only the Android framework, so the APK stays tiny.
- Pick the app from a list of what's on the phone.
- Control the run from a floating panel, and stay in charge of sign-ins and permissions.

## Where screenshots go

Everything is in the phone's gallery and file manager:

```
Pictures/TapTrak/<App name>/<Feature>/01_<Screen title>.png
```

The app's home screen goes in `Home`, and sign-in screens go in `Sign-in`. Other
features are named after what opened them, such as the `Settings` tab.

## How it decides what to capture

| Behaviour | How it works |
|---|---|
| **Features** | A feature starts when the crawler taps something on the app's home screen, or a tab or navigation item anywhere. Screens reached from there belong to that feature. At most 15 screenshots are kept per feature. |
| **No repeated screens** | A screen's identity is the set of view types and fixed labels it contains, ignoring positions, list contents and counts. Scrolling, new list rows and changing values don't make a screen look new, and a 85% match counts as the same screen. |
| **No repeated images** | A 256-bit visual hash of each screenshot is compared with every other one in the run, and near-identical images are dropped. |
| **Lists** | After two rows of a list lead nowhere new, the rest of that list is skipped. A news feed costs a few taps, while a settings list with different destinations is still walked. |
| **Toggles and risky actions** | Switches and checkboxes aren't touched. Neither is anything labelled like log out, delete, buy, pay, subscribe, send, call, report, block or reset. |

## Sign-in and permission requests

- **Sign-in screens** (password or one-time-code fields, or "continue with" buttons):
  the screen is captured once, while empty, and the crawl pauses. Sign in and it
  continues by itself, or tap ⏭ to skip that screen.
- **Permission requests from the app:** the crawl waits for your answer, then shows
  whether it was granted. ⏭ dismisses the request.
- **Passwords** are never filled in or read.

These are detected from what's on the screen, so an unusual sign-in screen may not
be recognised. Use Pause if the crawler carries on past one.

## The floating panel

It's hidden for the instant of each screenshot, so it never appears in one. Drag to
move it, and tap it to collapse or expand.

| Button | What it does |
|---|---|
| ⏸ / ▶ | Pause or resume. While paused you can use the app freely. |
| ⏭ | Appears while waiting on you: skip the sign-in screen or dismiss the permission request. |
| ↻ | Restart. Deletes this run's screenshots (earlier runs are kept) and starts over. |
| ⏹ | Stop and return to TapTrak. |

## Deliberate limitation: no `FLAG_SECURE` bypass

Windows that set `FLAG_SECURE` (banking apps, DRM video, some login screens) opt out
of screen capture at the OS level and capture as black. TapTrak skips those frames
and does not defeat the protection (no Xposed/LSPosed hook, no root requirement).

## Permissions it requests, and why

The setup list ticks each item as it's accepted.

- **Accessibility service:** reads the layout, performs taps and shows the panel. You
  enable it manually in Settings > Accessibility. On Android 13+, if it's greyed out,
  open TapTrak's App info, tap ⋮ and choose **Allow restricted settings**.
- **Screen capture** (`MediaProjection`): choose **Entire screen** in the dialog.
- **Notifications** (optional, Android 13+): shows the capture notification.
- **Storage** (Android 9 and older only): saves to `Pictures/`.
- **All-apps visibility** (`QUERY_ALL_PACKAGES`): fills the app list.

## Building it

### 1. Cloud build + Release (nothing to install)
Every push to `main` runs `.github/workflows/build.yml` on GitHub's servers and
publishes **TapTrak.apk** to the repo's **Releases** page.

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
2. Accept the setup items.
3. Choose an app and set the screenshot limit.
4. Tap **Start capture**. TapTrak returns with a summary when the run ends.

> Use this only on apps you own or have permission to test. Driving or capturing
> someone else's app or account without authorisation may be unlawful.

## Signing
Debug-signed by default, which is fine for your own devices but not the Play Store.
Swap in your own release key (see the `signingConfig` in `app/build.gradle.kts`, or
the `KEYSTORE` variables in `build-standalone.sh`) before distributing.
