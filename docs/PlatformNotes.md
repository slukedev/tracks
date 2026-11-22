# Platform notes

What Apple's frameworks actually do, as measured while building Tracks, where it differs from
what you'd expect or isn't documented. Code comments point here. If you measure something new,
add it, with the platform and OS version you saw it on.

## What Apple counts as a play

- Songs heard on an Apple Music radio station don't count as plays in Music. That's why
  Tracks keeps them itself and adds them to "Heard on Radio".
- `ApplicationMusicPlayer`, which Tracks plays Apple Music with, adds to the listening history
  behind Recently Played and Replay but never moves the play count in Get Info. Measured on
  macOS with both the catalog song and the library copy.
- On iOS, `SystemMusicPlayer` hands the queue to Music, so a play there moves the play count
  too. macOS has no `SystemMusicPlayer`; Music itself moves the count within seconds
  (`MusicAppPlaybackService`). Both are only for Play Songs In set to Apple Music, and Tracks
  doesn't record what they play.

## Telling radio from on demand

No API says a song came from a station. Tracks only records what its own players play, and
it queued the music itself, so the player says (`TracksPlayerContext`).

Earlier versions recorded other apps too, and had to guess. On iOS 27 a queue entry's
identifier is `<queue>::<item>`, with the item half `STREAM` for radio. On the Mac a station
track reports no duration in Music's `playerInfo` notification. Both platforms name a station
only when you tune in.

## Catalog IDs and artwork

- Tracks' Apple Music player gives each song's catalog ID. A radio song's cover there is a
  `musicKit://` address that never loads (see `ArtworkURL`), so covers come from the catalog.
- Rows from older Mac versions, which had no ID, and your own files are searched for by title
  and artist, through the strict matcher.

## History from earlier versions

- Songs recovered from Recently Played have no real date: they carry the time Tracks found
  them, and merge with a witnessed play of the same song up to a day apart.

## Scripting Music on the Mac

- A sandboxed app names the library as `library playlist 1 of source 1`. The shorter
  `library playlist 1` is the same playlist, but Music refuses it with -10004
  (`errAEPrivilegeError`) from a sandboxed app.
- -10004 means the script reached outside the access groups in the scripting-targets
  entitlement. Automation being turned off is a different error, -1743.
- A script run from Terminal isn't sandboxed, so it can't reproduce -10004. Test in the app.

## Music's transport controls on the Mac

- On a live radio station, `next track`, `previous track` and `back track` return no error
  and do nothing. Only `playpause` works. Only tested on a live broadcast; an algorithmic
  station might accept `next track`.
- Delivered isn't done. To know a skip worked, compare `database ID of current track` before
  and after.
- Addressing Music when it isn't running launches it, so check first.

## HomePod and AirPlay

What a third-party app can do with HomePods, as of iOS 27 and macOS 27. Read from the SDK and
Music's scripting dictionary; the device-only items are marked untested.

- **Mac, Music's speakers.** Music's `AirPlay device` elements and `current AirPlay devices`
  are in the `com.apple.Music.playback` access group (read and write), which the
  scripting-targets entitlement already holds. Each device has `name`, `kind` (`computer`,
  `HomePod`, `Apple TV`, `AirPlay device`, `Bluetooth device`, `TV`, `AirPort Express`),
  `available`, `supports audio`, `selected` (settable, which groups rooms) and
  `sound volume` (0 to 100, settable). Read them as lists, `name of every AirPlay device`,
  one Apple event per property, not per device. Setting `current AirPlay devices` to a list
  plays to those alone. This only moves what Music.app plays. Untested on a sandboxed build
  against a real HomePod.
- **Mac, Tracks' own player.** `ApplicationMusicPlayer` plays inside Tracks, and
  `AVRoutePickerView` without a `player` was reported to leave it on the Mac's speakers on
  macOS 14 (FB13521393, developer forums thread 744128). There is no public API to pick an
  AirPlay output for it. The Mac's sound output in Control Center does move it.
- **iPhone.** No public API chooses a route; only the system's `AVRoutePickerView` can.
  `AVAudioSession.currentRoute.outputs` names the AirPlay receivers (`portType == .airPlay`,
  `portName` is the room) and `routeChangeNotification` says when it changes, so nothing has
  to poll. A HomePod's kind isn't exposed, only its name. Tracks' session is only long-form
  (`.longFormAudio`) while your own music plays; whether the Apple Music player's route
  shows in the app's session otherwise is untested on a device.
