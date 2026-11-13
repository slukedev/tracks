# Tracks for Android

Your own music server, played and remembered. Tracks on Android plays the music on a
Subsonic-compatible server (Navidrome, Gonic, Airsonic and others) and keeps statistics of
what you play in it: listening time, top songs, artists and albums, streaks and highlights.
The statistics come only from what Tracks plays.

It's a native Material 3 app built with [Skip](https://skip.dev) Fuse: SwiftUI that is
compiled for Android and drawn with Jetpack Compose. Every number comes from the same
`StatsCalculator` as the iPhone and Mac apps.

## Layout

| Folder                    | What's in it                                                              |
| ------------------------- | ------------------------------------------------------------------------- |
| `Sources/TracksStats`      | The statistics engine and the play log. `Shared` links to TracksCore files |
| `Sources/Tracks`           | The app: SwiftUI screens, drawn as Material 3 on Android                  |
| `Sources/Tracks/Skip`      | Hand-written Compose: `MaterialExpressive.kt`, and the module's Gradle deps |
| `Android/app/src/main`    | The manifest, launcher icons, `PlaybackService.kt` and `SecretStore.kt`   |
| `Tests/TracksStatsTests`   | Tests for the Android-only parts of TracksStats                            |

`Sources/TracksStats/Shared` is symbolic links to the TracksCore files that need nothing but
Foundation, so there's one copy of the statistics. A TracksCore file added there must stay free
of SwiftData, CloudKit, MusicKit and `URL.applicationSupportDirectory`.

How it fits together:

- **Playing.** `PlaybackService.kt` runs Media3's ExoPlayer in a media session service, so
  music, the notification, the lock screen and headphone controls carry on with Tracks closed.
  `PlayerModel.swift` sends it a queue and reads its state through Skip's `AnyDynamicObject`.
- **Counting.** The service writes each song that has played long enough to
  `pending-listens.jsonl`. The app takes them every few seconds, keeps those TracksCore's
  `ScrobblePolicy` counts (half the song or 4 minutes), and scrobbles them to the server.
- **Keeping.** Plays are a JSON file (`PlayLog`) in the app's private storage. Covers are
  stored as `subsonic-cover:` references, never as addresses, which carry the server's token.
  The server's password is AES-GCM encrypted with a key in the Android Keystore
  (`SecretStore.kt`, reached through `Secrets.swift`), and kept out of backups, which couldn't
  decrypt it anyway.
- **Design.** Material 3 throughout: dynamic colour from the wallpaper, the M3 type and shape
  scales (`Design/Tokens.swift`), Material Symbols (`Design/Symbol.swift`) and colour roles
  (`Design/Material.swift`), reached through small Kotlin `ContentModifier`s.
- **Expressive.** The player and browsing use Material 3 Expressive, as Android 16's own apps
  do: Now Playing, the mini player and album pages take their colours from the cover; the
  cover morphs from a rounded square (paused) to a turning scalloped disc (playing) and swipes
  to skip; the seek bar is Android 16's wave; buttons square off when pressed; artists are
  scalloped; Home has a hero carousel and Search a grid of shaped browse tiles. Compose
  Material 3 1.4 keeps most of Expressive internal, so `Skip/MaterialExpressive.kt` builds these
  from `graphics-shapes` and springs, and `Design/Expressive.swift` wraps them for SwiftUI.

## Building

Needs Skip (`brew install skiptools/skip/skip`), the Swift Android SDK
(`skip android sdk install`), JDK 21 and the Android SDK.

```bash
cd Android && JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle installDebug
```

Build through Gradle only. Running `skip android build` separately rewrites the plugin's
generated Kotlin, and the next Gradle build fails with unresolved references; delete
`.build/plugins/outputs` to recover.

The Swift tests run on the Mac:

```bash
swift test --build-system native
```

Debug builds have a *Sample listening* switch in Settings, for screenshots and for trying the
statistics without a history.

## Skip pitfalls met so far

Each of these cost a crash or a stuck screen; the code notes them where they're avoided.

- `@State` can't be `private`, and neither can nested views.
- A bridged root view is made again on every recomposition: keep models out of its `@State`
  (see `AppModels`), or it redraws without end.
- `accessibilityAddTraits` with an empty set overflows the stack. Add traits only when they
  apply (`selectedTrait`).
- `AsyncImage(url:content:placeholder:)` never shows the image; use the phase form.
- `safeAreaInset`, `listRowInsets`, `contentShape` and `accessibilityElement(children:)` aren't
  available on Android. `Design/Platform.swift` has the stand-ins.
- A row of fixed-width views wider than its container makes Compose measure the container
  wider too, pushing content out of sight.
- Kotlin statics called from Swift belong in a class's `companion object`, not an `object`.
- Internal declarations in `#if SKIP` blocks are bridged unless marked `// SKIP @nobridge`.
- Hand-written Kotlin in `Sources/Tracks/Skip` is compiled with the transpiled files, in the same
  folder. Give it a name no Swift file has: `Skip/Expressive.kt` and `Design/Expressive.swift`
  both became `Expressive.kt`, one overwrote the other, and the app crashed at launch.
- A `#if SKIP` struct with a closure property needs an explicit `init` marking it `@escaping`,
  or the generated bridge doesn't compile.
- Compose Material 3 1.4 keeps `MotionScheme`, `MaterialShapes`, the emphasized type styles and
  Expressive's components internal. Kotlin can't implement `MotionScheme` either.
- Android blocks plain-http loads (covers, streams) unless `usesCleartextTraffic` is on. Swift's
  own networking ignores it, so sign-in can work while nothing plays.
