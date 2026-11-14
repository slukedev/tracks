//! A made-up but believable listening history, for screenshots, previews and trying the app
//! without a music account. Every artist, album and song name here is invented.
//!
//! A port of the Swift app's `DemoLibrary`, so every platform shows the same invented
//! library: the same artists, albums, songs, collaborations, favourites, listening windows
//! and session shapes. Same seed and time in, same history out, so screenshots are
//! reproducible.
//!
//! The generator is the same `SplitMix64` as Swift's `SeededGenerator`, but the history is
//! not bit-for-bit the Swift app's: Swift's `shuffled(using:)`, `randomElement(using:)` and
//! `Double.random(in:using:)` turn the raw numbers into draws differently from the helpers
//! here, so which song lands where differs in detail between platforms.

mod catalog;
mod random;

use std::collections::VecDeque;

use jiff::civil::Weekday;
use jiff::tz::TimeZone;
use jiff::{Timestamp, ToSpan, Zoned};
pub use tracks_model::PlayKind;

use catalog::{ALBUM_NAMES, ARTIST_NAMES, Artist, COLLABORATIONS, Catalog, GENRES, Song};
use random::Draw;

/// The seed the apps use unless they ask for another.
pub const DEFAULT_SEED: u64 = 2026;

/// How many days of history the apps ask for by default: about ten months.
pub const DEFAULT_DAYS: u32 = 300;

/// The invented radio stations sample radio plays come from.
pub const STATIONS: [&str; 4] = [
    "Late Night Jazz Radio",
    "Indie Rising Station",
    "Chill Station",
    "Morning Mix Radio",
];

/// One invented play.
#[derive(Debug, Clone, PartialEq)]
pub struct DemoPlay {
    pub title: String,
    pub artist_name: String,
    pub album_title: String,
    pub kind: PlayKind,
    /// When it played, in Unix milliseconds.
    pub played_at_ms: i64,
    /// The station, for radio plays.
    pub station_name: Option<String>,
    /// Whether it has been sent to Last.fm; the latest three haven't.
    pub is_scrobbled: bool,
    /// Whether a radio play was played back afterwards.
    pub is_played_back: bool,
    /// How long the song is, in seconds.
    pub length_seconds: f64,
}

impl DemoPlay {
    fn new(song: &Song, kind: PlayKind, played_at_ms: i64, station: Option<&str>) -> Self {
        Self {
            title: song.title.clone(),
            artist_name: song.artist.clone(),
            album_title: song.album.clone(),
            kind,
            played_at_ms,
            station_name: station.map(str::to_owned),
            is_scrobbled: true,
            is_played_back: false,
            length_seconds: song.length,
        }
    }
}

/// Every invented artist, so real data can be told apart from sample data.
pub fn artist_names() -> Vec<String> {
    ARTIST_NAMES.iter().map(|&name| name.to_owned()).collect()
}

/// Every artist credit on an invented song: each artist, and the collaborations.
pub fn artist_credits() -> Vec<String> {
    ARTIST_NAMES
        .iter()
        .copied()
        .chain(COLLABORATIONS.iter().filter_map(|c| c.credit))
        .map(str::to_owned)
        .collect()
}

/// Every invented album title.
pub fn album_names() -> Vec<String> {
    ALBUM_NAMES.iter().map(|&name| name.to_owned()).collect()
}

/// A genre, release year and catalog artists for an invented song.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DemoSongMetadata {
    /// The song's identity, [`tracks_model::song_key`] of its title and artist credit.
    pub song_key: String,
    pub genre: String,
    pub release_year: i32,
    /// Who the catalog would list as the song's artists.
    pub artists: Vec<String>,
}

/// A genre and release year for every invented song, so sample data has genres and decades
/// to show without asking a music service about made-up songs. Takes the same seed as
/// [`plays`] and draws nothing extra from it, so the histories stay as they were. An artist
/// can have two songs of the same name; as in the Swift app's dictionary, the later wins.
pub fn song_metadata(seed: u64) -> Vec<DemoSongMetadata> {
    let mut random = Draw::new(seed);
    let catalog = Catalog::make(&mut random);
    let mut result = Vec::new();
    for (rank, artist) in catalog.artists.iter().enumerate() {
        let genre = GENRES[rank % GENRES.len()];
        for (number, album) in artist.albums.iter().enumerate() {
            // Favourites have catalogues going back decades; the long tail is newer.
            let span = if rank < 6 { 40 } else { 15 };
            let year = 2025 - ((rank * 7 + number * 4) % span) as i32;
            for song in &album.songs {
                result.push(DemoSongMetadata {
                    song_key: tracks_model::song_key(&song.title, &song.artist),
                    genre: genre.to_owned(),
                    release_year: year,
                    artists: catalog::catalog_artists(song),
                });
            }
        }
    }
    result
}

