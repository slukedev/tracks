# Mac

The Mac app (`Apps/macOS`, with most screens in `Apps/Shared`), its menu bar extra
(`Apps/macOS/MenuBar`), the Mini Player, Stage and Song Info windows (`Apps/macOS/Play`,
`Apps/macOS/SongInfo`), the widget extension (`Widgets/`), the Settings panes
(`Apps/macOS/Settings`) and the Watchdog helper (`Watchdog/`). Logic shared with the iPhone
lives in `TracksCore/`, and Music.app is driven through `TracksCore/Sources/TracksMusic`.

See [README.md](README.md) for the legend and how to keep this current.

## Complete features

### History capture

- Keeps every song played inside Tracks, from Apple Music or your own music.
- Keeps songs played in Music.app, from its `playerInfo` notifications and a poll, including
  while no Tracks window is open (the menu bar extra keeps it running).
- Tells radio from on demand (a station track reports no duration), and keeps radio songs
  Apple Music doesn't count.
- Searches the catalog for each song, for its ID and cover, since Music gives neither.
- Recovers songs played while Tracks was closed from Recently Played.
- Counts After, Same Song Again Within, Keep Songs Played on Demand, Treat Everything as Radio.
- Pause History.
- Exclude stations you don't want recorded.
- Heard on Radio playlist in your library.
- Play Back, and Play Back Automatically (Mac only).
- Repair Artwork.
- Motif import, again from Settings › History.
- Sample data with `-TracksDemoData YES` in Debug builds.
- Reopen Tracks If It Quits Unexpectedly: the Watchdog helper reopens the app when macOS ends
  it without a quit, and the app says what happened.

### Insights (Summary)

- A dashboard Summary that uses the room on a big display.
- Listening time by week, month, year or all time against last period, daily bars and average.
- Step through earlier periods with the arrows; hover a chart to read a day.
- Highlights, records, streaks, new favorites, running total, Discovery, Variety, Top Genres
  and Decades, sessions, time of day.
- Listening clock and weekday and weekend averages.
- Week-by-hour heat map (Mac only).
- Top songs, artists and albums, with Top Albums always filling its rows.
- Apple Music and Your Music together or apart.

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
- Quick Switch between Apple Music and Your Music.
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
- Stage window for a second display or a TV, with a visualizer that follows the Mac's own
  sound through a Core Audio tap (⌥⌘P).
- Backgrounds: Full Cover, artwork, Living Colour, Pulse.
- Play count and keep ring, sleep timer, favorite star, audio quality badge.
- Pick Up Where You Left Off.
- Gapless albums for your own music.
- The song and controls in the Dock and the system's Now Playing; media keys.

### Your Music

- Play FLAC and other files: import songs or folders, or drop them in Tracks' Music folder
  (Show in Finder, Look for New Songs).
- Subsonic-compatible servers, Octo, Picked for You and server discovery.
- Get More, with Octo or Lidarr, as on iPhone: on Your Music, artist and album pages, search
  and Downloads, with right-click menus, help tags on Get, and the shelf's paging arrows.
- Downloads with a sortable table: space used, what you play, what's coming down or failed.
- Streaming quality and download quality (one setting for every network).
- Tracks Radio's Downloaded Songs First and Delete After Playing.
- Playlists, smart playlists, Merge with Apple Music (Merge Now).
- Suggestions checked against what you have; Lidarr in the sidebar and Settings.
- Tell Server About Plays.

### Scrobbling and Last.fm

- Connect a Last.fm account (Connect… in a browser), key in the Keychain.
- Scrobbles every kept song, radio and recovered included; Now Playing updates.
- Import Last.fm history and Sync Now.

### Sync and backup

- History syncs through your private iCloud database with the iPhone.
- Settings and removed songs sync through the iCloud key-value store.
- Sync with iCloud on or off, with what syncs and what iCloud said.

### Devices, Play On and SharePlay

