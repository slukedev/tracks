# Mac

The Mac app (`Apps/macOS`, with most screens in `Apps/Shared`), its menu bar extra
(`Apps/macOS/MenuBar`), the Mini Player, Stage and Song Info windows (`Apps/macOS/Play`,
`Apps/macOS/SongInfo`), the widget extension (`Widgets/`) and the Settings panes
(`Apps/macOS/Settings`). Logic shared with the iPhone lives in `TracksCore/`, and Music.app,
for Play Songs In set to Music, is driven through `TracksCore/Sources/TracksMusic`.

See [README.md](README.md) for the legend and how to keep this current.

## Complete features

### History capture

- Keeps every song played inside Tracks, from Apple Music or your own music, including while
  no Tracks window is open. Songs played in Music.app or other apps aren't kept.
- Knows radio from on demand because Tracks' player queued it, and keeps radio songs Apple
  Music doesn't count.
- Counts After, Same Song Within, Keep On-Demand Songs.
- Pause History.
- Exclude stations you don't want recorded.
- Heard on Radio playlist in your library.
- Imports your Last.fm history, from any app you scrobbled from, in Settings › Last.fm.
- Repair Artwork.
- Sample data with `-TracksDemoData YES` in Debug builds.

### Insights (Summary)

- A dashboard Summary that uses the room on a big display.
- Listening time by week, month, year or all time against last period, daily bars and average.
- Step through earlier periods with the arrows; hover a chart to read a day.
- Highlights, records, streaks, new favorites, running total, Discovery, Variety, Top Genres
  and Decades, sessions, time of day.
- Listening clock and weekday and weekend averages.
- Week-by-hour heat map (Mac only).
- Top songs, artists and albums, with Top Albums always filling its rows.
- Every source together or apart (Apple Music, files and servers, Spotify).

### Charts

- Top Songs, Top Artists and Top Albums as sidebar items, for any day, week, month, year or
  all time, with movement.
- Per-song bars by day; artists' share; the album you went deepest into.
- Charts work from the keyboard; play a chart.
- Charts read from the Rust engine.

### History list

- A sortable history table with an inspector for the selected song.
- Paged reads, so opening and sorting stay fast in a long history.
- Filters: All Songs, Radio, On Demand, Recovered, From Last.fm; and by source.
- Delete one or several plays (Delete key, with confirmation).
- Double-click or Return plays a song; ⌘I shows its details.

### Detail pages

- Artist, album and song pages: plays over time, rank, genre, release years, first and last
  heard.
- How You Heard It, About the artist, About the song.
- Song Info window (Get Info): your history, MusicBrainz credits and samples, Last.fm and
  Apple Music related songs, two columns on a wide window. Your Stats from the Song menu
  (⌥⌘I).

### Search

- Search in the sidebar and ⌘F, with Apple Music, Your Library and History scopes.
- Remembered searches in words, best match first, recent searches and moods.
- Asks your music servers as you type.
- Identify Song with Shazam, in a popover from the toolbar.

### Play, mixes, Tracks Radio, moods and suggestions

- Listen Now and Radio in the sidebar, with your library (Recently Added, Playlists, Albums,
  Artists, Songs, Downloads, Lidarr) under them.
- Everything on the iPhone's Play tab: the crate, mixes from your history, Save as playlist,
  hear less of, fresh shuffle.
- Tracks Radio with Tune, Through the Day, and learning which songs go together.
- Radio page: Tracks Radio first, then Apple Music's live stations, then the moods.
- Suggested Songs and Artists, Find Songs, Find Artists, Keep Exploring, For You, Your
  Artists, New from Your Artists.
- Moods, genres, Handpicked and Party.
- Edit Listen Now's sections.
- Source filter in the toolbar of Listen Now and every Library page, once a second source
  exists: All Sources, each source by its real name (Apple Music, On This Mac, each server's
  name), Manage Sources… (opens the Sources pane). Never touches the queue. The
  sidebar keeps its shape; its Library group follows the filter's player. With All Sources
  and more than one player, Listen Now is one page built from every source, one queue plays
  songs from several sources, album pages show the glyphs of every source that has them and
  artist pages a shelf per other source, as on iPhone (see the iPhone page, Tabs and
  sources). Up Next's rows carry their source's glyph in a mixed queue and dim when they
  can't play.
