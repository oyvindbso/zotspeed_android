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

### Reading

The app lists every EPUB attachment in your library. Use the dropdown in the top-left corner to switch to
a group library. Tap a book to download it and start reading. Long-press a downloaded book
to remove it from the device.

The app checks for changes incrementally, using Zotero's library versions. Use
**⋮ → Full re-sync** to rebuild the list from scratch.

**Limitations:** ZotSpeed can only download files that are stored in Zotero's own file storage.
EPUBs synced through WebDAV, or added as linked files, show up in the list but can't be downloaded
through the Zotero Web API. Images in the books are ignored.

## Using it on the Flip cover screen

The app is resizable and keeps running when it moves between the inner screen and the cover screen. Its
layouts are built for a screen of about 340 × 380 dp. Two settings help on the cover screen:

- **Theme → Black** (the default) uses true black, which saves power on the cover screen and blends into the bezel.
- **Show over lock screen** lets you read on the cover screen without unlocking the phone.

Samsung only lets apps on its own list run in the Flex Window. To run an app you installed yourself, such as this one, use
Samsung's **Good Lock** app and its **MultiStar** module. Under *I ♡ Galaxy Foldable → Launcher Widget*, add
ZotSpeed. The exact menu names vary between One UI versions.

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
