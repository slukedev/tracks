# Platforms

One document per platform, listing what Tracks does there today and what it still needs to
match the others. They exist so feature parity is something we can see and plan, not
something we rediscover by reading code.

- [iPhone](ios.md): the iOS app, CarPlay, widgets, controls, Siri and App Intents, SharePlay
  and its App Clip.
- [Mac](macos.md): the Mac app, the menu bar extra, the Mini Player, Stage, widgets and the
  Settings panes.
- [Android](android.md): the new Android app in `Android/` (Kotlin, Compose, Material 3
  Expressive), being built now.

Each platform document has three parts:

1. **Complete features**, grouped by area, one plain line each.
2. **Missing for parity**: what another platform has that this one lacks, or the
   platform's own equivalent it should get (Android Auto for CarPlay, a Quick Settings tile
   for a Control Center control).
3. **Platform-only**: features that don't need parity, and why.

The lists were drawn from the code, not only from the top-level README or the changelog.
Where the two disagree, the code wins and the document says so.

## Keeping these current

- Update the list in the same change that adds, removes or changes a feature. A pull
  request that ships a feature on one platform moves its row in this matrix and its line in
  that platform's document; if it opens a gap, it adds the gap to the other platforms'
  "Missing for parity".
- When a feature reaches parity, delete it from "Missing for parity" rather than striking
  it through.
- When logic moves into the Rust engine, update the "Engine (shared)" column and the engine
  section below.
- Keep each item to one line in plain language: what a person can do, not how it's built.
  Name the file or type only when it helps someone find the code.
- An Android "Yes" means built and covered by unit and screenshot tests. Paths that only a
  device can prove are flagged in android.md.

## Status legend

| Status | Meaning |
| --- | --- |
| Yes | Shipped and working on this platform. |
| Partial | Shipped, but missing part of what another platform does. The platform document says what. |
| In progress | Being built now, or declared but not yet implemented. |
| No | Missing. Listed under "Missing for parity" in that platform's document. |
| n/a | Doesn't apply on this platform, or has no sensible equivalent. Listed under "Platform-only" where it matters. |

For the "Engine (shared)" column:

| Value | Meaning |
| --- | --- |
| Engine | The logic lives in the Rust engine (`Engine/`) and every platform that has the feature uses it. |
| Engine, Apple in Swift | The engine has it and Android uses it; the Apple apps still run their own Swift version. |
| Platform | Written per platform, natively. Usually because it is system integration. |
| Candidate | Written per platform today, but the logic is the same everywhere and should move into the engine. |

## Parity matrix

As of November 2026, on `feat/android-m3-expressive`.

### History capture

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Keep songs played inside Tracks | Yes | Yes | Yes | Platform |
| Keep songs played in other apps | Removed | Removed | Removed | Platform |
| Tell radio from on demand | Yes (the player says) | Yes (the player says) | n/a | Platform |
| Keep threshold, repeat window, keep on demand or radio only | Yes | Yes | No | Candidate (`CapturePolicy`) |
| Pause History | Yes | Yes | No | Platform |
| Exclude stations or apps | Yes (stations) | Yes (stations) | Removed | Platform |
| Heard on Radio playlist | Yes | Yes | n/a | Platform |
| Repair Artwork | Yes | Yes | No | Platform |
| Sample data | Yes (Debug launch argument) | Yes (Debug launch argument) | Yes (setting) | Engine (`tracks-demo`) |

### Insights and charts

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Listening time by period, against last period, daily bars | Yes | Yes | Yes | Engine, Apple in Swift (totals, timeline) |
| Swipe or step through earlier periods | Yes | Yes | Yes | Platform |
| Listening clock, weekday and weekend rhythm | Yes | Yes | No | Engine, Apple in Swift (activity) |
| Week-by-hour heat map | No | Yes | No | Engine, Apple in Swift (activity) |
| Streaks | Yes | Yes | Yes | Engine, Apple in Swift |
| Highlights in plain language | Yes | Yes | No | Candidate (`StatsCalculator+Insights`) |
| Records, sessions, running total, variety, discovery | Yes | Yes | No | Candidate |
| Top genres and decades | Yes | Yes | No | Candidate (needs metadata) |
| Separate Apple Music and Your Music stats | Yes | Yes | No | Engine (source filter) |
| Top songs, artists and albums for any day, week, month, year or all time | Yes | Yes | Yes | Engine |
| Movement since last period | Yes | Yes | Yes | Engine |
| Per-song bars and play the chart | Yes | Yes | No | Engine (timeline) |