- Sources pane in Settings (after General): Apple Music access, On This Mac, servers, and
  Play From (drag to reorder; synced).
- Settings › General › About › Acknowledgements: the engine's licenses.
- Play Songs In Tracks or Music; Explicit Songs; Open To.
- Block artists; Reset Mix Suggestions.
- Starting music in Tracks pauses Music.

### Now Playing

- A glass player bar at the foot of the window; click the song for the full player.
- Full player: the cover large on its colour, your count, the transport; the sleeve turns over
  to your history.
- Up Next and Your History panels rise from the bar (⌥⌘U, ⌥⌘Y): drag to reorder, Delete to
  remove, double-click to play.
- Mini Player: the cover in a small window that stays in front, controls on hover (⇧⌘M to
  switch, ⌥⌘M alongside).
- Stage window for a second display or a TV, with a visualizer that hears Apple Music through
  a Core Audio tap of MediaPlayer's RemotePlayerService, the helper MusicKit plays in, and
  only that (⌥⌘P). Opt-in: asked the first time, then Settings › Play › Now Playing › Hear the
  Music; the song's preview groove when it's off or macOS refuses. Code in `Apps/macOS/Audio/`.
- Backgrounds: Full Cover, artwork, Living Colour, Pulse.
- Play count and keep ring, sleep timer, favorite star, audio quality badge, and beside it
  the source badge (two or more sources) whose menu plays the song from another source.
  Songs that can't play
  are dimmed in Up Next, and a queue that left some out says "Skipped 3 songs".
- Pick Up Where You Left Off.
- Gapless albums for your own music.
- The song and controls in the Dock and the system's Now Playing; media keys.

### Files and servers

- Play FLAC and other files: import songs or folders, or drop them in Tracks' Music folder
  (Show in Finder, Look for New Songs).
- Subsonic-compatible servers, servers that find songs, Trackside, Picked for You and server
  discovery.
- Get More, with a server that finds or gets songs, or Lidarr, as on iPhone: on Listen Now, artist and album pages, search
  and Downloads, with right-click menus, help tags on Get, and the shelf's paging arrows.
- Download for Apple Music and your server together (Settings, off by default): in song and album menus, the album header beside Shuffle. Apple Music adds to the library and opens Music to download, since MusicKit can't download.
- Downloads with a sortable table: space used, what you play, what's coming down or failed.
- Streaming quality and download quality (one setting for every network).
- Tracks Radio's Downloaded Songs First and Delete After Playing.
- Playlists, smart playlists, Merge with Apple Music (Merge Now).
- Suggestions checked against what you have; Lidarr in the sidebar and Settings.
- Tell Server About Plays.

### Servers away from home

- Addresses without a scheme get https unless they can only be nearby (private, link-local,
  loopback and 100.64/10 addresses, names without a dot, `.local`, `.lan`, `.internal`,
  `home.arpa`, `.ts.net`), the same rule Trackside uses (`NearbyAddress`). Credentials never
  cross plain HTTP to anything else. ATS allows only local networking plus those suffixes.
- Subsonic over plain HTTP (`SubsonicCredentialRule`): an API key never, except to loopback.
  A server that also has an https address gets credentials on its nearby http address only
  on a network recorded as its home (`NetworkIdentity`: routers, IPv4 subnets and
  unique-local IPv6 prefixes, as a digest), recorded only when it's added or edited in the
  form there and its nearby address answers; elsewhere the nearby address isn't raced and
  https is used. An entry with no home recorded uses https only. A server with only an http
  address works as before, and is marked as working on the home network when it's added.