- **`AVRouteDetector`** says whether there's anywhere besides the phone to play. Apple says
  detection "significantly increases power consumption", so Tracks enables it only while Your
  Devices is open.
- **Now Playing, Control Center, a HomePod's own controls and Handoff** to a HomePod come
  from `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`. `ApplicationMusicPlayer` fills
  both itself; the local player sets them in `LocalPlayerEngine.activate()`. iOS 26.4 fixed
  the route picker showing "Not Playing" for MusicKit players (forum thread 815154).
- **Siri on a HomePod** ("play X on Tracks") needs SiriKit Media Intents with a cloud
  extension and Media Setup, which is a program Apple admits services to. Not available to
  Tracks.

## SwiftUI on the Mac

- A `MenuBarExtra` with the `.window` style sizes itself to its content, and a `ScrollView`
  has no height of its own, so a scrolling list inside one collapses to nothing. Give the
  window a fixed width and the scrolling part a definite height.

## Notification observers and the main thread

Measured from TestFlight crash reports on iOS 27.2, November 2026.

- `NotificationCenter.addObserver(forName:object:queue:using:)` with `queue: .main` makes the
  posting thread wait until the main thread has run the block. A notification posted from a
  framework's private queue therefore blocks that queue on the main thread.
- MusicKit's first call to the system player (`SystemMusicPlayer.shared`, then
  `nowPlayingAudioFormat`) waits on the main thread for iTunesCloud's account queue. That
  queue writes iTunesCloud's own `UserDefaults` while it starts and posts
  `UserDefaults.didChangeNotification`. An observer of that name with `object: nil` and
  `queue: .main` closes the loop: each waits for the other, and the scene-update watchdog
  kills the app after 10 seconds (0x8BADF00D). CarPlay relaunched Tracks into the same
  deadlock four times in a minute.
- So: observe with `queue: nil` and hop with `Task { @MainActor in ... }` for anything a
  framework can post off the main thread (Core Data and CloudKit changes, iCloud key-value
  changes, `UserDefaults`, audio session, media library, power state). `queue: .main` is only
  for notifications UIKit or AppKit post on the main thread themselves (scene and window
  changes). Always pass the `object` you mean; `UserDefaults.didChangeNotification` with
  `object: nil` hears Apple's frameworks' defaults too.
- The Xcode debugger turns the watchdog off, so this never shows when running from Xcode.
  Test CarPlay connects and lock/unlock with the app launched from the Home Screen.

## CarPlay

Learned fixing Apple Music in the car, November 2026, iOS 27.2.

- Every push, pop, sheet and Now Playing goes through `CarMover`, one at a time. CarPlay
  raises an exception when a template already on the stack, or still on its way, is pushed
  again, and over wireless CarPlay a transition can take seconds or never report back. A move
  that never reports back frees the car after 15 seconds without sending the same template
  twice.
- A row's handler must call its completion, or the car spins the row forever. `RowCompletion`
  calls it once, when the play is queued or after 8 seconds at the latest.
- Wrap a tap's play in `PlayStart.run(mayAskForAccess: false, whenQueued:)`. The car can't
  show MusicKit's access prompt (the phone is usually locked), and `whenQueued` brings up Now
  Playing the moment the queue is set, before catalog lookups or `prepareToPlay` finish.
- Nothing slow comes before the queue: the subscription is cached, every catalog lookup has a
  time limit, and explicit versions get 1.5 seconds.
- Only one engine owns Now Playing. When Apple Music takes over, the local engine clears
  `nowPlayingInfo` and its remote command targets but leaves the audio session active.
  Otherwise CarPlay can stick to the stale entry and send play to the empty player
  (FB24840951).
- Car copy never tells the driver to use the iPhone, Settings or another app; Apple's CarPlay
  Developer Guide forbids it. `CarProblem` maps every player problem to car copy.
- Each tab draws its local content in one pass first, then adds shelves as they finish, and
  rebuilds when the car starts or stops limiting lists (`CPSessionConfiguration`).
