# Android

The Android app in `Android/` (Kotlin, Jetpack Compose, Material 3 Expressive, Media3, Room,
Glance), on the shared Rust engine through UniFFI bindings
(`Android/app/engine-bindings/uniffi/tracks_engine`, built by `Engine/build-android.sh`).

Everything below **Complete features** is built and merged on `feat/android-m3-expressive`.
It has been checked with unit tests and Robolectric screenshots against the real engine, but
not yet on a device: there is no emulator in the build environment. Items that need a device
to prove (Android Auto, Last.fm sign-in, widgets, the tile)
are marked *(untested on a device)*.

Two things to know about the platform:

- Tracks can't play Apple Music on Android, so "Play" here means your own music (files on the
  phone and Subsonic servers). Anything Apple Music does on iPhone has to come from history,
  the engine, your servers or Last.fm.
- The history comes only from Tracks itself: songs played in Tracks' own player, and your
  Last.fm history if you import it. See Decisions.

See [README.md](README.md) for the legend and how to keep this current.

## Decisions

- 2026-11-14: Keeping songs from other apps is removed. Tracks only tracks songs played
  through Tracks. The notification listener, its onboarding step, the Listening history and
  excluded apps pages, and the Radio filter are gone. Plays it kept earlier stay in the
  history.

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
  Playing), hidden songs, scrobbling on or off,
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

## Complete features (built in the app)

### Keeping plays

