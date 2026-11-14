# Android

The Android app in `Android/` (Kotlin, Jetpack Compose, Material 3 Expressive, Media3, Room,
Glance), on the shared Rust engine through UniFFI bindings
(`Android/app/engine-bindings/uniffi/tracks_engine`, built by `Engine/build-android.sh`).

The app is mid-build. What's on `feat/android-m3-expressive` today is the foundation; the
screens and services are being built in parallel worktrees. Items marked **In progress**
are that planned scope. The lead flips them to Complete when the work merges.

Two things to know about the platform:

- Tracks can't play Apple Music on Android, so "Play" here means your own music (files on the
  phone and Subsonic servers). Anything Apple Music does on iPhone has to come from history,
  the engine, your servers or Last.fm.
- In exchange, Android can keep what you play in **any** music app, through a notification
  listener. On iPhone and Mac, Tracks only sees Apple Music.

See [README.md](README.md) for the legend and how to keep this current.

## Complete features

### Foundation

- Material 3 Expressive theme with the expressive motion scheme and Google Sans Flex.
- Material You dynamic color from the wallpaper (Android 12 and later), or Tracks' own
  colors; a choice for colors from the cover playing is in the settings model.
- Themed (monochrome) app icon and a splash screen.
- Adaptive navigation (navigation bar, rail or drawer by window size) with five places:
  Play, Summary, Charts, History, Your Music.
- Navigation 3 routes for artist, album, song, mix, search, Settings and its pages, and
  library albums and artists, with predictive back and back to Play from another tab's root.
- Edge-to-edge layout.
- Per-app language support declared (`locales_config.xml`).

### History and data

- Room database of plays as the source of truth, with kind (radio, on demand, imported,
  Last.fm), source (streaming or your music), the app it played in, station, measured
  listening time and scrobble state.
- The engine's history index mirrors Room and is rebuilt from it when needed; it follows the
  system time zone.
- History paged newest first, and by kind, ready for the History screen.
- Owed-scrobble queries and marking scrobbled or failed, ready for the scrobbler.
- Sample data from the shared engine (the same generator as the Apple screenshots), in its
  own database and index, switched on from a setting; switching restarts the app.

### Settings model

- Stored with DataStore: theme (system, light, dark), colors (wallpaper, Tracks, Now
  Playing), keep plays from other apps, excluded apps, hidden songs, scrobbling on or off,
  Last.fm user, Tracks Radio adventure, sample data, onboarded, Subsonic servers.

### System integration

- Deep links: `tracks://radio`, `tracks://search`, `tracks://summary`.
- Static launcher shortcuts: Tracks Radio (bound to the `actions.intent.PLAY` capability),
  Search, Summary.
- `MEDIA_PLAY_FROM_SEARCH` intent filter for "Play X on Tracks" from Assistant and other apps
  (handling is in progress).
- Settings reachable from the system's app info screen (`APPLICATION_PREFERENCES`).
- Auto Backup and device transfer of the history database and settings; the index is left
  out because it rebuilds.

### Contracts for the parts being built

- `PlayerController`: play, Play Next, add to queue, remove, move, skip to, seek, shuffle,
  repeat, sleep timer, start Tracks Radio; state with keep progress and kept flag.
- `RadioSource`: Tracks Radio's songs from the engine's `RadioStation`, a few at a time, with
  how each went (skipped, moved on, finished).
- `MusicLibrary`: device files and servers merged, lookup from a history identity to
  something playable, search.

### Engine

- Uses the engine for the history index, charts, totals, timeline, activity, streaks, search,
  song, artist and album stats, name folding, credits, mixes, fresh shuffle, Tracks Radio's
  picker, Last.fm requests and parsing, and sample data.

### Testing

- Screenshot tests with Robolectric and Roborazzi, including the icon.

## In progress

### History capture

- Keeps plays from any music app through a notification listener (declared in the manifest
  as `CaptureListenerService`).
- Keeps every song played in Tracks' own player.
- Exclude apps whose plays you don't want kept.

### Insights and charts

- Summary: listening time by period against last period, daily bars, streak.
- Charts: top songs, artists and albums by period, with movement.

### History list and detail pages

- History list with filters, swipe to delete and long-press menus.
- Artist, album and song pages.

### Search

- Search across your history, and your library and servers.

### Play, mixes and Tracks Radio

- Play tab with mixes from the shared engine: time of day, On Repeat, New Finds, All-Time
  Favorites, Deep Cuts, Radio Finds, Rediscover, A Year Ago.
- Mix pages.
- Tracks Radio through the engine's `RadioStation`.
- "Open in another app" for songs in your history you don't have a copy of.
- Hear less of: songs leave every mix and the radio.

### Now Playing

- Now Playing with colors from the cover, a wavy seek bar, the queue, sleep timer, output
  switcher and the keep ring.
- Floating mini player.

### Your Music

- Device files through MediaStore (`READ_MEDIA_AUDIO`).
- Subsonic-compatible servers.

### Playback and system

- Media3 `PlaybackService` with the media notification, lock screen, Bluetooth and Wear OS
  controls.
- Android Auto browse tree (the service is a `MediaLibraryService`).
- Assistant "play X on Tracks".
- Quick Settings tile for Tracks Radio (declared as `RadioTileService`).
- Dynamic shortcuts.
- Listening and Last Played Glance widgets (declared, home screen and lock screen categories).

### Scrobbling and Last.fm

- Connect, scrobble and import through the shared Rust Last.fm client, sent with OkHttp.
  API keys from a gitignored `secrets.properties`.

### Settings and the rest

- Settings pages: Last.fm, capture, servers, appearance, about.
- Onboarding (notification access, music files, notifications).
- Long-press context menus and haptics throughout.
- Material You dynamic color and themed icon carried through every screen.

