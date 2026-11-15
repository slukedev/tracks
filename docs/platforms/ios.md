# iPhone

The iOS app (`Apps/iOS`, with most screens in `Apps/Shared`), its widget extension
(`Widgets/`), CarPlay (`Apps/iOS/CarPlay`), App Intents (`Apps/Shared/Intents`,
`Apps/Shared/Play/Siri`), SharePlay (`Apps/iOS/Play/SharePlay`) and the App Clip
(`Apps/Clip`). Logic shared with the Mac lives in `TracksCore/`. iPhone only: the target sets
`TARGETED_DEVICE_FAMILY` to 1, so there is no iPad layout.

See [README.md](README.md) for the legend and how to keep this current.

## Complete features

### History capture

- Keeps every song played inside Tracks, from Apple Music or your own music, even in the
  background. Songs played in other apps, the Music app included, aren't kept.
- Knows radio from on demand because Tracks' player queued it, and keeps radio songs Apple
  Music doesn't count as plays.
- Counts After (how much of a song must play) and Same Song Within, in Settings › History.
- Keep On-Demand Songs, or radio only.
- Pause History: nothing is kept, counted, scrobbled or reported to your server until you resume.
- Exclude stations you don't want recorded.
- Heard on Radio: radio songs added to a playlist in your Apple Music library.
- Imports your Last.fm history, from any app you scrobbled from, in Settings › Last.fm.
- Repair Artwork finds covers that stopped loading and fetches them again.
- Sample data with `-TracksDemoData YES` in Debug builds, never saved, synced or scrobbled.

### Insights (Summary)

- Listening time for the week, month, year or all time, compared with the same point last
  period, with a bar for every day and a daily average.
- Swipe the charts sideways, or step with the arrows, to earlier periods; tap a bar to read it.
- Glance numbers under the listening card.
- Highlights: short sentences about the period, with See All.
- Listening clock (24 hours), weekday and weekend averages, time of day and sessions.
- Running total against last period, and how much was new (Discovery).
- Variety: your mix of artists, one-off songs, deepest dive into one artist.
- Top Genres and Decades, with median release year and oldest song.
- Records: biggest day, longest session, earliest and latest listens.
- Streaks and new favorites.
- Top songs, artists and albums for the period, with a mix card for a top artist.
- Apple Music and Your Music counted together or apart.
- Search sits at the top of Summary.

### Charts

- Top songs, artists and albums for any day, week, month, year or all time.
- Movement since the last period.
- Songs show when you listened, bar by bar; a bar opens that day.
- Artists show who your listening went to; albums show the one you went deepest into.
- Play a chart.
- Charts read from the Rust engine.

### History list

- Every play, newest first, grouped by day, paged so it opens fast with any history size.
- Filters: All Songs, Radio, On Demand, Recovered, From Last.fm; and by source.
- Swipe to delete, and a context menu with the song's actions.

### Detail pages

- Artist, album and song pages: plays over time, rank, genre, release years, first and last
  heard.