- Your Devices: see and control what your iPhone is playing, send music there or bring it here.
- The menu bar shows what your other devices are playing, with their controls.
- Controls › Play On lists this Mac and every device nearby; HomePods and AirPlay speakers
  from Music, with grouping and volume.
- Handoff of the song playing, in the Dock.
- Play on Another Device and Bring Music Here intents.

### Menu bar extra

- The current song and cover, playback controls and a progress track to click or drag.
- Today's listening and streak, and your last few songs.
- Status item shows the radio icon, a note, the cover, the title, the artist, cover and title,
  or a format you write (with tokens such as `{station}`), optionally animated.
- Show in Menu Bar and Show in Dock, with a guard so one is always on.

### Widgets, controls, Siri and system integration

- Desktop and Notification Center widgets: Listening (small, medium), Top Songs, Last Played,
  Today.
- Controls: Play in Tracks, Save Radio Song, Identify Song, Play Back Today.
- App Shortcuts for Siri, Spotlight and Shortcuts: the same ten as iPhone.
- Get Top Artist, Open Artist, Song and Album; Spotlight indexing.
- Menu commands with Music's shortcuts: Space, ⌘→, ⌘←, ⌘., ⇧⌘F, ⌥⌘R for Tracks Radio, ⌥⌘N
  for something new; Song menu (Favorite, Add to Library, Go to Album or Artist, Share, Create
  Station); Go › Back and Forward; View › Refresh (⌘R); ⌘1 onwards for sidebar items.
- Drag songs, albums and playlists onto Up Next or out to Messages, Notes or Finder.
- Open at Login.

### Settings

- Panes: General, Play, History, Radio, Last.fm, iCloud, Devices, Menu Bar, each sized to fit
  and opening with a line on how it's doing.
- General: Open at Login, Show in Dock, Reopen If It Quits Unexpectedly, Apple Music access.
- Copy Debug Info, and a notice after an unexpected quit.

### Accessibility

- VoiceOver labels and actions, chart descriptors, keyboard navigation in charts and lists.
- Reduce Motion, Differentiate Without Color, large text layouts.

### Privacy

- No account, no analytics; history on the Mac and in your private iCloud database.
- Last.fm only if you connect; Keychain storage.
- Identify Song sends only a signature; Your Devices only connects your own devices.

## Missing for parity

From the iPhone:

- Song Transitions (crossfade). The pane says neither MusicKit's player nor Your Music's
  crossfades on the Mac; Your Music's could, with its own audio engine.
- Offline Mode for downloaded Apple Music songs.
- SharePlay as host. GroupActivities runs on macOS, so a Mac at a party could take requests;
  the car flow doesn't apply.
- Lock Screen-style widget sizes (no equivalent on the Mac; listed for completeness).
- Siri can learn to use Tracks for a plain "Play music": SiriKit's media intent is iOS only;
  the App Shortcuts cover the Mac.

From Android (planned there):

- Open songs you don't have in another app (only matters with Your Music as the source).

Engine parity (no visible change):

- Summary, insights, search, mixes, Tracks Radio and Last.fm still run in Swift, as on
  iPhone. See the iPhone document.

## Platform-only

- **Menu bar extra, Dock icon choice, Open at Login, Watchdog**: Mac app conventions. A
  phone app has none of them.
- **Sortable table with inspector, menu commands and keyboard shortcuts, drag to Finder**:
  desktop interaction. Android tablets and Chromebooks could use keyboard shortcuts later,
  but they aren't parity.
- **Mini Player and Stage as windows**: Stage exists on iPhone too, from Now Playing.
- **Week-by-hour heat map**: shown where there's room. The iPhone could have it (listed there
  as missing).
- **Music.app scripting, AirPlay speakers through Music, the Core Audio tap, Play Back
  Automatically**: these exist because Music.app is the player on the Mac.
- **CarPlay, Louder at Speed, driving, Shake to Play, Action button, Focus filter
  surfaces, Background App Refresh**: phone-only surfaces. (The Focus filter intent is
  shared code, but Focus filters are most useful on iPhone.)
