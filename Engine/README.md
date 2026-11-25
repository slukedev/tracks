# Tracks Engine

The cross-platform core of Tracks, in Rust. Each platform keeps its own native UI and system
integration (SwiftUI, MusicKit, CarPlay, widgets, CloudKit and Keychain on Apple; the same
roles on Android, Windows and Linux later) and calls the engine for everything that is the
same everywhere.

## Layout

| Crate | What it holds |
| --- | --- |
| `tracks-model` | Play kinds and sources, name folding (byte-for-byte Foundation's, checked against golden vectors produced by Foundation), song and album identities, artist credits ("A feat. B", "A & B"), search folding. Pure, no I/O. |
| `tracks-store` | The history index: SQLite with running totals, and every chart, total, timeline, activity, streak and search read from it. |
| `tracks-lastfm` | Last.fm requests, signing and response parsing, the scrobble queue rules and backoff. Sans-IO: the host sends the request with its own HTTP stack. |
| `tracks-catalog` | Music you don't have: iTunes catalog search and lookups, the Lidarr v1 client, matching catalog and server names to what you own, and ranking suggestions. Sans-IO like `tracks-lastfm`. |
| `tracks-mixes` | The Play tab's mixes, the artist-spread shuffle, what a music server finds for you, and Tracks Radio over Your Music. Sans-IO. |
| `tracks-demo` | The invented sample library (artists, albums, songs, collaborations) and a seeded, believable listening history over it, the same on every platform. Pure, no I/O. |
| `tracks-library` | Your own music against what you could get: whether an album is owned, partly owned or missing and which songs are missing (matching names loosely enough that Apple Music's, Last.fm's and a server's agree), Get More's ranking from the artists you play and ones like them, and the rules for when a server finds songs it doesn't have and whether to get something from it or from Lidarr. Pure, no I/O. |
| `tracks-spotify` | Spotify on [librespot](https://github.com/librespot-org/librespot): sign-in, a session that reconnects with backoff, metadata, the saved library and search, and decoded audio the host renders. Built only with `tracks-ffi`'s `spotify` feature. |
| `tracks-ffi` | The surface the apps see, generated with [UniFFI](https://mozilla.github.io/uniffi-rs/) (Swift and Kotlin). |
| `uniffi-bindgen` | The bindings generator. |

Dependencies are deliberately few and mature: `rusqlite` (bundled SQLite), `rusqlite_migration`,
`jiff` (time zones), `unicode-normalization`, `caseless`, `md-5`, `serde_json`, `rustc-hash`,
`thiserror`, `uniffi`. The `spotify` feature adds librespot 0.8 and what it brings: Tokio,
hyper with rustls and bundled web roots (no OpenSSL), and symphonia for decoding. No audio
backend is built; the apps render the PCM themselves.

## Decisions

- **The index mirrors the store; it is never the source of truth.** SwiftData and CloudKit keep
  the history and sync it. The index can be deleted at any time and refills itself, and a file
  that won't open is moved aside and rebuilt. Anything going wrong costs a rebuild, never a play.
  The Swift callers fall back to the Swift calculator if the engine is unavailable.
- **Cost follows what's shown, not how long the history is.** Plays are clustered by time
  (`WITHOUT ROWID`, primary key `(played_at, key)`), so a period is one contiguous read.
  All-time totals per song and album are kept current on every write, so all-time charts never
  scan plays. Lookups that only change when names do (credits, first plays, all-time artist and
  album charts) are cached per write and shared by every read until the next one.
- **Every write is idempotent.** A play is keyed by a stable hash of the host's id and carries a
  digest of its fields, so replaying a batch after a crash is a no-op and a launch-time sync
  writes only what changed.
- **Periods come from the host's calendar.** Swift works out weeks, months and years with its own
  `Calendar` (first weekday, locale, calendar system) and passes Unix milliseconds; the engine
  counts within them. Hours, weekdays and streaks use the IANA zone the host passes.
- **Native networking.** Last.fm requests are built and parsed in Rust and sent with URLSession
  (OkHttp on Android), keeping proxies, ATS, cellular rules and background sessions native and
  keeping a TLS stack out of the binary.
- **Synchronous, thread-safe API.** One writer and up to three reader connections over WAL, so
  reads never wait for an import. Hosts call it off their main thread.
- **Sources stay as stored.** A play's source is Apple Music, Your Music, Spotify, or a value
  from a newer build, which is kept as it was (`PlaySource::Unknown`) so it syncs back unchanged
  and counts only under All. Nothing stored means Apple Music. Apple Music and Your Music keep
  their index codes (0 and 1), so existing files and digests are untouched.
- **Spotify is the exception to native networking.** librespot speaks Spotify's own protocol,
  so it brings its own TLS and runs on one Tokio runtime owned by `tracks-spotify`. Every call
  is still synchronous and time-limited. Sign-in is OAuth with PKCE in the system browser; only
  librespot's reusable credentials are handed to the host for the Keychain or Keystore, never
  the OAuth tokens. See Spotify below.

## Speed

`cargo bench -p tracks-store --bench large_history` seeds the same library as the Swift app's
`LargeLibraryBenchmarks` (150,000 plays, 25,000 songs, 4,000 artists over five years) and a
ten-year, 300,000-play one. On an M-series Mac, release build:

| | Swift (in memory) | Engine |
| --- | --- | --- |
| First load of the history | 4.9 s | 2.2 s (once; later launches sync only changes) |
| All-time song chart | 80 ms | 0.06 ms |
| All-time artist chart | 47 ms | 0.1 ms |
| All-time album chart | 292 ms | 0.14 ms |
| Search | 522 ms index build, then 11 ms a query | 0.3 to 0.5 ms a query, no build |
| Add a play, then the next all-time chart | rebuild of in-memory state | 10 ms |

A year's charts take 6 to 12 ms and a month's about 1 ms, and none of it grows with history
outside the period: the ten-year, 300,000-play library gives the same figures. The index file is
34 MB for 150,000 plays and 53 MB for 300,000.

## Building

```bash
Engine/build-apple.sh
```

Builds the static libraries for iPhone, the Simulator and the Mac, generates the Swift bindings
and writes `Engine/apple/TracksEngine` (a local Swift package TracksCore depends on). Run it once
after cloning and after any change in `Engine/`. Both outputs are gitignored.

```bash
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo clippy --workspace --all-targets --features tracks-ffi/spotify -- -D warnings
```

The build scripts leave Spotify out. `Engine/build-apple.sh --spotify` builds it in for your own
builds: it writes `Config/Engine.xcconfig` so Debug builds of the apps define `SPOTIFY` and
link `Security.framework` (rustls reads the system roots for proxies), and Release builds stop
with an error until the script runs again without `--spotify`. `Engine/build-android.sh
--sideload` (or `--both`) builds Android's sideload variant with it; its Kotlin bindings rename
`SpotifyStream.close` to `stop` (`android-bindings.toml`), since UniFFI's Kotlin objects free
themselves in `close()`. Elsewhere, build `tracks-ffi` with `--features spotify` and generate
the bindings from that library.

`tests/consistency.rs` in `tracks-store` is a property test: random adds, edits, removals and
syncs, read between each, must always end exactly where a rebuild from the final history does.
`HistoryEngineParityTests` in TracksCore checks the engine's charts against the Swift
calculator row for row.

## Spotify

`tracks-spotify` signs in with librespot's client ID, whose redirect is
`http://127.0.0.1:<port>/login`; `SpotifyLogin.wait_for_redirect` listens there while the
system browser is open. `SpotifyAccount` keeps one session per account, reconnecting with
backoff when it drops, and reads metadata over Spotify's own protocol and the saved library and
search over the Web API (a 429 with a short Retry-After is waited out, a long one returned as
`RateLimited`). `SpotifyStream` decodes a song to interleaved 44.1 kHz stereo for
`AVAudioSourceNode` or Media3: `read` as floats, or as bytes, which cross the boundary without
boxing each sample: `read_pcm16` (little-endian 16-bit, 4 bytes a frame) and `read_f32_bytes`
(little-endian `f32`, 8 bytes a frame). Reads block while audio downloads, so hosts pull from
a feeder thread.

Since November 2026 Spotify refuses audio keys to clients like this for accounts it hasn't seen
on one before ([librespot#1649](https://github.com/librespot-org/librespot/issues/1649)), and
there is no fix. Opening a stream then fails with `AudioKeyRefused`; sign-in, browsing and the
library keep working. Using librespot is against Spotify's terms, so the feature is off by
default and not for store builds.

Two workarounds live here until librespot updates: `patches/sysinfo` stands in for sysinfo
0.36, which librespot-core 0.8 needs only for the OS version and which does not build for iOS
against current libc (the host passes the OS version in `SpotifyConfig`), and `vergen` is
pinned to 9.0.6 in `Cargo.lock` because 9.1 breaks librespot-core's build script.

## Credits

Spotify support is built on [librespot](https://github.com/librespot-org/librespot) (MIT,
Paul Lietar and contributors). Its shape, a pull-based player without an output device, follows
[psst](https://github.com/jpochyla/psst) (MIT, Jan Pochyla and contributors). Both licenses are
in `licenses/`.

## Status

Moved to the engine so far: name folding, artist credits, the history index and its charts,
totals, activity, streaks and search; Last.fm request building, signing and parsing; what you
own of an album and Get More's ranking (`tracks-library`); Tracks Radio over Your Music
(`tracks_mixes::radio`, a port of `LiveMix` with its steering, opening probe, habits and taste
graph), which Android plays. Tracks Radio over Apple Music stays in Swift with the MusicKit
catalog; iOS and the Mac may adopt the engine's for Your Music. In the app, Charts (songs,
artists, albums) reads from the engine, and Your Music's Get More, artist and album pages ask
`OwnedLibrary`, `rankGetMore`, `ownershipOfCounts`, `searchShowsFinds` and the get routes
through TracksCore's `LibraryOwnership.swift`. Still in Swift, to move next: Summary and
the profiles, search with its "remembered" phrases, mixes and Tracks Radio, the scrobble service
(switching it to the Rust request builder), widgets reading the shared index, and replacing the
in-memory `ListeningHistoryReader` so launch and memory no longer scale with history.
