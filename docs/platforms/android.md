# Android

The Android app in `Android/` (Kotlin, Jetpack Compose, Material 3 Expressive, Media3, Room,
Glance), on the shared Rust engine through UniFFI bindings
(`Android/app/engine-bindings/kotlin/uniffi/tracks_engine`, built by `Engine/build-android.sh`).

Everything below **Complete features** is built and merged on `feat/android-m3-expressive`.
It has been checked with unit tests and Robolectric screenshots against the real engine, but
not yet on a device: there is no emulator in the build environment. Items that need a device
to prove (Android Auto, Last.fm sign-in, widgets, the tile)
are marked *(untested on a device)*.

Two things to know about the platform:

- "Play" means your own music (files on the phone and servers): anything Apple Music does on
  iPhone has to come from history, the engine, your servers or Last.fm.
- The history comes only from Tracks itself: songs played in Tracks' own player, and your
  Last.fm history if you import it. See Decisions.

See [README.md](README.md) for the legend and how to keep this current.

## Decisions

- 2026-11-24: Sources replace "Your Music". A source is where a song comes from: On This
  Device and each server by its own name. They live in one
  registry (`sources/`), are managed in Settings > Sources, and filter Play and Library once
  there are two or more. The tabs are Play, Library, Summary, History and Search, with Charts
  as History's second mode. Plays stay in History when a source is removed.
- 2026-11-27: Apple Music and Spotify are no longer sources on Android. MusicKit for Android
  (the optional AARs, sign-in, the silent stand-in it played beside) and the sideload
  variant's librespot Spotify are gone; streaming services are to come through Trackside.
  Plays already kept from either stay in History with their sources.
- 2026-11-24: Cleartext is the app's decision, not the network security config's. The config
  can't name private address ranges, so it permits cleartext and `net/Http.kt`'s
  `CleartextGuard` refuses plain http to anything but a nearby address (the iPhone's and
  Trackside's rule) on every hop, redirects included; the fixed services Tracks talks to are
  held to https in the config too. Every HTTP client in the app is built from `net/Http.kt`.

- 2026-11-14: Keeping songs from other apps is removed. Tracks only tracks songs played
  through Tracks. The notification listener, its onboarding step, the Listening history and
  excluded apps pages, and the Radio filter are gone. Plays it kept earlier stay in the
  history.
- 2026-11-15: Play follows the iPhone's Play with your own music. New music comes from your
  Subsonic server, as on iPhone (its similar songs, which Octo gets from Last.fm), so those
  shelves show only with a server. They live on Play only; Your Music shows only your music.
  Play's old "Suggested songs" (your most played this month) and "Keep exploring" (old
  favorites) are gone: On Repeat and Rediscover cover them, and those names now mean new
  music, as on iPhone.
- 2026-11-15: Tracks Radio works as the iPhone's does with Your Music, apart from following the
  time of day and noticing driving. Its decisions moved into the engine
  (`tracks_mixes::radio`, a port of `LiveMix`), which Android plays. That engine radio is for
  Your Music only: on iPhone and Mac, Tracks Radio over Apple Music keeps its Swift `LiveMix`
  with the MusicKit catalog, and they may adopt the engine's for Your Music stations. The old
  five-step adventure slider became the iPhone's Familiar, Balanced and Adventurous.

## Complete features

### Foundation

- Material 3 Expressive theme with the expressive motion scheme and Google Sans Flex.
- Material You dynamic color from the wallpaper (Android 12 and later), or Tracks' own
  colors; a choice for colors from the cover playing is in the settings model.
- Themed (monochrome) app icon and a splash screen.
- Adaptive navigation with five places: Play, Library, Summary, History (Plays or Charts)
  and Search. The short bar on phones, the collapsed rail on mid-size windows and unfolded
  foldables, the expanded rail with labels on tablets.
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
  Last.fm user, Tracks Radio's tuning (discovery, genres, old favorites) and whether it shows,
  sample data, onboarded, Subsonic servers.

### System integration

