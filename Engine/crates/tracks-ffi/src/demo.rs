//! The invented sample library, so every platform shows the same made-up listening.

use crate::{EngineError, PlayKind, Result};

/// One invented play from the sample library.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DemoPlay {
    pub title: String,
    pub artist_name: String,
    pub album_title: String,
    pub kind: PlayKind,
    pub played_at_ms: i64,
    /// The station, for radio plays.
    pub station_name: Option<String>,
    /// Whether it has been sent to Last.fm; the latest three haven't.
    pub is_scrobbled: bool,
    /// How long the song is, in seconds.
    pub length_seconds: f64,
}

impl From<tracks_demo::DemoPlay> for DemoPlay {
    fn from(play: tracks_demo::DemoPlay) -> Self {
        Self {
            title: play.title,
            artist_name: play.artist_name,
            album_title: play.album_title,
            kind: play.kind.into(),
            played_at_ms: play.played_at_ms,
            station_name: play.station_name,
            is_scrobbled: play.is_scrobbled,
            length_seconds: play.length_seconds,
        }
    }
}

/// About `days` days of invented listening ending at `now_ms`, oldest first, with the last
/// two weeks unbroken. Days start and weekends fall in the IANA `time_zone`. Same seed and
/// time in, same history out (the apps use seed 2026 and 300 days).
#[uniffi::export]
pub fn demo_plays(now_ms: i64, time_zone: String, days: u32, seed: u64) -> Result<Vec<DemoPlay>> {
    let zone = jiff::tz::TimeZone::get(&time_zone).map_err(|_| EngineError::TimeZone {
        identifier: time_zone,
    })?;
    Ok(tracks_demo::plays(now_ms, &zone, days, seed)
        .into_iter()
        .map(DemoPlay::from)
        .collect())
}

/// One more invented song, played on demand at `at_ms`, for a live feed. `number` picks the
/// song; favourites come up often.
#[uniffi::export]
pub fn demo_live_play(at_ms: i64, number: u32) -> DemoPlay {
    tracks_demo::live_play(at_ms, u64::from(number)).into()
}

/// An invented song's genre and year, so sample data has genres and decades for Tracks Radio.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DemoSongMetadata {
    /// The song's identity, as [`crate::song_identity`] makes it.
    pub identity: String,
    pub genre: String,
    pub release_year: i32,
}

/// The genre and year of every invented song, for the same `seed` as [`demo_plays`].
#[uniffi::export]
pub fn demo_song_metadata(seed: u64) -> Vec<DemoSongMetadata> {
    tracks_demo::song_metadata(seed)
        .into_iter()
        .map(|m| DemoSongMetadata {
            identity: m.song_key,
            genre: m.genre,
            release_year: m.release_year,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_794_000_000_000;

    #[test]
    fn plays_cross_the_boundary() {
        let plays = demo_plays(NOW, "America/New_York".into(), 30, 2026).expect("plays");
        assert!(plays.len() > 100, "only {} plays", plays.len());
        assert!(
            plays
                .iter()
                .any(|p| p.kind == PlayKind::Radio && p.station_name.is_some())
        );
        assert!(!plays.last().expect("last").is_scrobbled);
    }

    #[test]
    fn unknown_zone_is_an_error() {
        let error = demo_plays(NOW, "Nowhere/Special".into(), 30, 2026).expect_err("zone");
        assert!(
            matches!(error, EngineError::TimeZone { identifier } if identifier == "Nowhere/Special")
        );
    }

    #[test]
    fn live_play_is_on_demand_and_unscrobbled() {
        let play = demo_live_play(NOW, 5);
        assert_eq!(play.kind, PlayKind::OnDemand);
        assert_eq!(play.played_at_ms, NOW);
        assert!(!play.is_scrobbled);
    }
}