- How You Heard It: on demand, radio or recovered, and which stations.
- About the artist (Apple Music's editorial note, genre, when you first heard them).
- About the song: release date, genre, writer, length, mastering, whose recording.
- Get Info sheet: your history with the song, MusicBrainz credits and samples, Last.fm and
  Apple Music related songs.
- Tap any album anywhere for its page.

### Search

- Search across everything you've played, by song, artist, album, genre or date.
- Remembered searches understood in words: "Last Night", "September", "On the Radio · 1990s ·
  Chill".
- Scopes: Apple Music, Your Library, History; leads with the best match.
- Opens on your recent searches and every mood.
- Asks your music servers as you type, including songs a server finds elsewhere, and songs
  Trackside could get.
- Identify Song: names the song playing nearby with Shazam and finds it in your history.

### Play, mixes, Tracks Radio, moods and suggestions

- Play tab plays Apple Music inside Tracks: library, live radio, your stations, albums,
  playlists and the catalog.
- The crate: Tracks Radio in front, this hour's mix, Discover and the day's mixes to flip
  through, glowing with the front record's colour.
- Mixes from your history: time of day, On Repeat, New Finds, All-Time Favorites, Deep Cuts,
  Radio Finds, Rediscover, a year ago this week. Each says why it holds what it holds.
- Save any mix to your library as a playlist.
- Songs you skip early twice, or ask to hear less of, drop out of mixes; Reset Mix Suggestions
  in Settings.
- Shuffle keeps each artist apart.
- Tracks Radio: an endless station picked a song at a time from what you love and new finds.
- Tune Tracks Radio: how adventurous, which genres, whether old favorites return; or turn it off.
- Through the Day: Tracks Radio's energy follows the time of day, editable on a curve.
- Notices driving (motion or CarPlay) and plays for the road.
- Learns which songs go together from what you play in one sitting ("Because you played").
- Autoplay continues after an album or playlist ends.
- Suggested Songs and Suggested Artists, endless.
- Find Songs: a pile to flip through by lens (your artists, artists like them, new releases,
  popular, a mood); play, keep or pass.
- Find Artists: a feed of stories by genre.
- Keep Exploring at the foot of Play.
- For You cards: what you play at this time of day, mixes for the rest of the day.
- Your Artists shelf with play counts.
- New from Your Artists, with upcoming releases; Apple Music's picks and Top Charts.
- Find Your Mood: Feel Good, Energy, Workout, Focus, Chill, Love, Heartbreak, Party, Sleep.
- Genres shelf, each a station.
- Handpicked: a one-time station from songs you pick.
- Party: pick the kind of party and get playlists and a mix from your own songs.
- Edit Play: reorder or hide its sections.
- Shake to Play: shake for a song you haven't heard, then Tracks Radio.
- Quick Switch between Apple Music and Your Music, with a Focus filter.
- Offline Mode: downloaded Apple Music songs, their mixes and most played, all offline.
- Play counts beside songs on albums, playlists and in search.
- Play Next, Play Last and Create Station everywhere.
- Play Songs In: Tracks (so plays are kept) or Apple Music.
- Explicit Songs on or off.
- Block an artist from their page or a song's menu: never played or suggested again; unblock in
  Settings. Past plays stay in the history.

### Now Playing

- Your play count for the song, and a ring that fills until the song is kept.
- Backgrounds: Full Cover, the artwork, Living Colour, Pulse; with crossfade, slide or bloom
  between songs.
- The back of the sleeve: tap the cover for your history with the song.
- Up Next: reorder, remove, jump; Tracks Radio shows just the next song.
- Sleep timer, AirPlay, system volume slider.
- Favorite star; hold it to add to a playlist.
- Audio quality badge (Lossless, Hi-Res, Dolby Atmos) with bit depth and sample rate.
- Song Transitions: none, or crossfade of 1 to 12 seconds.
- Pick Up Where You Left Off, for as long as you choose.
- Mini player above the tab bar; pull down to close Now Playing.
- Stage: the song on a screen of its own with a visualizer, for a TV or a party.
- Tune Tracks Radio from Up Next.

### Your Music

- Play FLAC, ALAC, MP3, AAC, WAV and AIFF: import songs or folders, open them in Tracks from
  anywhere, or drop them into On My iPhone › Tracks › Music.
- Tags, covers and folder names are read.
- Subsonic-compatible servers (Navidrome, Gonic, Airsonic, Ampache, LMS), streamed; passwords
  in the Keychain.
- A Subsonic server that finds songs you don't own: play them and get them. Tracks learns a
  server finds songs from its own search answers, and remembers it. A Trackside server says
  whether it gets songs for you in its `/me`, so it's never guessed.
- Get More, with a server that finds or gets songs, or Lidarr: songs and albums you don't own, from the artists you play
  and ones like them, with Get, a ring while they come in, and a tick when they're here. Also
  on artist pages (Your Music, server, Apple Music and history), album pages (Missing, Get
  All), search and Downloads (Requested). None of it shows without one of them.
- Download for Apple Music and Your Music together (Settings, off by default): in song and album menus, the album's toolbar and a song's swipe. Apple Music adds to the library and opens Music to download, since MusicKit can't download (downloaded state read from the media library).
- Picked for You, discovery, newest, most played, random albums and Keep Exploring per server.
- Tracks Radio, mixes, moods and "Waiting to Be Heard" from your own music.
- Downloads from your servers, as original files, in the background; cellular optional.
- Downloads page: storage, stats, most played, sorting, Free Up Space, Shuffle Downloads.
- Streaming quality on Wi-Fi and cellular, and download quality.
- Tracks Radio can start with downloaded songs and download new finds; Delete After Playing.
- Playlists: make, add, reorder, trim, download.
- Smart playlists by artist, album, genre, year, plays, last played, date added or quality.
- Merge playlists with Apple Music, off by default.
- Gapless albums; lock screen, Control Center, headphones, Siri and CarPlay all work.
- Tell Server About Plays.
- Suggestions checked against your files, servers and Lidarr: Everything, Only Music I Have,
  or Off.
- Lidarr: add artists, request albums, see downloading, wanted and coming soon.

### Scrobbling and Last.fm

- Connect a Last.fm account in a web sheet; the session key is kept in the Keychain.
- Scrobbles every song Tracks keeps, radio included.
- Sends Now Playing updates.
- Imports your Last.fm history from Settings, without asking first; Sync Now adds what you've
  scrobbled since, from any app. Progress shows in the row, and it picks up where it stopped.
- Scrobble queue with retries; scrobbling can be paused.

### Sync and backup

- History syncs through your private iCloud database (CloudKit), with the Mac.
- Settings and removed songs sync through the iCloud key-value store.
- Sync with iCloud can be turned off; Settings › iCloud shows what syncs and what iCloud said.

### Devices, Play On and SharePlay

- Your Devices: see what Tracks on your Mac is playing, control it, send music there or bring
  it here, over the local network with TLS keys shared through iCloud.
- Play On lists HomePods and AirPlay speakers beside your devices.
- Handoff of the song playing to your other devices.
- Play on Another Device and Bring Music Here as App Intents.
- SharePlay: passengers add songs to Up Next, invited in Messages or by a code to scan.
- Joining by code works nearby (Bonjour, peer-to-peer) or through an encrypted relay.
- Passengers with Tracks can add from Recently Played, Playlists and Recently Added.
- App Clip for passengers without Tracks: see what's playing, search Apple Music, add songs.
- Louder at Speed: with your own music in the car, the volume follows your speed. Off by
  default.

### CarPlay

- Listen Now: For You cards, your mixes, your week, then Apple Music's shelves.
- Radio with Tracks Radio, moods and genres.
- Library: Playlists, Artists, Albums, Songs, Downloaded, Recently Added.
- Search.
- Now Playing, with SharePlay at the top of Up Next: the code, who's joined, what they added.
- Needs Apple's CarPlay Audio entitlement on a device.

### Widgets, controls, Siri and system integration

- Widgets: Listening (small, medium, and Lock Screen rectangular, circular and inline), Top
  Songs (small, medium), Last Played (small), Today (medium, large).
- Controls for Control Center, the Lock Screen and the Action button: Play in Tracks
  (configurable mix or mood) and Identify Song.
- Siri: "Play music in Tracks" starts Tracks Radio; "Play my mix", "Play something new", mixes
  and moods by name; any artist, song, album, playlist or station.
- Siri can learn to use Tracks for a plain "Play music" (SiriKit media intent).
- App Shortcuts: Tracks Radio, My Mix, Top Artist, Search, Play a Mix, Play Music, Switch
  Source, Play on Device, Bring Music Here.
- Get Top Artist for a period, answered by Siri.
- Open Artist, Open Song and Open Album intents on indexed entities.
- Spotlight indexes the artists, songs and albums you've played.
- Focus filter picks a music source per Focus.
- Deep links (`tracks://summary`, `history`, `song`) from widgets.
- Lock screen and Control Center now playing, remote commands, headphones.

### Settings

- A list of pages, each opening with a line on how it's doing: Play, History, Radio, Apple
  Music, Last.fm, iCloud, Your Devices, Louder at Speed.
- Open To: Summary or Play.
- Apple Music access, which playing Apple Music needs, and audio quality.

### Accessibility

- VoiceOver labels and custom actions throughout; charts have audio graph descriptors.
- Adjustable actions on sliders and charts.
- Layouts that stack at accessibility text sizes.
- Reduce Motion respected by backgrounds, transitions and Settings animations.
- Differentiate Without Color hatches heard-before bars in Discovery.
- Haptics on selections, impacts and successes, such as a song identified.

### Privacy

- No Tracks account, no analytics.
- History on the device and in your private iCloud database.
- Last.fm only contacted if you connect; key in the Keychain.
- Identify Song sends only a signature of the sound, and listens only while showing.
- Your Devices only connects devices signed in to your iCloud.
- The SharePlay relay only passes sealed messages it can't read.

## Missing for parity

From the Mac:

- Week-by-hour heat map on Summary (the Mac draws it from the same activity data).
- A sortable history with more than one order, as the Mac's table has (the list is newest
  first only).
