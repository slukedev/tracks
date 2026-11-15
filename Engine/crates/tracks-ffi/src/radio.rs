//! Tracks Radio: the station, built from the index's plays and the host's library, and what it
//! keeps between listens (habits and skips, as JSON the host stores).

use std::sync::{Arc, Mutex, MutexGuard};

use rustc_hash::FxHashSet;
use tracks_mixes::radio as core;

use crate::{EngineError, HistoryIndex, Result};

/// A song in your music, or one a server found for you.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RadioTrack {
    pub identity: String,
    pub title: String,
    pub artist_name: String,
    pub genre: Option<String>,
    pub release_year: Option<i32>,
    pub is_from_server: bool,
}

impl From<RadioTrack> for core::RadioTrack {
    fn from(t: RadioTrack) -> Self {
        Self {
            identity: t.identity,
            title: t.title,
            artist_name: t.artist_name,
            genre: t.genre,
            release_year: t.release_year,
            is_from_server: t.is_from_server,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RadioDiscovery {
    Familiar,
    Balanced,
    Adventurous,
}

/// How Tracks Radio plays, as the person tunes it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RadioTuning {
    pub discovery: RadioDiscovery,
    pub genres: Vec<String>,
    pub brings_back_old_favorites: bool,
}

impl From<RadioTuning> for core::RadioTuning {
    fn from(t: RadioTuning) -> Self {
        Self {
            discovery: match t.discovery {
                RadioDiscovery::Familiar => core::Discovery::Familiar,
                RadioDiscovery::Balanced => core::Discovery::Balanced,
                RadioDiscovery::Adventurous => core::Discovery::Adventurous,
            },
            genres: t.genres.into_iter().collect(),
            brings_back_old_favorites: t.brings_back_old_favorites,
        }
    }
}

/// Why a song came up.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RadioReason {
    NewFind,
    NewFromYourArtist,
    NewFindLike { artist: String },
    OnTheRoad,
    AroundNow,
    LeaningInto { genre: String },
    OldFavorite { last_heard_ms: i64 },
    MostPlayed,
    MoreOf { genre: String },
    MoreLike { artist: String },
    Probing,
    GoesWith { title: String },
}

impl From<core::Reason> for RadioReason {
    fn from(reason: core::Reason) -> Self {
        use core::Reason as R;
        match reason {
            R::NewFind => Self::NewFind,
            R::NewFromYourArtist => Self::NewFromYourArtist,
            R::NewFindLike(artist) => Self::NewFindLike { artist },
            R::OnTheRoad => Self::OnTheRoad,
            R::AroundNow => Self::AroundNow,
            R::LeaningInto(genre) => Self::LeaningInto { genre },
            R::OldFavorite { last_heard_ms } => Self::OldFavorite { last_heard_ms },
            R::MostPlayed => Self::MostPlayed,
            R::MoreOf(genre) => Self::MoreOf { genre },
            R::MoreLike(artist) => Self::MoreLike { artist },
            R::Probing => Self::Probing,
            R::GoesWith(title) => Self::GoesWith { title },
        }
    }
}

/// What the station is doing about what it's heard.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum RadioSteering {
    AwayFromGenre { genre: String },
    AwayFromDecade { year: i32 },
    FewerNewFinds,
    MoreNewFinds,
    TryingSomethingElse,
    TowardGenre { genre: String },
    TowardArtist { artist: String },
    SettledOn { genre: String },
}

impl From<&core::Steering> for RadioSteering {
    fn from(steering: &core::Steering) -> Self {
        use core::Steering as S;
        match steering {
            S::AwayFromGenre(genre) => Self::AwayFromGenre {
                genre: genre.clone(),
            },
            S::AwayFromDecade(year) => Self::AwayFromDecade { year: *year },
            S::FewerNewFinds => Self::FewerNewFinds,
            S::MoreNewFinds => Self::MoreNewFinds,
            S::TryingSomethingElse => Self::TryingSomethingElse,
            S::TowardGenre(genre) => Self::TowardGenre {
                genre: genre.clone(),
            },
            S::TowardArtist(artist) => Self::TowardArtist {
                artist: artist.clone(),
            },
            S::SettledOn(genre) => Self::SettledOn {
                genre: genre.clone(),
            },
        }
    }
}

/// How a song went, told by when it gave way.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Listen {
    Skipped,
    MovedOn,
    Finished,
}

impl From<Listen> for core::Listen {
    fn from(listen: Listen) -> Self {
        match listen {
            Listen::Skipped => Self::Skipped,
            Listen::MovedOn => Self::MovedOn,
            Listen::Finished => Self::Finished,
        }
    }
}

impl From<core::Listen> for Listen {
    fn from(listen: core::Listen) -> Self {
        match listen {
            core::Listen::Skipped => Self::Skipped,
            core::Listen::MovedOn => Self::MovedOn,
            core::Listen::Finished => Self::Finished,
        }
    }
}

/// How a song that played for `played_ms` of `duration_ms` went.
#[uniffi::export]
pub fn radio_listen(played_ms: i64, duration_ms: Option<i64>) -> Listen {
    core::Listen::from_played(played_ms, duration_ms).into()
}

/// The skips kept in `skips_json`, with one more of `identity` at `at_ms`, as JSON to keep.
#[uniffi::export]
pub fn radio_record_skip(skips_json: String, identity: String, at_ms: i64) -> String {
    let mut skips = core::SkipMemory::from_json(&skips_json);
    skips.record(&identity, at_ms);
    skips.to_json()
}

/// Tracks Radio playing: picks the next song as the one before starts, and learns from how
/// each went.
#[derive(Debug, uniffi::Object)]
pub struct RadioStation {
    mix: Mutex<core::LiveMix>,
}

