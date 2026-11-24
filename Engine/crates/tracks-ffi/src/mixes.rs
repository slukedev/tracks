//! Mixes, the artist-spread shuffle and the play feed.

use rustc_hash::FxHashSet;

use crate::{HistoryIndex, PlayKind, PlaySource, Result};

/// One play read back from the index, newest spelling of nothing: exactly as it was written.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HistoryPlay {
    pub identity: String,
    pub title: String,
    pub artist_name: String,
    pub album_title: Option<String>,
    pub catalog_id: String,
    pub song_key: String,
    pub artwork_url: Option<String>,
    pub played_at_ms: i64,
    pub listen_ms: i64,
    pub kind: PlayKind,
    pub source: PlaySource,
}

fn kind_of(raw: u8) -> PlayKind {
    match tracks_model::PlayKind::from_u8(raw) {
        Some(tracks_model::PlayKind::Radio) => PlayKind::Radio,
        Some(tracks_model::PlayKind::Imported) => PlayKind::Imported,
        Some(tracks_model::PlayKind::LastFm) => PlayKind::LastFm,
        _ => PlayKind::OnDemand,
    }
}

impl From<tracks_store::PlayRow> for HistoryPlay {
    fn from(row: tracks_store::PlayRow) -> Self {
        Self {
            identity: row.identity,
            title: row.title,
            artist_name: row.artist_name,
            album_title: row.album_title,
            catalog_id: row.catalog_id,
            song_key: row.song_key,
            artwork_url: row.artwork_url,
            played_at_ms: row.played_at,
            listen_ms: row.listen_ms,
            kind: kind_of(row.kind),
            source: row.source.into(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DayPart {
    Morning,
    Afternoon,
    Evening,
    Night,
}

impl From<tracks_mixes::DayPart> for DayPart {
    fn from(part: tracks_mixes::DayPart) -> Self {
        match part {
            tracks_mixes::DayPart::Morning => Self::Morning,
            tracks_mixes::DayPart::Afternoon => Self::Afternoon,
            tracks_mixes::DayPart::Evening => Self::Evening,
            tracks_mixes::DayPart::Night => Self::Night,
        }
    }
}

/// Which mix, and what it was built around.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MixKind {
    RightNow { part: DayPart, is_weekend: bool },
    OnRepeat,
    NewFinds,
    RadioFinds,
    Rediscover,
    Throwback { months_ago: u8, around_ms: i64 },
    AllTimeFavorites,
    DeepCuts,
}

impl From<tracks_mixes::MixKind> for MixKind {
    fn from(kind: tracks_mixes::MixKind) -> Self {
        use tracks_mixes::MixKind as K;
        match kind {
            K::RightNow { part, is_weekend } => Self::RightNow {
                part: part.into(),
                is_weekend,
            },
            K::OnRepeat => Self::OnRepeat,
            K::NewFinds => Self::NewFinds,
            K::RadioFinds => Self::RadioFinds,
            K::Rediscover => Self::Rediscover,
            K::Throwback {
                months_ago,
                around_ms,
            } => Self::Throwback {
                months_ago,
                around_ms,
            },
            K::AllTimeFavorites => Self::AllTimeFavorites,
            K::DeepCuts => Self::DeepCuts,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MixSong {
    pub identity: String,
    pub catalog_id: String,
    pub title: String,
    pub artist_name: String,
    pub album_title: Option<String>,
    pub artwork_url: Option<String>,
    pub plays: u32,
    pub last_heard_ms: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Mix {
    /// Stable across launches; the Swift app's id for the same mix.
    pub id: String,
    pub kind: MixKind,
    pub songs: Vec<MixSong>,
    pub play_count: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MixSet {
    pub right_now: Option<Mix>,
    pub other_times: Vec<Mix>,
    pub mixes: Vec<Mix>,
}

impl From<tracks_mixes::Mix> for Mix {
    fn from(mix: tracks_mixes::Mix) -> Self {
        Self {
            id: mix.kind.id(),
            kind: mix.kind.into(),
            play_count: mix.play_count,
            songs: mix
                .songs
                .into_iter()
                .map(|s| MixSong {
                    identity: s.identity,
                    catalog_id: s.catalog_id,
                    title: s.title,
                    artist_name: s.artist_name,
                    album_title: s.album_title,
                    artwork_url: s.artwork_url,
                    plays: s.plays,
                    last_heard_ms: s.last_heard_ms,
                })
                .collect(),
        }
    }
}

#[uniffi::export]
impl HistoryIndex {
    /// The `limit` plays before `before_ms` (or the newest), newest first.
    pub fn recent_plays(&self, before_ms: Option<i64>, limit: u32) -> Result<Vec<HistoryPlay>> {
        Ok(self
            .store
            .recent_plays(before_ms, limit)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Every play since `since_ms` (all for `None`), oldest first.
    pub fn plays_since(&self, since_ms: Option<i64>) -> Result<Vec<HistoryPlay>> {
        Ok(self
            .store
            .plays_since(since_ms)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Every mix for `now_ms`, in the index's time zone, leaving out `excluded` song identities.
    pub fn mixes(&self, now_ms: i64, excluded: Vec<String>) -> Result<MixSet> {
        let plays: Vec<tracks_mixes::MixPlay> = self
            .store
            .plays_since(None)?
            .into_iter()
            .map(|row| tracks_mixes::MixPlay {
                is_radio: row.kind == tracks_model::PlayKind::Radio as u8,
                identity: row.identity,
                catalog_id: row.catalog_id,
                title: row.title,
                artist_name: row.artist_name,
                album_title: row.album_title,
                artwork_url: row.artwork_url,
                played_at_ms: row.played_at,
            })
            .collect();
        let excluded: FxHashSet<String> = excluded.into_iter().collect();
        let out = tracks_mixes::build_mixes(&plays, &excluded, now_ms, &self.store.time_zone());
        Ok(MixSet {
            right_now: out.right_now.map(Into::into),
            other_times: out.other_times.into_iter().map(Into::into).collect(),
            mixes: out.mixes.into_iter().map(Into::into).collect(),
        })
    }
}

/// Something to shuffle: its id, the artist to keep apart, and whether it was just heard.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ShuffleItem {
    pub id: String,
    pub artist_key: String,
    pub recently_heard: bool,
}

/// The ids in an order that keeps each artist apart; the same seed gives the same order.
#[uniffi::export]
pub fn fresh_shuffle(items: Vec<ShuffleItem>, seed: u64) -> Vec<String> {
    tracks_mixes::fresh_order(
        &items,
        |item| item.artist_key.clone(),
        |item| item.recently_heard,
        seed,
    )
    .into_iter()
    .map(|item| item.id)
    .collect()
}

/// A seed that holds all day for this local date and salt.
#[uniffi::export]
pub fn daily_seed(year: i16, month: i8, day: i8, salt: String) -> u64 {
    tracks_mixes::daily_seed(year, month, day, &salt)
}