- Keeps every song played in Tracks' own player once it passes Last.fm's rule (half its
  length or four minutes; seeks don't count), with the final listened time saved.

### Insights (Summary)

- Week, month, year and all time, stepping back through past periods.
- Listening time counting up, compared with the same point last period.
- A bar for every day (every month for a year) with a daily average line; touch to read each
  bar.
- Artists, different songs, new to you and streak tiles.
- Highlights ported from iOS where the engine has the data (milestones, on repeat, top
  artist share, new artists, mostly new, night owl or early bird, busiest hours, weekend
  listener, biggest day, most played song), plus new favorite and longest streak.
- Top songs with movement, top artists and albums, a 24-hour clock, a weekday chart, a
  week-by-hour heat map and how you listened.
- Empty states on Summary, Charts, History and Play that point to playing in Tracks or
  importing from Last.fm.

### Charts

- Songs, artists and albums by period with up, down, new and same badges, a No. 1 card and up
  to 100 rows.
- Filter by All, Streaming and Your music.
- Play and Shuffle when the songs are in your library.

### History list and detail pages

- The whole history, paged, with sticky day headings and filters for All, Tracks and Last.fm.
- Imported and Last.fm plays are tagged on their rows.
- Swipe or press and hold to delete, with Undo.
- Artist, album and song pages: rank and share, plays, listening, first and last heard, plays
  per month, top songs, albums, plays with, credits and a time-of-day clock.
- Covers fly from lists into the page headers.

### Search

- Results as you type across artists, songs and albums, with recent searches.
- Your Music search across device files and servers.

### Play, mixes and Tracks Radio

- A carousel of this hour's mix, Tracks Radio and the day's other mixes, with a play button
  that changes shape between play and pause.
- Mixes from the shared engine: time of day, On Repeat, New Finds, All-Time Favorites, Deep
  Cuts, Rediscover, A Year Ago. Radio Finds needs radio plays, so it never appears on
  Android.
- Your artists lately, suggested songs from your history, Keep exploring.
- Mix pages with Play and Shuffle; songs you can't play here are dimmed and offer "Open in"
  another app.
- Pin a mix to the home screen.
- Hear less of, with Undo: the song leaves every mix and the radio.
- Tracks Radio: the engine's `RadioStation` over your library, weighted by how much and how
  recently you play each song, with never-played songs as new finds. Learns from skips.
- Tracks Radio adventure setting.

### Now Playing

- Colors from the cover, artwork that breathes while playing and rests when paused.
- Swipe the artwork to skip; pull down to close.
- "Playing from", tappable artist, play count and a ring that fills until the song is kept.
- Wavy seek bar that goes flat when paused.
- Shuffle and repeat, expressive button group transport.
- Output switcher, sleep timer with a fade, Up Next with drag to reorder and swipe to remove,
  Share.
- Floating mini player: tap or swipe up to expand, swipe to skip, press and hold for the
  queue.

### Your Music

- Device files through MediaStore, with permission handling and change watching. *(untested
  on a device)*
- Subsonic-compatible servers (Navidrome and others): add, edit, test and remove. Passwords
  sealed with an Android Keystore key. Song lists cached and synced.
- Songs, Albums, Artists and Servers, with album and artist pages.
- Sample music in sample-data mode, so the whole app plays.

### Playback and system

- Media3 `PlaybackService`: audio focus, headphones unplugged, network wake lock, media
  notification with like, shuffle and repeat buttons. Like stars the song on its server.
  *(untested on a device)*
- Android Auto browse tree: Tracks Radio, Mixes, Recently played, Albums, Artists.
- Assistant "play X on Tracks"; "play music on Tracks" starts the radio.
- Artist-spread shuffle from the engine, written into the queue so every controller sees it.
- Resumes the last queue after a restart or from Bluetooth.
- Quick Settings tile for Tracks Radio, showing the song; press and hold opens Tracks.
- Dynamic shortcuts for your top two mixes, plus static ones for Radio, Search and Summary.
- Listening and Last Played Glance widgets with dynamic colors. *(untested on a device)*
- Animated launch splash; adaptive icon with a themed layer.

### Scrobbling and Last.fm

- Connect through a Custom Tab, with the session key sealed by an Android Keystore key.
- Scrobbles in batches of 50 with WorkManager, settled by Last.fm's answer, with backoff.
- Now playing updates.
- Import from Last.fm, skipping plays already kept. The row says what it does before you tap
  it and shows progress; there is no confirmation dialog.
- A notification when the session is revoked.
- Keys come from a gitignored `secrets.properties`; without them the app says Last.fm isn't
  set up in this build.

### Settings and the rest

- Colors follow the system (Material You, from the wallpaper) by default. Appearance offers
  a custom color instead: the wallpaper's own colors or twelve presets, in Tonal, Vibrant,
  Exact or Muted style, plus pure black for dark mode. Colors follow Android's contrast
  setting and cross-fade when changed.
- Settings in Android 16's grouped style: Last.fm, servers, appearance, Tracks Radio, Hear less of, privacy, about, licenses,
  app language and sample data.
- Onboarding: welcome, permissions, Last.fm, and try with sample data.
- Material 3 Expressive's short navigation bar on phones (a wide rail on larger screens). It
  slides away while you scroll down and returns when you scroll up; the mini player follows,
  and pages leave only the room they need at the bottom.
- Press and hold on every song, album, artist, mix and history row.
- Haptics matched to each action: long press, ticks while scrubbing and switching, confirm
  and reject, toggles.
- Expressive spring motion, shared element transitions, animated list items, count-ups.

## Missing for parity

Known issue shared with iPhone and Mac: the engine's credit splitting reads a band name
like "Wren & Ivy" as two artists.

Grouped by area. Where Android needs its own equivalent rather than a copy, the line says so.

### History capture

- Keep threshold and Same Song Again Within settings, and on demand or radio only.
- Pause History.
- Repair Artwork for covers that stop loading.
- Catch up in the background (scrobbles, covers) with WorkManager, the equivalent of
  Background App Refresh.

### Insights

- Records, sessions, variety, top genres and decades, running total, an all highlights page.
  These should come from the engine first, so every platform shares them.
- A dominant station highlight: the engine's play records don't carry the station yet.
- Separate streaming and Your Music stats.

### Charts

- Per-song bars by day, and play the chart.

### Detail pages and search

- How You Heard It (in Tracks, Tracks Radio, imported or Last.fm).
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