Note: the manifest declares `PlaybackService`, `CaptureListenerService`, `RadioTileService`
and both widget receivers, but none of those classes exist on the branch yet, and every
screen is a placeholder. `Startup` wires an empty library, an idle player and no radio.

## Missing for parity

Grouped by area. Where Android needs its own equivalent rather than a copy, the line says so.

### History capture

- Tell radio from on demand for plays from other apps (by app, by the notification's
  metadata, or a per-app setting), so the Radio filter and Radio Finds mean something.
- Keep threshold and Same Song Again Within settings, and on demand or radio only.
- Pause History.
- Repair Artwork for covers that stop loading.
- Recover plays missed while the listener wasn't running. There is no Recently Played; the
  nearest equivalents are a Last.fm Sync Now and a Subsonic server's own play history.
- Catch up in the background (scrobbles, covers) with WorkManager, the equivalent of
  Background App Refresh.

### Insights

- Highlights, records, sessions, variety, discovery, top genres and decades, listening clock,
  weekday and weekend rhythm, running total, new favorites. Unless the Summary work covers
  some of these, they are missing; most should come from the engine first.
- Separate streaming and Your Music stats.

### Charts

- Per-song bars by day, and play the chart.

### Detail pages and search

- How You Heard It (radio, on demand, which app or station).
- About the artist and song, Get Info with MusicBrainz credits and samples, Last.fm similar
  songs.
- Remembered searches ("last night", "on the radio · 1990s").
- Identify Song. ShazamKit has an Android SDK.

### Play

- Save a mix as a playlist.
- Tune Tracks Radio beyond adventure: genres, old favorites, Through the Day.
- Learning which songs go together, and Autoplay after an album or playlist.
- Suggested Songs and Artists, Find Songs, Find Artists, Keep Exploring. Without Apple Music,
  these need Last.fm similar artists and tracks, or a server with Octo.
- Moods, genres, Handpicked and Party.
- Your Artists shelf and For You cards.
- Edit Play's sections; Open To.
- Block an artist.
- Driving: Tracks Radio for the road, from Activity Recognition or an Android Auto
  connection.
- Shake to Play.
- Quick Switch has no equivalent need (one source), but switching between device files and a
  server that isn't answering would be.

### Now Playing

- Moving backgrounds (Full Cover, Living Colour, Pulse).
- Back of the sleeve: your history with the song.
- Stage: full-screen visualizer, for a TV through Cast or a second screen.
- Audio quality badge.
- Crossfade and gapless.
- Pick Up Where You Left Off.
- Favorite and add to playlist from the player.

### Your Music

- Octo: find and fetch songs you don't own.
- Downloads from servers for offline, a Downloads page, Free Up Space, cellular rules.
- Streaming and download quality.
- Playlists and smart playlists.
- Suggestions checked against what you have.
- Lidarr.
- Tell the server about plays (Subsonic `scrobble`).
- Passwords stored with the Android Keystore (EncryptedSharedPreferences or a Keystore key),
  not in DataStore as plain JSON.

### Scrobbling

- Store the Last.fm session key in the Keystore, as Apple keeps it in the Keychain.

### Sync and backup

- Sync between Android devices. Auto Backup restores to a new phone but doesn't keep two
  devices in step. Options: Google Drive's app data folder, or a sync built on the engine's
  idempotent writes.
- Sync with an iPhone or Mac. Nothing crosses today: Apple history is in CloudKit. This needs
  a design (a shared export format through Last.fm, a file, or a server) and is the biggest
  cross-platform gap.

### Devices and sharing

- Your Devices: see and control Tracks on another device nearby. Android's own equivalents
  are the output switcher and Cast for speakers; device-to-device control between Android
  phones and tablets would use Nearby Connections or NSD.
- SharePlay has no Android equivalent. The nearest is a join-by-code flow over the same
  encrypted relay, with a web page for guests in place of the App Clip.
- Louder at Speed (volume follows speed in the car).

### Widgets, shortcuts and system integration

- Top Songs and Today widgets.
- Quick Settings tiles for Save Radio Song (as "keep this song") and Identify Song.
- App Actions or App Functions for the App Shortcuts: top artist, search, play a mix or mood,
  play on another device.
- System search: AppSearch indexing of artists, songs and albums, the equivalent of Spotlight.
- Deep links to a song's page, as the Apple widgets use.

### Accessibility

- TalkBack labels and custom actions on every screen and chart.
- Respect "Remove animations" for moving backgrounds and transitions.
- Charts readable without color (patterns as Differentiate Without Color does).
- Layouts that hold up at the largest font scale.

## Platform-only

- **Keeping plays from any music app**: Android lets a notification listener read every
  media session. Apple platforms don't, so iPhone and Mac can't follow.
- **Excluded apps**: the counterpart of excluded stations, only meaningful with capture from
  other apps.
- **Open in another app**: on Android, a history song you don't own has to play somewhere
  else. On Apple platforms Apple Music plays it.
- **Theme and color choice**: Material You and an in-app light or dark choice are Android
  conventions. The Apple apps follow the system.
- **Wavy seek bar, floating mini player, expressive motion**: Material 3 Expressive design.
- **Quick Settings tile and dynamic shortcuts**: Android surfaces, standing in for Control
  Center controls and App Shortcuts.
- **Apple-only features that don't come across**: Apple Music playback, the Heard on Radio
  playlist, Play Back, Merge with Apple Music, Explicit Songs, Offline Mode for Apple Music,
  Motif import, Handoff, CarPlay (Android Auto instead), the menu bar extra and the Mac's
  windows.