- Deep links: `tracks://radio`, `search`, `library`, `summary`, `history`, `charts` (History in
  Charts mode), `nowplaying`, `mix/<id>`, `sources` and `settings/<page>`.
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
- `RadioSource`: Tracks Radio's songs from the engine's `RadioStation`, one pick at a time, with
  how each went (skipped, moved on, finished), likes, more like this, replays, removals, hear
  less, and its state (each pick's reason, its steering) for the screens.
- `MusicLibrary`: device files and servers merged, lookup from a history identity to
  something playable, search.

### Engine

- Uses the engine for the history index, charts, totals, timeline, activity, streaks, search,
  song, artist and album stats, name folding, credits, mixes, fresh shuffle, Tracks Radio
  over Your Music (picking, steering, probe, habits, skip memory, genres to tune to), Last.fm requests and parsing, and sample data.

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

- History's second mode, switched with Plays and Charts in the bar.
- Songs, artists and albums by period with up, down, new and same badges, a No. 1 card and up
  to 100 rows.
- Filter by the kind of source the plays came from (files and servers, Spotify, streaming
  imports), offered only when there are plays of two kinds.
- Play and Shuffle when the songs are in your library.

### History list and detail pages

- The whole history, paged, with sticky day headings and filters for All, Tracks and Last.fm.
- Imported and Last.fm plays are tagged on their rows.
- Swipe or press and hold to delete, with Undo.
- Artist, album and song pages: rank and share, plays, listening, first and last heard, plays
  per month, top songs, albums, plays with, credits and a time-of-day clock.
- Covers fly from lists into the page headers.

### Search

- Its own tab: results as you type, with recent searches. Everything, Library or History,
  and with two or more sources, one source's songs only.
- Search beyond your music: the iTunes catalog (no account, the phone's own storefront) under
  your own results, as "Not in your music": artists, albums (marked when you have some or all
  of them) and songs, each with a 30 second preview, open in another app, its catalog artist
  and album pages, and a get button when something can get it.

### Getting music you don't have

- Get music only appears when something can do it: a Subsonic server with Octo (found by its
  OpenSubsonic type or by search results it found elsewhere) or a connected Lidarr. Settings,
  Get music shows which servers can, and checks again on a tap. *(Octo and Lidarr untested
  against real servers; built against recorded answers)*
- Octo: find a song on the server and star it, which has Octo fetch it; Tracks syncs until it
  arrives and shows it as on its way meanwhile.
- Lidarr: connect with an address and API key (sealed in the Keystore), choose what Tracks
  follows, quality, metadata and folder, see what's downloading; ask for an album or follow an
  artist from any song, album or artist.
- More by an artist on both artist pages, with albums marked as yours, partly yours or not, and
  their songs you don't have. Your album pages say how many songs are missing and open the full
  album, which can list just the missing ones.

### Play, mixes and Tracks Radio

- A carousel of this hour's mix, Tracks Radio and the day's other mixes, with a play button
  that changes shape between play and pause.
- Mixes from the shared engine: time of day, On Repeat, New Finds, All-Time Favorites, Deep
  Cuts, Rediscover, A Year Ago. Radio Finds needs radio plays, so it never appears on
  Android.
- Sections in the order of the iPhone's Play with your own music: a card for a server that
  can't be reached, the crate, Suggested songs, Recently played, Your artists, Made from your
  listening, Waiting to be heard, then for each server Picked for you and Something
  different, and Keep exploring at the end. Each shows only when it has
  something.
- Suggested songs, Picked for you and Keep exploring come from each Subsonic server, as on
  iPhone: the engine (`tracks_mixes::server`) picks the artists to start from (what you play,
  then your library's), walks on to artists like them a step at a time as a shelf is scrolled,
  leaves out songs you've heard, and decides which shelf each goes to (artists you know to
  Picked for you, woven with your own unheard songs; new artists to Suggested songs). The app
  asks the server (search, then `getSimilarSongs2`) within a budget of a few requests then one
  every 8 seconds, checks covers, and keeps the picks on disk for 6 hours. A Trackside server's own
  picks (`/api/v1/me/picks`) start the shelves and the walk goes on from there; one from
  before picks answers 404 and gets the walk alone. Trackside's library songs only ever hold
  songs you have, so there an artist you have and have never played counts as new to you
  (Suggested songs), and before you've played anything the server's picks are Picked for you.
- New songs from Trackside: where `/me` has `features.acquisition` (requests on with a source,
  the account allowed to request songs, a device key, which has the `request` scope), the
  `external` songs Last.fm suggests that the server doesn't have (from `/me/picks`, Get More
  once you've listened, songs like each artist's, and for an artist the server has no songs by,
  `search?types=external`) join Suggested songs, two for each of the server's own, so it
  fills with something to get even before you've listened or on an empty server. They join
  Picked for you too, one in four, and Keep exploring, one in two. All three take from one
  queue per server, so no new song shows on two shelves; when the walk has nothing more to go
  on from, a third of the waiting ones stay for Keep exploring. Before you've
  listened, every other one of the server's picks goes to Suggested songs too. A new song has
  no cover and an id of `new:` and its identity, so a shelf's keys stay unique. Its Add button sends `POST /acquisitions` with a fresh
  `Idempotency-Key` (sent again with the same key if the connection drops), shows a clock
  while the request goes on, checks it with `GET /acquisitions/{id}` every 5 seconds, then
  every 30, for up to 30 minutes (and again after a restart), and syncs once it's done. 200
  (already there), 429 `quota_exceeded` (says when it resets, in your time) and 410 each get
  a plain message. Without `features.acquisition`, Suggested songs says why there are no new
  songs and what the server's admin can do: turn on requests on the dashboard's Requests page
  and set a Last.fm API key under Settings, or allow the account to request songs under People.
- Tap to get and play: tapping a new song (For You shelves, Keep exploring, and the catalog's
  song rows in search, artist and album pages where Trackside can get it) queues the list as
  usual, with each new song as a stand-in: media id `trackside-get:<server>:<identity>`, address
  `tracks-get://<server>/<identity>`, the song's title, artist and catalog cover. ExoPlayer
  plays a stand-in through `WaitingMediaSource`, a period that never prepares, so it moves onto
  it as onto a slow stream and sits buffering: the player opens at once on the song with a
  loading indicator in its play button, how it's going under the title ("Asking Home for it",
  "Downloading, 40%"), and nothing is counted as a listen. `GetAsReached` in the service gets
  the stand-in the player reaches while it's meant to be playing (not ones further on, so no
  request is spent on songs you skip before them): with `instant_play` a candidate that can
  play at once is requested with `instant: true` and its `stream` plays as soon as it's there;
  else it's requested (section 15.3) and, with `external_play`, played at once without keeping
  (section 15.6) while the request keeps it; otherwise the request is followed every 2 seconds
  and the library's copy plays once it's done and synced. A song already on its way waits on
  that request rather than asking again. The song then takes the stand-in's place and plays;
  if it can't be got (quota, requests off, not found, five minutes without it arriving), it's
  taken out with the reason in the player's quiet message and the next song plays. Moving to
  another song stops waiting (the request goes on), and is no skip; coming back waits again.
