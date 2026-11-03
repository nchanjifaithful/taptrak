# TapTrak

A lightweight Android tool that walks through an app by itself and **screenshots
every new page and any new UI an interaction reveals**. It's meant for QA, regression
and accessibility testing of **apps you own or are authorised to test**.

- No third-party libraries, only the Android framework, so the APK stays tiny.
- Pick the app from a list of what's on the phone.
- Control the run from a floating panel, and stay in charge of sign-ins and permissions.

## Where screenshots go

Everything is in the phone's gallery and file manager:

```
Pictures/TapTrak/<App name>/<Folder>/<Subfolder>/001_<Screen title>.jpg
```

Folders follow the path taken through the app: `Home` for the first screen, one
top-level folder per bottom tab, and a subfolder for each screen opened from there.

Text files use the same folder names and go in the app's own storage, at
`Android/data/com.taptrak/files/TapTrak/<App name>/`. Uninstalling TapTrak deletes them.

## How it scans an app

| Behaviour | How it works |
|---|---|
| **Order** | It builds a map of screens. On each screen it tries untried elements in priority order and scrolls up to twice. Then it heads for the nearest unfinished screen, first by pressing Back, then by reopening the app and replaying the taps that reached it. |
| **Screenshots** | One per distinct screen layout, taken once the screen has stopped moving and confirmed by two still frames. |
| **Skipped** | The same layout showing different data, such as other items in a list. Also skipped: further list rows once one leads to a known screen, elements that lead to fully explored screens, and blank or protected windows. |
| **Screen text** | Each screenshot gets a matching `.txt` of the text on that screen. |
| **Speed** | It waits for each screen to settle before capturing or tapping, and saves in the background. |
| **Screens with little to tap** | Games and web content are also tapped on a grid of points. |
| **Whole app** | The run ends when no reachable screen is unfinished, the screenshot limit is reached, or the app has been reopened 80 times. |
| **Never tapped** | Anything that logs out or deletes, or a button labelled exactly Pay, Buy, Send, Confirm, Place order, Subscribe, Checkout, Call, Block or Reset. |

Because it presses switches and tries every element, run it on a test account: it can
change the app's settings and data.

## Release notes

Every release lists what changed, from the newest section of
[CHANGELOG.md](CHANGELOG.md) and the commits in that build.

## Sign-in and permission requests

- **Sign-in screens** (password or one-time-code fields, or "continue with" buttons):
  the screen is captured once, while empty, and the crawl pauses. Sign in and it
  continues by itself, or tap ⏭ to skip that screen.
- **Permission requests from the app:** the crawl waits for your answer, then shows
  whether it was granted. ⏭ dismisses the request.
- **Passwords** are never filled in. Text files record whatever a screen shows, which
  can include what's displayed in a sign-in field.

These are detected from what's on the screen, so an unusual sign-in screen may not
be recognised. Use Pause if the crawler carries on past one.

## The floating panel

It's hidden for the instant of each screenshot, so it never appears in one. Drag to
move it, and tap it to collapse or expand.

| Button | What it does |
|---|---|
| ⏸ / ▶ | Pause or resume. While paused you can use the app freely. |
| ⏭ | Appears while waiting on you: skip the sign-in screen or dismiss the permission request. |
| ↻ | Restart. Deletes this run's screenshots (earlier runs are kept, and text files aren't deleted) and starts over. |
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
3. Choose an app and type the maximum number of screenshots.
4. Tap **Start capture**. TapTrak returns with a summary when the run ends.

> Use this only on apps you own or have permission to test. Driving or capturing
> someone else's app or account without authorisation may be unlawful.

## Signing
Cloud builds are signed with one permanent key, held in the repo's
`TAPTRAK_KEYSTORE_BASE64` and `TAPTRAK_KEYSTORE_PASSWORD` secrets, so each release
installs as an update over the last. If that key is lost or replaced, the app has to
be uninstalled before the next install. Builds fail rather than use another key if
the secrets are missing. Local builds use the debug key.