- Select and delete several plays at once.

From Android (planned there):

- Open songs you don't have in another app: hand off a history song with no playable copy.
  On iPhone, Apple Music plays the catalog, so this matters only with Your Music as the source.
- Theme choice inside the app (follows the system today; fine as is).

Engine parity (no visible change, same results on every platform):

- Summary, insights and search still run in Swift; the engine has totals, timeline,
  activity, streaks and search ready.
- Mixes, the fresh shuffle and Tracks Radio still run in Swift; `tracks-mixes` has the port
  of the mixes and shuffle, and of Tracks Radio over Your Music (`tracks_mixes::radio`, which
  Android plays). Tracks Radio over Apple Music stays in Swift with the MusicKit catalog; the
  engine's radio could take over Your Music stations only.
- Last.fm still uses the Swift client and `ScrobbleService`; `tracks-lastfm` has requests,
  parsing, policy and the queue.
- The widgets read SwiftData, not the shared index.

## Platform-only

- **CarPlay**: an Apple car platform. Android's equivalent is Android Auto, tracked there.
- **SharePlay and the App Clip**: GroupActivities and App Clips are Apple's. An Android
  guest could join by code through the same relay one day, but that is new work, not parity.
- **Heard on Radio, Offline Mode, Merge with Apple Music, Explicit Songs**: they exist because
  of how Apple Music works.
- **Louder at Speed, driving detection, Shake to Play**: phone sensors in a car or a hand.
  Android could have them; they aren't needed on the Mac.
- **Lock Screen widget families, Action button, Focus filter**: iOS system surfaces.
- **Siri media intent ("Siri can learn to use Tracks")**: SiriKit is iOS only.

## Where the README and the code disagree

- The README lists three widgets (Listening, Last Played, Today). The code also ships **Top
  Songs** (`Widgets/Shared/TopSongsWidget.swift`).
- The README sums up the Siri and Shortcuts actions. The code has nine App Shortcuts
  (`Apps/Shared/Intents/TracksShortcuts.swift`) plus Open Artist, Song and Album, Spotlight
  indexing and a Focus filter.
- **Identify Song** (Shazam), **Stage**, **Handpicked**, **Party** and **Get Info** with
  MusicBrainz credits aren't in the README; Stage, Handpicked and Party aren't in the
  changelog either.
