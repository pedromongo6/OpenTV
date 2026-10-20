# OpenTV

A complete IPTV client for Android TV and phone. Live TV with EPG, a VOD catalogue,
series with seasons and episodes, multi-source failover, and the playback controls
IPTV actually needs.

Built as the client half of a reseller-panel app — the shape YouCine TV, IPTV Smarters
and similar clients share — but pointed at **your** sources. It ships no content and
no credentials. You supply an M3U playlist, an XMLTV guide, or an Xtream Codes panel
login.

## What it does

**Live TV**
- Category rail + channel list with logos
- EPG panel that follows the focused channel, with a progress bar on the current programme
- Search across channel name and guide mapping
- Catch-up/archive badge when the provider advertises it
- Long-press to favourite

**Movies and Series**
- Poster grid, responsive column count for phone through 1280dp TV
- Category filtering and search
- Series detail with season chips and episode list
- Long-press to add to watch-later

**Player**
- ExoPlayer (Media3) with HLS, DASH and progressive containers
- **Multi-source failover** — hands the player every known URL for the item and advances
  automatically on unrecoverable errors, giving each host two attempts first
- Remembers which host worked and starts there next time
- Audio track and subtitle selection, built from the real track list
- Four aspect modes: Fit, Fill, Zoom, Stretch
- Resume playback, saved every 5 s and on background
- Playback speed, technical info overlay, PiP-ready
- TV remote mapped: MENU cycles aspect, Y is audio, X is subtitles, INFO shows the overlay

**Sources**
- Xtream Codes panel (`player_api.php`), with live/VOD/series
- M3U / M3U8 playlists, extended attributes, `#EXTGRP`, `#KODIPROP`, `#EXTVLCOPT`
- XMLTV guide, from the playlist header or a separate URL
- Automatic movie/series/live classification from group names and `S01E04` patterns
- Credentials stored in `EncryptedSharedPreferences`; login verified before saving

## Build

Requires **JDK 17+** and the **Android SDK** (compileSdk 34).

```bash
# Point at your SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew installDebug         # to a connected device
```

Release:
```bash
./gradlew assembleRelease    # needs a signing config
```

If `gradlew` is absent, generate the wrapper once with a local Gradle 8.9:
```bash
gradle wrapper --gradle-version 8.9
```

## Architecture

```
ui/            Activities, Fragments, ViewModels, RecyclerView adapters
data/          Repository (single choke point), Prefs, Http
data/m3u/      M3U + XMLTV parser, M3uProvider
data/xtream/   XtreamProvider
data/db/       Room: sources, favourites, watch-later, resume, EPG cache
domain/        Provider-neutral models, LoadResult error algebra
player/        ExoPlayer factory, failover controller, aspect layout, media session
```

Design notes:

- **`Repository` is the only door.** Screens never touch a provider directly; adding a
  new backend means implementing `Provider` and adding one branch in `Repository.build`.
- **Errors are values.** Every call returns `LoadResult<T>`, so a timeout, a rejected
  login and a malformed playlist each produce a specific message instead of a crash.
- **The parser normalises once.** `M3uParser` turns a flat playlist into live/movies/series
  with categories, and heuristics classified as `EntryKind` at the owning boundary.
- **Failover is separate from the player.** `FailoverController` is a `Player.Listener`, so
  the retry policy is testable and the player stays a plain ExoPlayer.

## Notable behaviours

- **Never gives up on the first error.** Nine retries with widening backoff, and a fresh
  host before declaring failure. Panels return 502s under load and recover on their own.
- **Malformed errors don't retry.** Bad container, bad manifest, unsupported codec and
  404/403 advance to the next host immediately; network errors are retried in place.
- **Cleartext HTTP is permitted** (`network_security_config.xml`). Most panels are plain
  HTTP. No credential ever leaves the device except to the panel the user typed in.

## Layout

| | |
|---|---|
| Kotlin | 3,500+ lines across 26 files |
| minSdk / targetSdk | 21 / 34 |
| ABIs | all (no native code shipped) |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, FOREGROUND_SERVICE, POST_NOTIFICATIONS |

## What this is not

Not a source of free content. It is a client: it plays what you point it at. Feed it a
public free-to-air playlist, your own Jellyfin/Emby server, or a panel you pay for.