### History list, detail pages and search

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| History grouped by day, paged | Yes | n/a (table) | Yes | Platform |
| Sortable history table with inspector | n/a | Yes | n/a | Platform |
| Filters: radio, on demand, recovered, from Last.fm | Yes | Yes | Partial (Tracks, Last.fm) | Platform |
| Delete plays (swipe or menu; several at once on the Mac) | Partial (one at a time) | Yes | Yes | Platform |
| Artist, album and song pages: plays over time, rank, first and last heard | Yes | Yes | Yes | Engine (song, artist, album, rank) |
| How you heard it (radio, on demand, recovered, stations) | Yes | Yes | No | Platform |
| About the artist and song (editorial note, genre, credits) | Yes | Yes | No | Platform (MusicKit) |
| Get Info with MusicBrainz credits, samples and Last.fm similar songs | Yes | Yes (window) | No | Candidate (MusicBrainz client) |
| Search your history | Yes | Yes | Yes | Engine, Apple in Swift |
| Remembered phrases ("last night", "on the radio", a decade) | Yes | Yes | No | Candidate (`HistoryRecall`) |
| Search the catalog, your library and your servers | Yes | Yes | Yes (library, servers) | Platform |
| Identify the song playing nearby (Shazam) | Yes | Yes | No | Platform |

### Play

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Play Apple Music inside Tracks | Yes | Yes | n/a | Platform |
| Mixes from history (time of day, On Repeat, New Finds, All-Time Favorites, Deep Cuts, Radio Finds, Rediscover, A Year Ago) | Yes | Yes | Yes | Engine, Apple in Swift |
| Shuffle that keeps artists apart | Yes | Yes | Yes | Engine, Apple in Swift |
| Tracks Radio | Yes | Yes | Yes | Engine, Apple in Swift (picker core) |
| Tune Tracks Radio (adventure, genres, old favorites, Through the Day) | Yes | Yes | Partial (adventure only) | Candidate |
| Radio follows driving | Yes | n/a | No | Platform (sensing), Candidate (`DriveSense`) |
| Hear less of, skip learning | Yes | Yes | Yes | Candidate (`ListeningSignals`, `TasteGraph`) |
| Save a mix as a playlist | Yes | Yes | No | Platform |
| Suggested Songs and Artists, Find Songs, Find Artists, Keep Exploring | Yes | Yes | No | Candidate (`SuggestionWalk`) |
| Moods (Feel Good, Energy, Chill and more) | Yes | Yes | No | Candidate (`Mood`, `LiveMix`) |
| Genres shelf and stations | Yes | Yes | No | Candidate |
| Handpicked stations | Yes | Yes | No | Candidate (`Handpicked`) |
| Party | Yes | Yes | No | Candidate (`PartyMix`) |
| New from Your Artists | Yes | Yes | No | Platform (catalog) |
| Your Artists shelf | Yes | Yes | No | Engine (artist chart) |
| Edit Play's sections | Yes | Yes | No | Platform |
| Shake to Play | Yes | n/a | No | Platform |
| Quick Switch between sources, Focus filter | Yes | Yes | n/a | Platform |
| Offline Mode for downloaded Apple Music | Yes | No | n/a | Platform |
| Open songs you don't have in another app | No | No | Yes | Platform |

### Now Playing

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Play count and keep ring | Yes | Yes | Yes | Platform |
| Colors from the cover | Yes | Yes | Yes | Platform |
| Moving backgrounds (Full Cover, Living Colour, Pulse) | Yes | Yes | No | Platform |
| Back of the sleeve (your history with the song) | Yes | Yes | No | Engine (song stats) |
| Up Next: reorder, remove, jump | Yes | Yes | Yes | Platform |
| Sleep timer | Yes | Yes | Yes | Platform |
| Output switcher, AirPlay | Yes | Yes | Yes | Platform |
| Mini player | Yes (bar) | Yes (window and bar) | Yes (floating) | Platform |
| Stage (full-screen visualizer) | Yes | Yes (window) | No | Platform |
| Audio quality badge | Yes | Yes | No | Platform |
| Crossfade | Yes | No | No | Platform |
| Gapless albums | Yes | Yes | No | Platform |
| Pick Up Where You Left Off | Yes | Yes | No | Platform |
| Favorite and add to playlist from the player | Yes | Yes | No | Platform |
| Explicit songs setting | Yes | Yes | n/a | Platform |
| Block an artist from every mix and suggestion | Yes | Yes | No | Candidate (`BlockedArtists`) |