- Subsonic: every request is signed with a fresh salt. Cover addresses carry no credentials
  at all (they're cache keys, and reach Now Playing); `ArtworkImages` signs each one as it's
  fetched through `ServerMediaSigning`. Add Server offers Password or API Key where the server
  lists OpenSubsonic's `apiKeyAuthentication`; a key is kept with no username.
- A Subsonic server can have several addresses (home, Tailscale, public), typed under Other
  Addresses in Add Server or its edit sheet. They're raced
  without credentials (`SubsonicEndpoints`, `AddressRace`), the nearby one wins within 300 ms
  of the first answer, and the race runs again when the network changes or two requests in a
  row fail. Reads retry with backoff (3 tries); scrobbles and stars are sent once.
- Redirects are followed only to the same server, so nothing that signs a request leaves it;
  a Wi-Fi sign-in page shows as "This Wi-Fi network needs you to sign in first".
- Trackside learns new addresses from `GET /server/urls` (conditional, with `If-None-Match`)
  when it connects, comes to the front, reconnects its events, or gets a `server_urls` event.
  The list is read only from a proven address and each new address must prove itself with
  `/ping` before the key goes there. A `403 https_required` moves Tracks to a proven https
  address and asks again, without asking for the password.
- A download that breaks off picks up where it stopped (the system sends `Range` and
  `If-Range`), on its own up to three times when the connection dropped.
- Logs never hold a signed address: errors go through `URLRedaction.describe`.

### Scrobbling and Last.fm

- Connect a Last.fm account (Connect… in a browser), key in the Keychain.
- Scrobbles every kept song, radio included; Now Playing updates.
- Import Last.fm history and Sync Now. Imported scrobbles get the same id as on iPhone and
  Android (`PlayID.lastFM`).
- Every play is scrobbled, Spotify's and the other music services' included, as on iPhone: a
  Spotify play is looked for in the Last.fm history first, so one Spotify sent itself isn't
  sent twice.

### Sync and backup

- Sync goes to iCloud or to Trackside Cloud on one of your Trackside servers (the Sync pane, Sync
  With; `CloudSync.backend`). The choice applies the next time Tracks opens.
- iCloud: history through your private iCloud database (CloudKit); settings and removed songs
  through the iCloud key-value store.
- Trackside Cloud (`TracksideCloudSync`, `CloudSyncEngine`): plays, sessions, stations,
  forgotten songs, blocked artists and excluded stations sync as items by lasting id, and
  settings through `PATCH /cloud/settings` by the same rules as iCloud's (`TracksideCloudSettings`
  stands in for the key-value store). Pulls follow an opaque cursor and start over from a full
  snapshot on `410 sync_reset`; pushes go in batches of up to 1,000, idempotent by id. After
  the first sync of a run only rows the store's history names as changed are compared
  (`CloudHistoryMark`); every row is compared again after a reset, a failed push, or a day.
  Tombstones go up only for plays deleted, forgotten or merged away here, kept in
  `CloudRemovalJournal` until the server has them; a play merely missing here is never
  deleted, and a record this build can't read is left as the server has it. Sync never starts
  on a history held in memory for a run that couldn't open the store. The server's `cloud`
  event, a save here, and coming to the front start a sync. Works with Tracks on Android
  signed in to the same server.
- Moving between them loses nothing: the first sync with a server takes what it has, sends up
  everything here, and the reconciler folds duplicates together. iCloud keeps what it had.
- Every play has a lasting id (`Capture.playID`, schema V3): new plays get a UUID when they
  start, older ones a UUIDv5 of their history-index key (`PlayID`), so every device gives a
  synced row the same id. It's the history index's id, the server listen id and the cloud id.
  Older rows are given theirs only where nothing mirrors to iCloud (Trackside Cloud, or sync
  off): written through iCloud, every device would upload its whole history again.
- Playlists over Trackside Cloud (`PlaylistCloud`): playlists and entries as items, each entry
  with a fractional order key, so concurrent adds and moves all survive.
- Not yet: likes over Trackside Cloud, and Nearby Devices pairing without iCloud (on Trackside
  Cloud, Nearby Devices works only with devices that also share iCloud).
- Every setting is listed once in `SettingsRegistry` (TracksCore's `core`, the app's
  `AppSettingsRegistry.swift`), as synced or kept on the device; renamed keys carry over through
  `formerKeys`, and a demo launch's changes are undone on the next real launch.
- A welcome the first time Tracks opens on the device: Apple Music, On This Mac and Server, each with Add; Last.fm; and the iCloud restore's progress
  (`Apps/Shared/Onboarding`).
- Sync can be turned off; the Sync pane shows what syncs and where it stands.

### Devices, Play On and SharePlay

- Your Devices: see and control what your iPhone is playing, send music there or bring it here.
- The menu bar shows what your other devices are playing, with their controls.
- Controls › Play On lists this Mac and every device nearby; HomePods and AirPlay speakers
  from Music, with grouping and volume.