impl RadioStation {
    fn mix(&self) -> Result<MutexGuard<'_, core::LiveMix>> {
        self.mix.lock().map_err(|_| EngineError::Closed)
    }
}

#[uniffi::export]
impl RadioStation {
    pub fn is_empty(&self) -> Result<bool> {
        Ok(self.mix()?.is_empty())
    }

    /// Starts the listen from what has landed before (`habits_json`, as [`Self::habits_json`]
    /// gave it last time), opening with a probe when `probing`.
    pub fn begin(&self, habits_json: String, probing: bool, now_ms: i64) -> Result<()> {
        self.mix()?
            .begin(&core::RadioHabits::from_json(&habits_json), probing, now_ms);
        Ok(())
    }

    /// The song a listen opens on.
    pub fn opener(&self) -> Result<Option<String>> {
        Ok(self.mix()?.opener(&|_| true))
    }

    /// The next song, going round again once every song has played.
    pub fn next(&self) -> Result<Option<String>> {
        Ok(self.mix()?.next_playable(&|_| true))
    }

    /// Takes back a pick that never played.
    pub fn release(&self, identity: String) -> Result<()> {
        self.mix()?.release(&identity);
        Ok(())
    }

    /// Counts a song as picked without drawing it: the one a listen starts with.
    pub fn note_picked(&self, identity: String) -> Result<()> {
        self.mix()?.note_picked(&identity);
        Ok(())
    }

    pub fn note(&self, identity: String, listen: Listen) -> Result<()> {
        self.mix()?.note(listen.into(), &identity);
        Ok(())
    }

    pub fn note_loved(&self, identity: String) -> Result<()> {
        self.mix()?.note_loved(&identity);
        Ok(())
    }

    pub fn note_replayed(&self, identity: String) -> Result<()> {
        self.mix()?.note_replayed(&identity);
        Ok(())
    }

    /// Leans toward the songs that go with this one: the song a station starts from.
    pub fn lean_toward(&self, identity: String) -> Result<()> {
        self.mix()?.lean_toward(&identity, 1.0);
        Ok(())
    }

    pub fn reason(&self, identity: String) -> Result<Option<RadioReason>> {
        Ok(self.mix()?.reason(&identity).map(Into::into))
    }

    pub fn steering(&self) -> Result<Option<RadioSteering>> {
        Ok(self.mix()?.steering().map(Into::into))
    }

    pub fn has_picked(&self, identity: String) -> Result<bool> {
        Ok(self.mix()?.has_picked(&identity))
    }

    pub fn knows(&self, identity: String) -> Result<bool> {
        Ok(self.mix()?.knows(&identity))
    }

    pub fn is_probing(&self) -> Result<bool> {
        Ok(self.mix()?.is_probing())
    }

    pub fn new_share(&self) -> Result<f64> {
        Ok(self.mix()?.new_share())
    }

    /// What has landed, this listen included, as JSON to keep for the next. None for a
    /// station that wasn't begun.
    pub fn habits_json(&self, now_ms: i64) -> Result<Option<String>> {
        Ok(self.mix()?.habits(now_ms).map(|h| h.to_json()))
    }

    /// Carries the listen `earlier` was playing over to this newly tuned station.
    pub fn continue_from(&self, earlier: Arc<RadioStation>) -> Result<()> {
        if std::ptr::eq(self, earlier.as_ref()) {
            return Ok(());
        }
        let earlier = earlier.mix()?.clone();
        self.mix()?.continue_listen(&earlier);
        Ok(())
    }
}

#[uniffi::export]
impl HistoryIndex {
    /// Tracks Radio over `tracks` (your music, then songs your servers found for you), from
    /// every play in the index, tuned by `tuning`, leaving out `hidden` songs and ones skipped
    /// often lately (`skips_json`, as [`radio_record_skip`] keeps it).
    pub fn tracks_radio(
        &self,
        tracks: Vec<RadioTrack>,
        tuning: RadioTuning,
        skips_json: String,
        hidden: Vec<String>,
        now_ms: i64,
        seed: u64,
    ) -> Result<Arc<RadioStation>> {
        let plays: Vec<core::RadioPlay> = self
            .store
            .plays_since(None)?
            .into_iter()
            .map(|row| core::RadioPlay {
                is_station: row.kind == tracks_model::PlayKind::Radio as u8,
                identity: row.identity,
                artist_name: row.artist_name,
                played_at_ms: row.played_at,
            })
            .collect();
        let tracks: Vec<core::RadioTrack> = tracks.into_iter().map(Into::into).collect();
        let skips = core::SkipMemory::from_json(&skips_json);
        let hidden: FxHashSet<String> = hidden.into_iter().collect();
        let signals = core::RadioSignals {
            skips: &skips,
            hidden: &hidden,
        };
        let mix = core::tracks_radio(&plays, &tracks, &tuning.into(), signals, now_ms, seed);
        Ok(Arc::new(RadioStation {
            mix: Mutex::new(mix),
        }))
    }

    /// The genres Tracks Radio can lean into: the ones you play most, by the genres `tracks`
    /// carry, most played first.
    pub fn radio_genres(&self, tracks: Vec<RadioTrack>, limit: u32) -> Result<Vec<String>> {
        let genres: rustc_hash::FxHashMap<String, String> = tracks
            .into_iter()
            .filter_map(|t| t.genre.map(|g| (t.identity, g)))
            .collect();
        let plays = self.store.plays_since(None)?;
        Ok(core::genre_choices(
            plays
                .iter()
                .map(|p| genres.get(&p.identity).map(String::as_str)),
            limit as usize,
        ))
    }
}