### Your Music

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Play your own files (FLAC, ALAC, MP3, AAC, WAV, AIFF) | Yes | Yes | Yes (MediaStore) | Platform |
| Subsonic servers (Navidrome and others) | Yes | Yes | Yes | Candidate (`SubsonicClient`) |
| Servers that find songs: find and fetch songs you don't own | Yes | Yes | No | Engine (`search_shows_finds`, routes); requests Candidate |
| Get More: songs and albums you don't own, from what you play | Yes | Yes | No | Engine (`tracks-library` ranking) |
| Albums marked owned or partly owned, and their missing songs | Yes | Yes | No | Engine (`OwnedLibrary`) |
| Downloads for offline, Downloads page, Free Up Space | Yes | Yes | No | Platform |
| Playlists and smart playlists | Yes | Yes | No | Candidate (`SmartRules`) |
| Merge playlists with Apple Music | Yes | Yes | n/a | Platform |
| Suggestions checked against what you have | Yes | Yes | No | Candidate (`ServerSongMatcher`) |
| Lidarr | Yes | Yes | No | Candidate (`LidarrClient`) |
| Tell the server about plays | Yes | Yes | No | Candidate |

### Scrobbling and Last.fm

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Connect a Last.fm account | Yes | Yes | Yes | Engine, Apple in Swift |
| Scrobble every kept song, radio included | Yes | Yes | Yes | Engine, Apple in Swift (requests, policy) |
| Now Playing updates | Yes | Yes | Yes | Engine, Apple in Swift |
| Import Last.fm history, Sync Now | Yes | Yes | Yes | Engine, Apple in Swift |
| Scrobble queue with retries | Yes | Yes | Yes | Engine (queue rules, backoff), Apple in Swift |

### Sync and backup

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| History sync between devices | Yes (iCloud) | Yes (iCloud) | No | Platform |
| Settings sync | Yes (iCloud key-value) | Yes (iCloud key-value) | No | Platform |
| System backup | Yes (iCloud) | Yes (iCloud) | Yes (Auto Backup, device transfer) | Platform |
| Sync between Android and Apple devices | No | No | No | Candidate (needs a design) |

### Devices, Play On and SharePlay

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Your Devices: see, control and move music between your devices | Yes | Yes | No | Platform (`NearbyMessage` could move) |
| HomePods and AirPlay speakers in Play On | Yes | Yes | n/a (Cast, output switcher) | Platform |
| Handoff | Yes | Yes | n/a | Platform |
| SharePlay, invite in Messages or by code | Yes | No | No | Platform |
| Join by code without the app (App Clip) | Yes | n/a | No | Platform |
| Louder at Speed | Yes | n/a | No | Platform |

### Widgets, shortcuts and system integration

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Listening widget | Yes (incl. Lock Screen) | Yes | Yes | Platform |
| Last Played widget | Yes | Yes | Yes | Platform |
| Top Songs widget | Yes | Yes | No | Platform |
| Today widget | Yes | Yes | No | Platform |
| Play in Tracks control or tile | Yes (Control Center, Lock Screen, Action button) | Yes (menu bar controls) | Yes (Quick Settings tile) | Platform |
| Identify Song control | Yes | Yes | No | Platform |
| Voice: play music, mixes, moods, anything by name | Yes (Siri) | Yes (Siri) | Yes (Assistant) | Platform |
| App Shortcuts and actions (top artist, search, switch source, play on device) | Yes | Yes | Partial (static and dynamic shortcuts) | Platform |
| System search indexing (Spotlight) | Yes | Yes | No | Platform |
| Car | Yes (CarPlay) | n/a | Yes (Android Auto) | Platform |
| Media controls on lock screen, headphones, notification | Yes | Yes (menu bar, Dock, media keys) | Yes | Platform |
| Menu bar extra | n/a | Yes | n/a | Platform |

### Settings, accessibility and privacy

| Feature | iPhone | Mac | Android | Engine (shared) |
| --- | --- | --- | --- | --- |
| Settings with a status line on each page | Yes | Yes | Yes | Platform |
| Onboarding | Partial (permission prompts in context) | Partial | Yes | Platform |
| Choose where the app opens | Yes | Yes | No | Platform |
| App theme and color choice | n/a (follows system) | n/a | Yes (Material You) | Platform |
| Screen reader labels and actions | Yes | Yes | Partial | Platform |
| Large text layouts | Yes | Yes | Partial | Platform |
| Reduce Motion | Yes | Yes | No | Platform |
| Differentiate Without Color in charts | Yes | Yes | No | Platform |
| Haptics | Yes | n/a | Yes | Platform |
| No account, no analytics | Yes | Yes | Yes | n/a |
| Secrets in the system keystore | Yes (Keychain) | Yes (Keychain) | No | Platform |
| Encrypted local network between your devices | Yes | Yes | No | Platform |