/// About `days` days of listening ending at `now_ms` (Unix milliseconds), oldest first, with
/// the last two weeks unbroken. Days start and weekends fall in `zone`. A time outside what
/// a calendar can hold gives no plays.
pub fn plays(now_ms: i64, zone: &TimeZone, days: u32, seed: u64) -> Vec<DemoPlay> {
    let Ok(now) = Timestamp::from_millisecond(now_ms) else {
        return Vec::new();
    };
    let Ok(today) = now.to_zoned(zone.clone()).start_of_day() else {
        return Vec::new();
    };
    let mut random = Draw::new(seed);
    let catalog = Catalog::make(&mut random);
    let mut plays: Vec<DemoPlay> = Vec::new();

    for day_offset in (0..=days).rev() {
        let Ok(day) = today.checked_sub(i64::from(day_offset).days()) else {
            continue;
        };
        // The odd day off, but never in the last two weeks, so there's a streak to show
        // whatever time of day it is.
        let is_recent = day_offset < 14;
        if !is_recent && random.unit() < 0.09 {
            continue;
        }

        // Listening grows a little over the year, which gives the trend charts a shape.
        let growth = 0.75 + 0.45 * (1.0 - f64::from(day_offset) / f64::from(days.max(1)));
        // Artists join the rotation over time, so there are discoveries every month.
        let unlocked: Vec<&Artist> = catalog
            .artists
            .iter()
            .filter(|artist| artist.debut_offset >= i64::from(day_offset))
            .collect();

        for window in listening_windows(&day, &mut random) {
            let mut cursor = window.start_ms;
            let mode = SessionMode::pick(&mut random);
            let is_radio = mode == SessionMode::Radio;
            let station = if is_radio {
                random.pick(&STATIONS).copied()
            } else {
                None
            };
            let mut queue = songs(mode, &unlocked, &catalog, &mut random);
            let target = (window.songs as f64 * growth) as usize;
            let mut played = 0;
            while played < target && cursor < window.end_ms && cursor <= now_ms as f64 {
                let Some(song) = queue.pop_front() else { break };
                let kind = if is_radio {
                    PlayKind::Radio
                } else {
                    PlayKind::OnDemand
                };
                let mut play = DemoPlay::new(song, kind, cursor as i64, station);
                play.is_played_back = is_radio && random.unit() < 0.3;
                plays.push(play);
                cursor += (song.length + random.range(2.0, 14.0)) * 1000.0;
                played += 1;
                if queue.is_empty() {
                    queue = songs(mode, &unlocked, &catalog, &mut random);
                }
            }
        }

        // Three plays of one song on a single recent day, for "on repeat".
        if day_offset == 2
            && let Some(favourite) = catalog
                .artists
                .first()
                .and_then(|artist| artist.albums.first())
                .and_then(|album| album.songs.get(1))
            && let Ok(evening) = day
                .with()
                .hour(21)
                .minute(5)
                .second(0)
                .subsec_nanosecond(0)
                .build()
        {
            let evening_ms = evening.timestamp().as_millisecond();
            for index in 0..3 {
                plays.push(DemoPlay::new(
                    favourite,
                    PlayKind::OnDemand,
                    evening_ms + index * 212_000,
                    None,
                ));
            }
        }
    }

    plays.sort_by_key(|play| play.played_at_ms);
    // A few songs recovered from the service's history, a few read from Last.fm, and the
    // latest few not yet sent to Last.fm, so those states show up too.
    let count = plays.len();
    for (index, play) in plays.iter_mut().enumerate() {
        if play.kind == PlayKind::OnDemand {
            if index % 23 == 7 {
                play.kind = PlayKind::Imported;
            } else if index % 29 == 11 {
                play.kind = PlayKind::LastFm;
            }
        }
        play.is_scrobbled = index + 3 < count;
    }
    plays
}

/// One more sample song, played on demand at `played_at_ms`. A live feed uses it to show
/// screens changing as listening arrives. `number` picks the song; favourites come up often,
/// so charts reshuffle as well as grow.
pub fn live_play(played_at_ms: i64, number: u64) -> DemoPlay {
    let mut random = Draw::new(DEFAULT_SEED);
    let catalog = Catalog::make(&mut random);
    let songs: Vec<&Song> = catalog
        .artists
        .iter()
        .take(8)
        .flat_map(|artist| artist.albums.iter().take(1).flat_map(|album| &album.songs))
        .collect();
    let index = (number.wrapping_mul(7) % songs.len() as u64) as usize;
    let mut play = DemoPlay::new(songs[index], PlayKind::OnDemand, played_at_ms, None);
    play.is_scrobbled = false;
    play
}

// MARK: - Shape of a day

