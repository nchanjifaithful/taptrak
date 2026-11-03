# Changelog

Each release's notes are built from the newest section below, followed by the
commits in that build. Add a section here when a change is worth describing.

## 3.1

- **Pages that share a layout are no longer merged.** A page's title is part of its
  identity again, so pages like "Notifications" and "Privacy" built from one template
  each get a screenshot.
- **Long lists still stay short.** Once a list's rows have opened five pages sharing
  one layout, the remaining rows count as that same page.
- **The settle check now hears content changes.** The service receives content-change
  events, so it can tell when an app has stopped updating before capturing.

## 3.0

Rebuilt as a navigation-graph crawler. No more transition-UI screenshots.

- **Screens are a graph of nodes.** A node is identified by its structure — activity,
  view ids, role skeleton and selected tabs — not its text, so the same layout with
  different data is one node and every list row leads back to a single detail node.
- **Actions are edges.** The engine always picks the untried element most likely to
  reach a node it hasn't seen, and skips any element whose edge already leads to a
  fully-explored screen, so it stops grinding on lists and dead ends.
- **Settle gate kills transition frames.** After each action it waits for accessibility
  events to fall silent, the view tree to be stable across polls, and no spinner to be
  present, then confirms with two still frames before saving. Mid-animation and
  half-loaded frames are rejected and retried.
- **Hybrid navigation.** To reach the next unexplored screen it tries Back first, and
  when Back fails or it ends up somewhere unexpected it relaunches and replays the
  recorded tap-path to the target.
- One clean screenshot per screen, each with its matching on-screen-text `.txt`.
  Sign-in and permission screens still pause for you and resume afterwards.

## 2.7

Capture everything, on every change.

- **A screenshot is taken on any change at all** — every state or UI change, after
  every interaction. The only frame skipped is one effectively identical to the one
  just saved (nothing changed) or a blank/secure window.
- **The screen's text is saved too.** Every screenshot gets a matching `.txt` of all
  text on that screen, written to the app's own folder at
  `Android/data/com.taptrak/files/TapTrak/<App>/<Folder>/` (same names as the images).
- **No folder-depth limit** — folders mirror the full path taken into the app.
- **It keeps going until the app is exhausted,** rather than stopping after a quiet
  spell. Sign-in and permission screens still pause for you and resume afterwards.

## 2.6

Explore the whole app, not just the first few screens.

- **Every element is tapped again** — every button, text field, list row and tab.
  The one-row-per-list shortcut from 2.5 is gone, because an app whose menus are
  lists was only being explored one branch deep, which is why runs ended after a
  handful of screenshots.
- **The crawl no longer gives up early.** The limits that could declare a run
  "complete" after a short dry spell or when it briefly lost its way are raised a
  long way, so it keeps going until there is genuinely nothing unexplored left.
- **Still one screenshot per distinct screen, no duplicates** — capture fires on
  every newly-seen layout and the image-hash dedup drops anything that already
  looks the same.
- Sign-in and permission screens still pause the run for you; it resumes on its
  own once you're through, or use the overlay to skip.

## 2.5

Broader coverage, far fewer wasted taps.

- **Lists: one row stands in for the whole list.** The crawl taps a single
  representative item, records the detail screen, and moves on instead of
  drilling into every row.
- **Scrolling is capped at two full scrolls per screen,** then the crawl leaves
  the screen — enough to reveal more content without grinding on long lists.
- **Every newly-seen screen is captured.** Capture is now gated on whether the
  screen's layout is one the crawl hasn't recorded, with the perceptual
  image-hash dedup as the final guard against duplicates. This surfaces the
  major sections and dialogs the previous structural gate could skip.

## 2.4

Captures what an interaction reveals, not the interaction itself.

- **Form input and selections no longer create screenshots** on their own.
- **If a selection or entry makes new UI appear, it's captured,** such as an extra
  field, a dropdown, an expanded section or search results.
- **New pages, dialogs and tabs are always captured,** unless the image matches one
  already saved.
- **Scrolling is captured** when it shows a good amount of new content.
- Every element is still tried, so the walk reaches just as much of the app.

## 2.3

Captures every interaction, much faster.

- **A screenshot after every interaction:** each tap, text entry, toggle and scroll.
  A screenshot is only dropped when the image matches one already saved.
- **Full depth-first walk.** Every element on every screen is tried, including all
  list rows and switches. Screens no longer merge unless they match exactly.
- **Screens with little to tap** (games, web content) also get tapped on a grid.
- **Much shorter waits.** It moves on as soon as the screen stops changing, and saves
  in the background.
- **Features are gone.** Folders simply follow the path taken through the app.
- **Screenshots are now JPEG**, which saves far faster than PNG.
- **Default maximum is now 10000.**

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