- Something different is the server's random albums (`getAlbumList2` / `albums?sort=random`
  only; the most played list is no longer asked for). Where Trackside gets songs, it mixes in,
  one after every two, an album from Apple's catalog for up to five artists of the new songs
  the server suggests that you have nothing by. They use the catalog's own album cell and page,
  whose Get album (and each song's Get) asks Trackside for every song you don't have with
  `POST /acquisitions`, stopping at a used-up quota. The catalog's Get shows for Trackside
  whenever a server's `/me` has `features.acquisition`.
- A server's shelves show while its first sync is still under way (picks don't need its
  library) and don't wait for your listening to be read. A first load runs in the app's scope,
  so scrolling or leaving Play doesn't end it; it goes on while both shelves are empty, for at
  most 40 seconds (once one has songs, the other fills as it's scrolled or tried again), then
  every shelf settles: songs, "didn't find anything" with Try again, or "Couldn't reach" with
  Try again when every request failed. Each step is logged, redacted,
  under the `Tracks` tag: `adb logcat -s Tracks` shows the servers' states, how many artists
  the picks start from, whether the server picked songs itself, songs left out for want of a
  cover, and each load's counts and requests. With Octo, songs not
  in your music have an Add button that has Octo fetch them. Keep exploring weaves in songs
  you've never played from `getRandomSongs`.
- Recently played (your albums by when you last heard them), Your artists (only ones in your
  music), Waiting to be heard (your songs you've never played, in a fresh order each day).
- Mix pages with Play and Shuffle; songs you can't play here are dimmed and offer "Open in"
  another app.
- Press and hold any song on Play (every shelf, Keep exploring, Waiting to be heard, a mix's
  songs) for one sheet in the iPhone's order: Play, Play next, Add to queue; Add to your music
  where the server fetches; Go to album, Go to artist, Your stats; Open in another app; Share;
  Hear less of this. Go to album and Go to artist are TalkBack actions on each song, album and
  artist too. Songs you have open your music's pages; songs a server found that you don't
  open that server's album and artist pages (`getSong`, `getAlbum`, `getArtist`,
  `getTopSongs`, with a search by name for Octo's); the rest open your history's.
- Pin a mix to the home screen.
- Hear less of, with Undo: the song leaves every mix and the radio.
- Tracks Radio, as on iPhone with Your Music (see the parity table below): your songs weighted
  by plays and recency, never-played songs and your servers' Picked for you finds as new finds,
  picked one song ahead as the one before starts, steered by skips, songs let play, likes,
  More like this and replays, with why each song came up on Now Playing and in Up next.
- New songs on Tracks Radio: where a Trackside server gets songs, one song after every three
  of the station's own is a new one the server suggests (never two together, never twice in a
  listen, none you've asked to hear less of), said to be a new find. With
  `features.acquisition.external_play` it plays without keeping it (`POST /external/play`,
  section 15.6); else with `instant_play` a candidate that can play at once is requested with
  `instant: true` and played from its `stream` while it downloads (section 15.5), and it joins
  your library once it's in; with neither, new songs are left out. The stream path is a
  `media.tracks-server.invalid/<server>/play/<path>` link that gets the media token (`mt`) as
  it goes out. The play's media id is `trackside-new:<server>:<identity>`: it's kept in your
  history and scrobbled like any other, and sent to Trackside as a listen without a song id.
- Tune Tracks Radio: Show Tracks Radio, Familiar, Balanced or Adventurous, genres to lean
  into, Bring back old favorites, Reset to Balanced.

### Now Playing

- Colors from the cover, artwork that breathes while playing and rests when paused.
- Swipe the artwork to skip; pull down to close.
- With two or more sources, where the song plays from under the seek bar; Up next marks each row's source when the queue mixes them, and dims songs whose
  source can't play them now. When the player skips songs it couldn't play, one quiet
  snackbar says how many and why.
- "Playing from", tappable artist, play count and a ring that fills until the song is kept.
- Wavy seek bar that goes flat when paused.
- Shuffle and repeat, expressive button group transport.
- Output switcher, sleep timer with a fade, Up Next with drag to reorder and swipe to remove,
  Share.
- Mini player as a floating toolbar across the screen beside a round play button, tinted
  from the cover (or in the app's colors, from Settings, Appearance), with the song's progress filling the play button's edge: tap or swipe up
  to expand, swipe to skip, press and hold (or TalkBack's Show queue action) for the queue.

### Sources and Library

- Settings > Sources: every source with one status word (Connected, Away when a server is
  reached outside home, Offline, Sign in, Check certificate, Syncing with a percentage on a
  Trackside server's first sync, a song count), Add source (a container transform from the button;
  On This Device asks for music access, Server opens the server form),
  the Play From order once there are two or more (drag a row by its handle or after a long
  press, or TalkBack's Move up and Move down; any source can go anywhere and a new one goes
  last), and remove with a confirmation that
  counts what goes and says plays stay. Each source has a page: a server's status, name,
  addresses (home network, Tailscale, internet), Check connection and Remove. On a tablet the
  page sits beside the list.
- Play From: with every source on, each queued song plays from the first copy in the order
  that can play now (files and servers, matched by title and artist); filtered to one source,
  the copy asked for. A copy that fails is replaced by the next, from where it got to. Half a
  minute before a song ends, songs next whose source can't play are switched to another copy
  or skipped with one Snackbar (`Skipped 2 songs`, `Home is offline`); when nothing left can
  play, `Nothing in Up Next can play right now.` once the music stops.
- Library: one library across sources, with the source filter and Artists, Albums and Songs
  as chips. "No music yet" with Add source when there's nothing.
- Device files through MediaStore, with permission handling and change watching. *(untested
  on a device)*
- Subsonic-compatible servers (Navidrome and others): add, edit, test and remove. Passwords
  sealed with an Android Keystore key. Song lists cached and synced. OpenSubsonic API keys
  instead of a password where the server offers `apiKeyAuthentication`; every request,
  covers included, signed with a fresh random salt otherwise.
- Trackside, the first-party server, with the iPhone's protocol (`library/trackside`): the
  form tells Trackside from Subsonic as you type the address, then pairs with a dashboard
  code, approval from the dashboard, a password, or a pasted `trackside://pair` link. The
  device key is sealed with the Keystore key and sent only as a bearer header, and only to an
  address that has answered the endpoint proof; songs and covers carry the short-lived media
  token. Library synced by cursor (snapshot, then changes), the server's addresses refreshed
  from `/server/urls` (conditional on its ETag, at sync, on coming to the foreground and on a
  network change; `/me` for older servers), each new one used only once it passes its own proof,
  `https_required` read as "use the https address", the device revoked on the server when removed. Plays through playback
  decisions without HLS. *(untested against a real server; built against its wire fixtures)*
- Away from home: a server's addresses (home network, Tailscale, public; Trackside lists its
  own, Subsonic takes "Other addresses") are raced like the iPhone's `ServerEndpoints`,
  preferring the home address when it answers within 300 ms, and raced again after two
  failed requests or when Android reports a network change. An address counts only once it
  proves it is the server (Trackside's proof; for Subsonic an unsigned ping in the API's own
  shape), so a captive portal's page is "not reachable", never a server. 10 s connect and
  30 s read timeouts; idempotent API requests retried twice with backoff; songs resume from
  where a dropped connection broke off with range requests. A plain http proof holds only on
  the network it was made on: Android's default-network callback forgets it the moment the
  network changes, and each request compares the network with the proof's. *(network changes
  and captive portals untested on a device)*
- Subsonic on someone else's network: Subsonic can't prove who it is, so whoever holds
  192.168.1.10 on a café's Wi-Fi would get what it's sent. An API key never goes over plain
  http (except to this device), so the form offers only a password for an http address. When
  the server also has an https address, the password's token goes to its plain http ones only
  on the network it was added on (`homeNetworks`); elsewhere the http address isn't
  even asked and https is used. Android shows the Wi-Fi name only to apps with location
  access, which Tracks doesn't ask for, so a network is told by its router: gateway, DHCP and
  DNS servers and search domain, hashed (`NetworkIdentity`). A network set up the same way can
  pass, so an https address is the safe choice. Nothing learns a network later: a server added
  before this uses its http addresses only if it has no https one, until it's added again. An
  http-only server shows "Not
  encrypted. Use it only at home." when added.
- Certificates: a server with a self-signed certificate is trusted only once you confirm its
  fingerprint, and a pinned address that later shows a different certificate is refused until
  you confirm again (Settings, Servers shows it). Each server has its own OkHttp client and
  pin store (`Http.pinned`); Apple Music, Last.fm and the web use clients that trust the
  system alone, and server songs and covers are fetched with their server's client. A
  fingerprint Trackside announces for its own addresses only vouches for a certificate the
  system doesn't trust, never for the hosts in `network_security_config.xml`. A pairing link's
  fingerprints live in a client made for that pairing and are cleared when it ends; only the
  one at the address that proved the offer is kept. Only system CAs are trusted in release
  builds.
- Pairing: the link prompt shows each address (host:port), not just the name the link gives.
  A link or a typed address never replaces a server already here with the same server id: it's
  refused with both addresses, and only signing in again from that server's page replaces it.
  The claim must come from the server whose offer was proven. When the sync server is paired
  again (its address or key changes), Trackside Cloud forgets what it knew and asks before
  sending anything.
- Artists, Albums and Songs, with album and artist pages.
- Sample music in sample-data mode, so the whole app plays.

### Playback and system

- Media3 `PlaybackService`: audio focus, headphones unplugged, network wake lock, media
  notification with like, shuffle and repeat buttons. Like stars the song on its server.
  *(untested on a device)*
- Android Auto browse tree: Tracks Radio, Mixes, Recently played, Albums, Artists.
- Assistant "play X on Tracks"; "play music on Tracks" starts the radio.
- Artist-spread shuffle from the engine, written into the queue so every controller sees it.
- Resumes the last queue after a restart or from Bluetooth.
- No credential leaves the server layer: queue memory, Room's history, Media3 items (and so
  Android Auto, Wear and other controllers) and the image cache hold credential-free links
  (`https://media.tracks-server.invalid/<server>/stream/<song>`), signed by an OkHttp
  interceptor only as each request goes out. Older saved queues and history covers are
  cleaned on read. Logs redact `u`, `t`, `s`, `p`, `token`, `apiKey`, `api_key`, `key`, `mt`,
  `password`, `access_token`, `sig`, `signature`, `api_sig` and `sk`.
- The media service is exported for Android Auto, Wear and the Assistant, so any app can bind
  it. Only Tracks, controllers Media3 calls trusted (the system, apps with media control or
  notification access) and Android Auto, Automotive, Wear, the Assistant, System UI and
  Bluetooth when installed as part of the system get browsing, queueing and Tracks' own
  commands (`ControllerAccess`). Any other app gets play, pause, skip and seek. Items a
  controller sends play only as the library's own songs by id, never from its address or
  details, so nothing made up reaches History or Last.fm.
- Quick Settings tile for Tracks Radio, showing the song; press and hold opens Tracks.
- Dynamic shortcuts for your top two mixes, plus static ones for Radio, Search and Summary.
- Listening and Last Played Glance widgets with dynamic colors. *(untested on a device)*
- Animated launch splash; adaptive icon with a themed layer.

### Scrobbling and Last.fm

- Connect through a Custom Tab, with the session key sealed by an Android Keystore key.
- Scrobbles in batches of 50 with WorkManager, settled by Last.fm's answer, with backoff.
- Now playing updates.
- Spotify's plays (from earlier sideload builds, or synced from another device) aren't
  scrobbled, since Spotify scrobbles them itself.
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
- Settings in Android 16's grouped style: Sources and Sync first, then Last.fm, appearance,
  Tracks Radio, Hear less of, privacy, about, licenses, app language and sample data.
- Onboarding: welcome, Add music (On This Device, a server), Last.fm,
  and try with sample data.
- Material 3 Expressive's short navigation bar on phones (a wide rail on larger screens). It
  slides away while you scroll down and returns when you scroll up; the mini player follows,
  and pages leave only the room they need at the bottom.
- Press and hold on every song, album, artist, mix and history row.
- Haptics matched to each action: long press, ticks while scrubbing and switching, confirm
  and reject, toggles.
- Expressive spring motion, shared element transitions, animated list items, count-ups.

## Tracks Radio parity

iPhone with Your Music against Android. Time of day and driving are left out on purpose. The
engine and its wiring are tested on the JVM; the player, notification and Android Auto parts
are *(untested on a device)*.

| iPhone | Android |
| --- | --- |
| Songs: every song in Your Music, played ones by plays (log) and recency, a song heard lately giving way, songs heard in the last 6 hours resting, never-played ones as new finds | Same, in the engine (`tracks_radio`) |
| New finds from your servers' Picked for you (picks, suggested, further) not in your music | Same, from `ServerPicks` |
| Hear less of and songs skipped twice in 60 days left out; one recent skip weighs a song down | Same: hidden songs and the engine's skip memory, kept in `radio-skips.json` |
| Share of new finds from Familiar, Balanced, Adventurous (8%, 25%, 50%), more for a small history | Same |
| Genres to lean into (4x, the rest 0.2x), Bring back old favorites (2.5x after 90 days) | Same; genres offered are the ones you play most, by your music's tags |
| Each artist balanced by the square root of their songs; artists kept apart and spaced | Same |
| Opens on one of your strongest songs; from nothing, a probe of a few genre families, decades and new finds, which extends when all are skipped and settles on what landed | Same |
| Habits by moment (genres, decades, new finds), fading over 45 days, steering the next listen | Same, kept in `radio-habits.json`, always the "anytime" band |
| Taste graph: songs played together lift each other, and say "Because you played" | Same |
| Steering: runs of skips in a genre, decade, new finds or your songs turn away; the song let play after is homed in on; turns away said at once | Same; said in a toast while Tracks is on screen |
| Skip, moved on or finished from how long it played | Same rule (`radio_listen`) |
| Like (heart) and Play More Like This: more of the artist, the waiting pick makes way | Same, from the notification's heart and Now Playing's menu |
| Previous well into a song: heard as wanting it again | Same |
| Taking a pick out of Up next is a skip, and another is picked | Same |
| Songs you add play before the station's next pick | Same |
| One pick queued ahead; picks forever, going round again when all have played | Same |
| Retuning applies from the next pick, keeping what the listen learned | Same |
| Pick reasons in Up next, and for the song on | Up next, and on Now Playing under the artist |
| Steering line, or "picks each song as the one before starts", under Up next | Same |
| No shuffle, repeat or Autoplay on a station | Shuffle and repeat hidden in Now Playing and the media notification |
| Tune Tracks Radio from the station's menu, Up next and Now Playing | From the crate's long press, Up next and Now Playing's menu, and Settings |
| Show Tracks Radio; hidden, Siri plays the hour's mix | Same; hidden, Assistant plays the hour's mix and Android Auto leaves it out |
| Paused Tracks Radio resumed after a restart starts again from that song | Same, through Media3's playback resumption |
| CarPlay row: Now Playing, Paused or Made from your listening; a tap resumes rather than restarts | Android Auto's Tracks Radio item, the same |
| Downloaded Songs First, Delete After Playing, Played and Removed | Not on Android: it has no downloads yet |
| Blocked artists | Not on Android yet (no Block an artist) |
| Through the Day, Driving | Left out on purpose |

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
- Tracks Radio following the time of day (Through the Day).
- Learning which songs go together, and Autoplay after an album or playlist.
- Choose Artists for Picked for you (iPhone's SeedArtistPicker), and its start card when
  there's nothing to start from.
- The server's slower second search for an artist (iPhone shows the quick one first and adds
  the slower one's songs later); Android does one search.
- Your servers' songs in the crate.
- Moods, genres, Handpicked and Party.
- From Your Downloads (Android has no downloads yet) and the library list on Play.
- For You cards.
- Edit Play's sections; Open To.
- Pull to refresh Play.
- Block an artist.
- Driving: Tracks Radio for the road, from Activity Recognition or an Android Auto
  connection.
- Shake to Play.

### Now Playing

- Moving backgrounds (Full Cover, Living Colour, Pulse).
- Back of the sleeve: your history with the song.
- Stage: full-screen visualizer, for a TV through Cast or a second screen.
- Audio quality badge.
- Crossfade and gapless.
- Pick Up Where You Left Off.
- Favorite and add to playlist from the player.

### Your Music

- Get More, ownership marks and missing songs. The rules are in the engine already
  (`tracks-library`, exposed in `tracks-ffi/src/library.rs`), so Android calls the same ones
  the Apple apps do:
  - `OwnedLibrary(songs)`, built off the main thread when the library changes, then
    `owns(title, artist)` and `classifyAlbum(tracks)` for owned, partly owned or missing and
    the missing songs' positions; `ownershipOfCounts(owned, total)` where only counts are known.
  - `rankGetMore(candidates, taste, similar, rules)` for Get More's order.
  - `searchShowsFinds(returned, outsideLibrary)` to learn a server finds songs, from
    its own search answers.
  - `canGet(sources)`, `songGetRoute(foundOnServer, hasAlbum, sources)` and
    `albumGetRoute(foundOnServer, sources)` for whether and how to get something. Show none
    of it when `canGet` is false.

  Android already asks servers and Lidarr (`discover/`); what's left is wiring these rules into
  the pages. The Apple apps' `Apps/Shared/YourMusic/GetMore.swift` is the model to follow.
- Downloads from servers for offline, a Downloads page, Free Up Space, cellular rules.
- Streaming and download quality.
- Playlists and smart playlists.
- Lidarr's Wanted and Coming Soon lists, and adding an artist picked from Lidarr's own search.
- Tell the server about plays (Subsonic `scrobble`).
- Trackside: lyrics and playlists. Showing servers found on the network in Add Server and a
  Scan Code button there (`NearbyServers.found`, `rememberPairingScanner`) are ready for the
  form to use.

### Sync and backup

- Sync without a Trackside server (Google Drive's app data folder). With one, see Sync below.
- Sessions, stations, playlists and playlist entries in Trackside Cloud: Android has no
  radio sessions or playlists yet, so it doesn't sync them.

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
- **Apple-only features that don't come across**: Apple Music, its radio stations, the Heard on Radio
  playlist, Play Back, Merge with Apple Music, Explicit Songs, Offline Mode for Apple Music,
  Motif import, Handoff, CarPlay (Android Auto instead), the menu bar extra and the Mac's
  windows.

## Sync (Trackside Cloud)

2026-11-24. Settings, Sync is Off or Trackside Cloud on one of your Trackside servers (no
iCloud on Android). The page (`ui/settings/SyncPage.kt`) shows the server, when it last
synced or what went wrong, and what the server holds for you (`GET /cloud/usage`).
*(untested against a real server; built against its wire fixtures)*

- **Protocol** (`library/trackside/CloudWire.kt`, `TracksideCloud.kt`), per tracks-companion
  docs/api.md section 19: snake_case envelopes, kebab-case entity paths, `POST
  /cloud/{entity}:batch`, opaque `<epoch>.<seq>` cursors sent back verbatim, settings by
  `PATCH /cloud/settings` with a cleared value as an explicit `null`.
- **Engine** (`sync/CloudSyncEngine.kt`), the Apple apps' order: `PUT /cloud/devices/{id}`
  (with predecessors) before anything is pushed, push what changed here, then pull. Batches
  of at most 1000 items or 7 MiB, halved on `413`; per-item results: accepted and duplicate
  settle the item, conflict takes the server's copy (a play keeps the server's core and what
  was filled in here, and sends that again if the server lacks it), rejected drops it,
  `deleted` removes the play here too. `device_mismatch` (per item or on registering) stops
  sync with "Pair it again"; `quota_exceeded` stops it with nothing lost. A cursor is kept
  only after its page is in Room and the engine. `410 sync_reset` (the server restored a
  backup) pulls a full snapshot keeping everything here, then pushes all of it again.
- **What syncs**: plays (Room's id is the cloud's playID; another device's plays are never
  scrobbled here: `recordedBy` is set and the scrobble queue skips them; a source this app
  doesn't know is kept by name, goes back unchanged and counts only under All), play deletions as
  tombstones, likes (by song identity, `{"loved"}`, an unlike as `false`), forgotten songs,
  blocked artists and excluded stations (kept and round-tripped; forgotten songs keep Last.fm
  imports out), and settings: `scrobblesToLastFM` plus Android's own `android*` keys for
  appearance, Tracks Radio and Hear Less Of. Servers, keys and Last.fm stay on the phone.
- **Local changes**: plays carry a change counter (`cloudPending`) that a push clears only
  if nothing changed since it was sent; settings and likes are compared with the cloud's
  last copy. Turning sync on, or choosing another server, marks everything to go up first,
  and settings and likes go as seeds dated 1970, so another device's choice stands.
- **When**: at app start (foreground pull), 10 s after anything changes, on the server's
  `cloud` event, and every 4 hours with WorkManager on any network, backing off
  exponentially while the server can't be reached.
- **Ids**: the device id is a random UUID sealed with a Keystore key (`sync/DeviceIdentity.kt`)
  and is also the `client_device_id` sent when signing in and pairing. A phone restored from
  a backup gets a new id and names the old one (read from the backed-up history database)
  as its predecessor. Last.fm imports get the id an iPhone derives for the same scrobble:
  UUIDv5 in namespace 8F6B1D3C-2A47-5E90-B1C8-4D2E7F9A0B36 of
  `playedAtMs␟lastFM␟appleMusic␟songIdentity` (`data/PlayIds.kt`). Room version 2
  (`Migration1To2`) adds the sync columns and tables and renames older `lastfm-…` ids.
- **Trackside alongside**: live events (`TracksideEvents`, SSE while the app is in the
  foreground, reconnecting from 1 s to a minute with jitter, `Retry-After` honoured; each
  reconnect asks `/server/urls` with `If-None-Match`, syncs the library and pulls the cloud),
  the listen outbox (`ListenOutbox`, in Room, sent with the play's id by WorkManager and kept
  until the server answers), now playing (`POST /now-playing` on play, pause and stop), the
  cellular profile (the server's `suggested_cellular_profile` on cellular), `https_required`
  trying the https addresses the refusal lists first, `trackside://pair` links (asked before
  pairing), QR pairing with Google's code scanner (no camera permission), and finding servers
  on the network with NSD (`_trackside._tcp`, TXT `id`, `v`, `tls`, `path`).