#[derive(Debug, Clone, Copy)]
struct Window {
    start_ms: f64,
    end_ms: f64,
    songs: usize,
}

fn listening_windows(day: &Zoned, random: &mut Draw) -> Vec<Window> {
    let mut windows = Vec::with_capacity(4);
    if matches!(day.weekday(), Weekday::Saturday | Weekday::Sunday) {
        windows.push(window(day, random, 10.5, 2.0, (6, 14)));
        if random.coin() {
            windows.push(window(day, random, 15.0, 2.0, (4, 10)));
        }
        windows.push(window(day, random, 20.0, 4.0, (10, 20)));
    } else {
        windows.push(window(day, random, 7.9, 1.0, (5, 10)));
        if random.unit() < 0.4 {
            windows.push(window(day, random, 12.4, 0.8, (3, 7)));
        }
        windows.push(window(day, random, 18.5, 1.5, (4, 9)));
        if random.unit() < 0.7 {
            windows.push(window(day, random, 21.8, 2.5, (6, 14)));
        }
    }
    windows.into_iter().flatten().collect()
}

/// A session starting near `hour` on `day`, `hours` long, of between `songs.0` and
/// `songs.1` songs.
fn window(
    day: &Zoned,
    random: &mut Draw,
    hour: f64,
    hours: f64,
    songs: (usize, usize),
) -> Option<Window> {
    let jitter = random.range(-0.4, 0.4);
    let minutes = ((hour + jitter) * 60.0) as i64;
    let start = day.checked_add(minutes.minutes()).ok()?;
    let start_ms = start.timestamp().as_millisecond() as f64;
    Some(Window {
        start_ms,
        end_ms: start_ms + hours * 3_600_000.0,
        songs: random.int(songs.0, songs.1),
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SessionMode {
    Album,
    Favourites,
    Radio,
}

impl SessionMode {
    fn pick(random: &mut Draw) -> Self {
        match random.unit() {
            r if r < 0.35 => Self::Album,
            r if r < 0.75 => Self::Favourites,
            _ => Self::Radio,
        }
    }
}

fn songs<'a>(
    mode: SessionMode,
    unlocked: &[&'a Artist],
    catalog: &'a Catalog,
    random: &mut Draw,
) -> VecDeque<&'a Song> {
    let pool: Vec<&Artist> = if unlocked.is_empty() {
        catalog.artists.iter().take(3).collect()
    } else {
        unlocked.to_vec()
    };
    match mode {
        SessionMode::Album => {
            let artist = weighted_artist(&pool, random);
            random
                .pick(&artist.albums)
                .map(|album| album.songs.iter().collect())
                .unwrap_or_default()
        }
        SessionMode::Favourites => (0..12)
            .filter_map(|_| {
                let artist = weighted_artist(&pool, random);
                let songs = &random.pick(&artist.albums)?.songs;
                if songs.is_empty() {
                    return None;
                }
                // Earlier tracks on an album get played more.
                let index = (random.unit().powf(1.8) * songs.len() as f64) as usize;
                songs.get(index.min(songs.len() - 1))
            })
            .collect(),
        SessionMode::Radio => (0..12)
            .filter_map(|_| {
                let artist = random.pick(&pool)?;
                let album = random.pick(&artist.albums)?;
                random.pick(&album.songs)
            })
            .collect(),
    }
}

/// Roughly Zipf: a handful of favourites and a long tail.
fn weighted_artist<'a>(artists: &[&'a Artist], random: &mut Draw) -> &'a Artist {
    let total: f64 = artists.iter().map(|artist| artist.weight).sum();
    let mut roll = random.range(0.0, total);
    for artist in artists {
        roll -= artist.weight;
        if roll < 0.0 {
            return artist;
        }
    }
    artists[artists.len() - 1]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    // 2026-11-11 21:20 UTC, a Wednesday.
    const NOW: i64 = 1_794_000_000_000;
    const DAY_MS: i64 = 86_400_000;

    fn zone() -> TimeZone {
        TimeZone::get("Europe/London").expect("zone")
    }

    fn sample() -> Vec<DemoPlay> {
        plays(NOW, &zone(), DEFAULT_DAYS, DEFAULT_SEED)
    }

    #[test]
    fn same_seed_and_time_same_history() {
        assert_eq!(sample(), sample());
        assert_ne!(sample(), plays(NOW, &zone(), DEFAULT_DAYS, 7));
    }

    #[test]
    fn history_is_sizeable_and_sorted() {
        let plays = sample();
        assert!(plays.len() > 5_000, "only {} plays", plays.len());
        assert!(
            plays
                .windows(2)
                .all(|w| w[0].played_at_ms <= w[1].played_at_ms)
        );
        assert!(plays.iter().all(|p| p.played_at_ms <= NOW));
        let earliest = NOW - i64::from(DEFAULT_DAYS + 1) * DAY_MS;
        assert!(plays.iter().all(|p| p.played_at_ms >= earliest));
    }

    #[test]
    fn last_two_weeks_have_plays_every_day() {
        let plays = sample();
        let zone = zone();
        let days: BTreeSet<_> = plays
            .iter()
            .map(|p| {
                Timestamp::from_millisecond(p.played_at_ms)
                    .expect("time")
                    .to_zoned(zone.clone())
                    .date()
            })
            .collect();
        let today = Timestamp::from_millisecond(NOW)
            .expect("time")
            .to_zoned(zone)
            .date();
        for offset in 0..14 {
            let day = today.checked_sub(offset.days()).expect("day");
            assert!(days.contains(&day), "no plays on {day}");
        }
    }

    #[test]
    fn radio_plays_carry_a_station_and_others_do_not() {
        let plays = sample();
        assert!(plays.iter().any(|p| p.kind == PlayKind::Radio));
        for play in &plays {
            if play.kind == PlayKind::Radio {
                let station = play.station_name.as_deref().expect("station");
                assert!(STATIONS.contains(&station));
            } else {
                assert!(play.station_name.is_none(), "{play:?}");
                assert!(!play.is_played_back);
            }
        }
    }

    #[test]
    fn collaboration_credits_appear() {
        let plays = sample();
        let credits: BTreeSet<&str> = plays.iter().map(|p| p.artist_name.as_str()).collect();
        let collaborations = COLLABORATIONS.iter().filter_map(|c| c.credit);
        assert!(
            collaborations
                .into_iter()
                .any(|credit| credits.contains(credit))
        );
        assert!(plays.iter().any(|p| p.title.contains("(feat. ")));
        for credit in &credits {
            assert!(
                artist_credits().iter().any(|c| c == credit),
                "unknown credit {credit}"
            );
        }
    }

    #[test]
    fn every_kind_shows_up_and_only_the_latest_three_are_unscrobbled() {
        let plays = sample();
        for kind in PlayKind::ALL {
            assert!(plays.iter().any(|p| p.kind == kind), "no {kind:?} plays");
        }
        let unscrobbled: Vec<usize> = (0..plays.len())
            .filter(|&i| !plays[i].is_scrobbled)
            .collect();
        assert_eq!(
            unscrobbled,
            (plays.len() - 3..plays.len()).collect::<Vec<_>>()
        );
    }

    #[test]
    fn on_repeat_song_plays_three_times_two_days_ago() {
        let plays = sample();
        let mut random = Draw::new(DEFAULT_SEED);
        let favourite = &Catalog::make(&mut random).artists[0].albums[0].songs[1];
        let repeats = plays
            .iter()
            .filter(|p| p.title == favourite.title && p.artist_name == favourite.artist)
            .collect::<Vec<_>>()
            .windows(3)
            .any(|w| {
                w[1].played_at_ms - w[0].played_at_ms == 212_000
                    && w[2].played_at_ms - w[1].played_at_ms == 212_000
            });
        assert!(repeats);
    }

    #[test]
    fn songs_have_believable_lengths() {
        assert!(
            sample()
                .iter()
                .all(|p| (150.0..290.0).contains(&p.length_seconds))
        );
    }

    #[test]
    fn live_play_is_an_unscrobbled_favourite() {
        let play = live_play(NOW, 3);
        assert_eq!(play, live_play(NOW, 3));
        assert_eq!(play.kind, PlayKind::OnDemand);
        assert_eq!(play.played_at_ms, NOW);
        assert!(!play.is_scrobbled);
        assert!(
            ARTIST_NAMES[..8]
                .iter()
                .any(|a| play.artist_name.starts_with(a))
        );
        assert_ne!(live_play(NOW, 4).title, play.title);
        // Wraps rather than overflowing.
        let _ = live_play(NOW, u64::MAX);
    }

    #[test]
    fn metadata_covers_every_played_song() {
        let metadata = song_metadata(DEFAULT_SEED);
        let keys: BTreeSet<&str> = metadata.iter().map(|m| m.song_key.as_str()).collect();
        for play in sample() {
            assert!(keys.contains(tracks_model::song_key(&play.title, &play.artist_name).as_str()));
        }
        let four = metadata
            .iter()
            .find(|m| m.artists.len() == 4)
            .expect("four-way credit");
        assert_eq!(four.artists[0], "Hollow Pines");
    }

    #[test]
    fn no_days_or_odd_times_do_not_panic() {
        assert!(
            plays(NOW, &zone(), 0, 1)
                .iter()
                .all(|p| p.played_at_ms <= NOW)
        );
        assert_eq!(plays(i64::MAX, &zone(), 3, 1), Vec::new());
    }
}