## The engine today

What every platform already shares through `Engine/` (see `Engine/README.md`):

- **History index** (`tracks-store`): the SQLite mirror of the history, with running totals,
  and every chart, total, timeline, activity, streak and search read from it, plus per song,
  artist and album stats and rank.
- **Names** (`tracks-model`): name folding identical to Foundation's, song and album
  identities, artist credits ("A feat. B"), search folding.
- **Last.fm** (`tracks-lastfm`): request building and signing, response parsing, the
  scrobble policy, the queue rules and backoff, history import pages and track info. Sans-IO:
  each platform sends the requests with its own HTTP stack.
- **Mixes** (`tracks-mixes`): the history mixes, the fresh shuffle that keeps artists apart,
  daily seeds, and Tracks Radio's picker (`RadioStation`).
- **Sample data** (`tracks-demo`): the invented library and listening history.
- **Your library and what to get** (`tracks-library`): what you own of an album and which
  songs are missing, Get More's ranking, when a server finds songs it doesn't have, and
  whether to get something from it or from Lidarr.

How much each app uses it:

- The Apple apps read Charts (songs, artists, albums) from the engine and keep it in step
  with SwiftData (`TracksCore/Sources/TracksCore/Engine/HistoryEngine.swift`). Summary,
  search, mixes, Tracks Radio and Last.fm still run in Swift. Moving them to the engine is
  internal parity work: it changes nothing a person sees, but keeps behaviour identical.
- Android uses the engine for everything it can: the index, every chart and total, search,
  mixes, radio, Last.fm and sample data.
- `Engine/README.md` doesn't yet list `tracks-mixes`, and its Status section still says mixes
  and Tracks Radio are to move next. The crate and its bindings exist
  (`Engine/crates/tracks-ffi/src/mixes.rs`), so the README is behind the code.

## Candidates for the engine next

Logic that is the same on every platform, written in Swift today, that Android would
otherwise have to write again:

- **Insights**: highlights, records, sessions, variety, discovery, decades and genres
  (`StatsCalculator+Insights`, `+Profiles`, `+Genres`, `Insight`).
- **Remembered search**: the phrase parser behind "last night" and "on the radio · 1990s"
  (`HistoryRecall`, `SearchRanking`).
- **Capture policy**: keep thresholds, the repeat window, passed-over songs
  (`CapturePolicy`, `CaptureDecision`, `PassedOverSongs`).
- **Steering**: moods, live mixes, listening signals, the taste graph, Handpicked, Party,
  Through the Day and driving profiles (`Mood`, `LiveMix`, `ListeningSignals`, `TasteGraph`,
  `Handpicked`, `PartyMix`, `RadioDay`, `RadioMoment`, `DriveSense`).
- **Suggestion walks**: Suggested Songs and Artists, Keep Exploring (`SuggestionWalk`,
  `SuggestionPaging`).
- **Subsonic client**: requests and parsing, the request budget, server mixes and song
  matching (`SubsonicClient`, `RequestBudget`, `ServerMix`, `ServerSongMatcher`), sans-IO like
  Last.fm.
- **Smart playlists**: the rules and ordering (`SmartRules`, `LibraryOrdering`).
- **Lidarr client** (`LidarrClient`): requests and parsing, sans-IO like Last.fm, with
  `LidarrClient.albumKey` (edition-blind album titles) and the album request flow. Android is
  getting a Lidarr client, so this is the next piece to share.
- **Get More's lookups**: which artists to ask about and how (`GetMore.loadSuggestions`,
  `gets(forArtist:)`, `gaps(in:)` in `Apps/Shared/YourMusic/GetMore.swift`). The ranking and
  ownership already live in `tracks-library`; the asking order, caching and request budget
  are still Swift.
- **MusicBrainz client**: credits and samples (`MusicBrainzClient`, `SongCredits`).
- **Scrobble service**: the Swift `ScrobbleService` on top of the Rust request builder and
  queue, so both platforms send the same scrobbles the same way.
- **Your Devices messages**: the wire format between devices (`NearbyMessage`), if Android
  is ever to see or control an Apple device, or the reverse.
- **SharePlay by code messages**: the sealed relay format (`SharePlayMessage`,
  `SharePlayRelay`), if an Android guest should ever join an iPhone's session.
