# ZotSpeed

A speed reader for Android that reads the EPUBs in your Zotero library. It's designed to
work on the small, nearly square cover screen (Flex Window) of a Samsung Galaxy Z Flip.

## Reading modes

Switch modes from the top bar at any time. All three modes share one reading position, so you
can, for example, speed-read a chapter and then flip to pages to re-read a paragraph.

| Mode | What it does |
| --- | --- |
| ⚡ **One word** (RSVP) | Shows one word at a time with its optimal recognition point in red, fixed under a guide mark so your eyes never move. Longer pauses after commas, sentence ends and paragraph ends, and a short warm-up when you press play. Tap to play/pause; swipe left/right to step through words; ⏪/⏩ jump by sentence. When paused, the current sentence is shown for context. Speed is 100–1000 wpm. |
| 📖 **Pages** | Standard paging. Tap the right or left edge, or swipe, to turn pages; tap the middle to show or hide the controls. The whole chapter is laid out once and split at line boundaries, so lines are never cut off. |
| ↕ **Scroll** | One chapter at a time as a continuous scroll, with previous and next chapter buttons. |

The volume keys turn pages in Pages mode, scroll in Scroll mode, and play/pause (down) or go
back a sentence (up) in One-word mode. You can turn this off in the reading settings.

## Setup

### Sign in with Zotero (OAuth)

1. Register an OAuth app (once) at <https://www.zotero.org/oauth/apps>. Use any name, and set the
   **callback URL** to `zotspeed://oauth`. Zotero shows you a **client key** and a **client secret**.
2. Give those credentials to the app in one of two ways:
   - **In the app:** tap **OAuth app settings** on the first screen, paste the key and secret, and tap **Save**.
   - **Built into the APK:** add `ZOTERO_CLIENT_KEY` and `ZOTERO_CLIENT_SECRET` as GitHub
     repository secrets (Settings → Secrets and variables → Actions), or put them in `local.properties`
     for local builds. The setup step is then skipped.
3. Tap **Sign in with Zotero**, log in, and approve read access. You'll be sent back to the app.

The app asks for read-only access to your library and your groups. Signing out revokes the key that was created at sign-in.
Anyone who has the APK can extract the client secret from it. That's normal for OAuth 1.0 on mobile, but keep
an APK with built-in credentials to yourself.

### Or use an API key

Create a key at <https://www.zotero.org/settings/keys/new> with **Allow library access**
(read-only is enough; give it read access to groups if you want group libraries). Paste it
under **or use an API key**.

### Choosing a book

The app lists every EPUB attachment in your library as a grid of covers. You can switch to a list with the
button at the top. Use the dropdown in the top-left corner to switch to a group library. Tap a book to download it
and start reading. Long-press a downloaded book to remove it from the device.

- **Collections and tabs:** the first time you open a library, the app asks which Zotero collection
  to open. Each collection you open becomes a tab. Tap **+** to open another collection next to it, tap a tab to switch to it,
  and tap ✕ on the active tab to close it. Closing the last tab asks you to choose a collection again. Tabs include books in
  subcollections, are remembered for each library, and collections without EPUBs are hidden from the picker.
  **⋮ → Download all shown** downloads every book in the current tab, so you can read it offline.
- **Sorting:** the sort button (☰) sorts books by **title**, **author**, or **year**. Tap the active key again,
  or tick **Reverse order**, to reverse the order: Z–A, or newest first. Books without an author or year always go
  last. Sorting follows your phone's language, so æ, ø and å come after z in Norwegian.
- **Covers:** downloaded books show the cover from inside the EPUB. Books that aren't downloaded yet
  use Open Library's cover for the item's ISBN. This sends that ISBN to openlibrary.org, and you can
  turn it off under **⋮ → Covers from Open Library**. Books with no cover get a generated one.

The app checks for changes incrementally, using Zotero's library versions. Use
**⋮ → Full re-sync** to rebuild the list from scratch.

**Limitations:** ZotSpeed can only download files that are stored in Zotero's own file storage.
EPUBs synced through WebDAV, or added as linked files, show up in the list but can't be downloaded
through the Zotero Web API. Images in the books are ignored.

## Using it on the Flip cover screen

Samsung lets only the apps on its own list run full-screen on the Flip's cover screen (FlexWindow). On the Flip 8
that is about 16 apps. Every other app needs Good Lock. ZotSpeed gives you two ways to read there:

### 1. The ZotSpeed reader widget (no extra apps needed)

ZotSpeed includes a widget for the cover screen, built as described in Samsung's
[Flex Window guide](https://developer.samsung.com/galaxy-z/flex_window.html). Widgets on the cover screen work for any app,
without Good Lock.

- Add it under **Settings → Cover screen → Widgets**. The exact menu names can differ between One UI versions.
- It shows the book you last opened in the app, one page at a time. Tap the right side of the page, or **›**,
  for the next page, and the left side, or **‹**, to go back. It continues into the next chapter automatically.
- The widget and the app share your reading position, so you can read on the cover screen and continue inside the phone.
  It uses the app's text size and theme.
- **⤢** opens the book in the full app on the cover screen. This only works if ZotSpeed is allowed on the
  cover screen (see option 2). Otherwise One UI asks you to open the phone.

One-word (RSVP) reading isn't available in the widget, because widgets can't redraw fast enough to show words at reading speed.
Use the full app for that.

### 2. The full app on the cover screen (via Good Lock)

1. Install **Good Lock** from the Galaxy Store and enable its **MultiStar** module.
2. In MultiStar, open the cover-screen section ("I ♡ Galaxy Foldable" → Launcher Widget, or "Cover screen apps"
   on newer versions) and add ZotSpeed.

The app is resizable, keeps running when it moves between screens, and its layouts are built for a screen of about
340 × 380 dp. In the reading settings, **Theme → Black** (the default) suits the cover screen, and **Show over lock screen**
lets you read there without unlocking the phone.

## Install

Each push builds a release APK on GitHub Actions (**Actions → Build APK → zotspeed-apk**).
Download it on the phone and allow installing from that source.

The APK is signed with the non-secret `debug.keystore` in this repository. Because every build uses the same key,
a new build installs over the old one, and you keep your reading positions. If you ever publish the
app, replace that key with a private one.

## Building locally

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # unit tests
```

The following render the screens at cover-screen size to PNG files (using Robolectric) and parse your own EPUBs:

```bash
SCREENSHOT_DIR=/tmp/shots SAMPLE_EPUB=/path/book.epub SAMPLE_EPUB_DIR=/path/epubs ./gradlew testDebugUnitTest
```

## Code layout

- `epub/` – EPUB 2/3 parser: reads the spine and the nav document or NCX table of contents, and flattens XHTML into paragraphs.
- `reader/Rsvp.kt` – word timing, sentence navigation, and the optimal-recognition-point position.
- `data/` – Zotero Web API v3 client with incremental sync, OAuth 1.0a sign-in, a local library cache, and preferences.
- `ui/` – Jetpack Compose screens: setup, library, and the reader with its three modes.