- Your Trackside server's speakers join Play On and Controls › Play On; the full player and
  the bar show and control the one with the music, with its volume, power and grouping.
- Handoff of the song playing, in the Dock.
- Play on Another Device and Bring Music Here intents.

### Menu bar extra

- The song Tracks is playing and its cover, playback controls and a progress track to click
  or drag; the last song played when nothing is.
- Today's listening and streak, and your last few songs.
- Status item shows the radio icon, a note, the cover, the title, the artist, cover and title,
  or a format you write (with tokens such as `{station}`), optionally animated.
- Show in Menu Bar and Show in Dock, with a guard so one is always on.

### Widgets, controls, Siri and system integration

- Desktop and Notification Center widgets: Listening (small, medium), Top Songs, Last Played,
  Today.
- Controls: Play in Tracks, Identify Song.
- App Shortcuts for Siri, Spotlight and Shortcuts: the same ten as iPhone.
- Siri plays any song, album, artist, playlist, station, mix, mood or genre through the App
  Intents audio schemas (Play Audio, Create Station, Update Audio Affinity, Add to Library),
  the same queries the iPhone uses.
- Get Top Artist, Open Artist, Song and Album; Spotlight indexing.
- Menu commands with Music's shortcuts: Space, ⌘→, ⌘←, ⌘., ⇧⌘F, ⌥⌘R for Tracks Radio, ⌥⌘N
  for something new; Song menu (Favorite, Add to Library, Go to Album or Artist, Share, Create
  Station); Go › Back and Forward; View › Refresh (⌘R); ⌘1 onwards for sidebar items.
- Drag songs, albums and playlists onto Up Next or out to Messages, Notes or Finder.
- Open at Login.

### Settings

- Panes: General, Play, History, Radio, Last.fm, iCloud, Devices, Menu Bar, each sized to fit
  and opening with a line on how it's doing.
- General: Open at Login, Show in Dock, Apple Music access.

### Accessibility

- VoiceOver labels and actions, chart descriptors, keyboard navigation in charts and lists.
- Reduce Motion, Differentiate Without Color, large text layouts.

### Privacy

- No account, no analytics; history on the Mac and in your private iCloud database, or on your
  own Trackside server when Sync goes there.
- Last.fm only if you connect; Keychain storage.
- Identify Song sends only a signature; Your Devices only connects your own devices.

## Missing for parity

From the iPhone:

- Song Transitions (crossfade). The pane says none of Tracks' players crossfades on the Mac;
  the one for files and servers could, with its own audio engine.
- Offline Mode for downloaded Apple Music songs.
- SharePlay as host. GroupActivities runs on macOS, so a Mac at a party could take requests;
  the car flow doesn't apply.
- Lock Screen-style widget sizes (no equivalent on the Mac; listed for completeness).
- Siri can learn to use Tracks for a plain "Play music": SiriKit's media intent is iOS only;
  the App Shortcuts cover the Mac.

From Android (planned there):

- Open songs you don't have in another app (only matters with files and servers as the source).

Engine parity (no visible change):

- Summary, insights, search, mixes, Tracks Radio and Last.fm still run in Swift, as on
  iPhone. Tracks Radio over Apple Music stays in Swift; the engine's radio is for Your Music.
  See the iPhone document.

## Platform-only

- **Menu bar extra, Dock icon choice, Open at Login**: Mac app conventions. A phone app has
  none of them.
- **Sortable table with inspector, menu commands and keyboard shortcuts, drag to Finder**:
  desktop interaction. Android tablets and Chromebooks could use keyboard shortcuts later,
  but they aren't parity.
- **Mini Player and Stage as windows**: Stage exists on iPhone too, from Now Playing.
- **Week-by-hour heat map**: shown where there's room. The iPhone could have it (listed there
  as missing).
- **Music.app scripting, AirPlay speakers through Music, the Core Audio tap**: these exist
  because Music.app is the Mac's own player.
- **CarPlay, Louder at Speed, driving, Shake to Play, Action button, Focus filter
  surfaces**: phone-only surfaces. (The Focus filter intent is
  shared code, but Focus filters are most useful on iPhone.)
