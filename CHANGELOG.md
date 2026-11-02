# Changelog

Each release's notes are built from the newest section below, followed by the
commits in that build. Add a section here when a change is worth describing.

## 2.2

- **Updates now install over the existing app.** Every build used to be signed with a
  new temporary key, which caused the "package conflicts" error. Builds now share one
  permanent key.
- **One last uninstall needed.** Remove the old version once before installing this
  one. Later updates install directly.

## 2.1

Scans the whole app, for app inventories.

- **Tabs are now opened.** Each tab is its own journey with its own folder: its
  content is explored to the end before moving to the next tab. Bottom-bar tabs were
  being skipped as if they were toggles.
- **Every new screen is captured.** Screens are told apart by title, selected tab,
  activity and layout, so similar-looking screens no longer merge. Small changes on a
  screen, such as scrolling or new list rows, are still ignored.
- **No per-feature cap.** The old limit of 15 screenshots per feature is gone.
- **The crawl no longer ends early.** When Back leaves the app, it reopens the app
  and goes back to whatever is unexplored.
- **Lists are walked further.** A list is only skipped once its rows clearly lead to
  the same kind of page, as in a news feed.
- **Fewer buttons avoided.** Screens like "Payments" or "Send money" are now opened.
  It still won't tap exact labels that commit an action, such as Pay, Buy, Send or
  Confirm, or anything that logs out or deletes.
- **Maximum screenshots is now a number you type.** The default is 1000.
- **Every release now includes a log of what changed.**

## 2.0

- Screenshots are grouped by feature in `Pictures/TapTrak/<App>/<Feature>/`.
- Added a list of installed apps to choose from.
- Added a floating panel to pause, restart and stop.
- The crawl waits for you on sign-in screens and permission requests.
- The setup list ticks each permission as it's accepted.
- Redesigned the interface, with light and dark themes.

## 1.0

- First release: explores an app and saves screenshots.
