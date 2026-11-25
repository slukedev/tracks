//! What a music server finds for you: which artists to go on from, what's left out, and
//! which shelf each song goes to. The host does the asking (a search, the server's similar
//! songs) and the cover checks; see `tracks_mixes::server`.

use std::sync::{Arc, Mutex, MutexGuard};

use tracks_mixes::server as core;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FoundSong {
    pub id: String,
    pub identity: String,
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub cover: Option<String>,
    pub duration_ms: Option<u64>,
}

impl From<FoundSong> for core::FoundSong {
    fn from(s: FoundSong) -> Self {
        Self {
            id: s.id,
            identity: s.identity,
            title: s.title,
            artist: s.artist,
            album: s.album,
            cover: s.cover,
            duration_ms: s.duration_ms,
        }
    }
}

impl From<core::FoundSong> for FoundSong {
    fn from(s: core::FoundSong) -> Self {
        Self {
            id: s.id,
            identity: s.identity,
            title: s.title,
            artist: s.artist,
            album: s.album,
            cover: s.cover,
            duration_ms: s.duration_ms,
        }
    }
}

fn songs(list: &[core::FoundSong]) -> Vec<FoundSong> {
    list.iter().cloned().map(Into::into).collect()
}

/// A song the server found, with what the host knows of it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FoundCandidate {
    pub song: FoundSong,
    pub heard: bool,
    pub known_artist: bool,
    pub owned: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ForYouShelf {
    Picks,
    Suggested,
    Further,
}

impl From<ForYouShelf> for core::Shelf {
    fn from(list: ForYouShelf) -> Self {
        match list {
            ForYouShelf::Picks => Self::Picks,
            ForYouShelf::Suggested => Self::Suggested,
            ForYouShelf::Further => Self::Further,
        }
    }
}

/// What the shelves show now.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ForYouShelves {
    pub picks: Vec<FoundSong>,
    pub suggested: Vec<FoundSong>,
    pub further: Vec<FoundSong>,
    pub from: Vec<String>,
    pub can_go_on: bool,
    pub loaded_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ArtistStart {
    pub theirs: Vec<u32>,
    pub start: Option<u32>,
}

/// The artists picks start from: what you play leads, picked artists keep a place, and your
/// library fills the rest.
#[uniffi::export]
pub fn server_seeds(
    picked: Vec<String>,
    played: Vec<String>,
    library: Vec<String>,
    limit: u32,
    rotation: u64,
) -> Vec<String> {
    core::seeds(&picked, &played, &library, limit as usize, rotation)
}

/// From a search for an artist: up to four of their own songs, and the song to go on from.
#[uniffi::export]
pub fn server_artist_start(artist: String, found: Vec<FoundSong>) -> ArtistStart {
    let found: Vec<core::FoundSong> = found.into_iter().map(Into::into).collect();
    let start = core::artist_start(&artist, &found);
    ArtistStart {
        theirs: start.theirs.into_iter().map(|i| i as u32).collect(),
        start: start.start.map(|i| i as u32),
    }
}

/// Songs you have and new ones woven together, about one of yours for every two new.
#[uniffi::export]
pub fn server_blend(owned: Vec<FoundSong>, new: Vec<FoundSong>) -> Vec<FoundSong> {
    let owned: Vec<core::FoundSong> = owned.into_iter().map(Into::into).collect();
    let new: Vec<core::FoundSong> = new.into_iter().map(Into::into).collect();
    songs(&core::blend(&owned, &new, 1.0 / 3.0))
}

/// How many songs a shelf asks for to end on a whole column.
#[uniffi::export]
pub fn server_wanted(showing: u32, per_column: u32) -> u32 {
    core::wanted(showing as usize, per_column as usize) as u32
}

/// Picks kept from last time; None if they can't be read.
#[uniffi::export]
pub fn server_for_you_from_json(json: String) -> Option<Arc<ServerForYou>> {
    core::ForYou::from_json(&json).map(|state| {
        Arc::new(ServerForYou {
            inner: Mutex::new(state),
        })
    })
}

/// One server's walk through the artists you play and ones like them.
#[derive(Debug, uniffi::Object)]
pub struct ServerForYou {
    inner: Mutex<core::ForYou>,
}

impl ServerForYou {
    fn state(&self) -> MutexGuard<'_, core::ForYou> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

#[uniffi::export]
impl ServerForYou {
    /// A walk starting from `seeds`, nothing found yet.
    #[uniffi::constructor]
    pub fn new(seeds: Vec<String>, picked: Vec<String>, now_ms: i64) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(core::ForYou::start(&seeds, &picked, now_ms)),
        })
    }

    pub fn to_json(&self) -> String {
        self.state().to_json()
    }

    pub fn copy(&self) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(self.state().clone()),
        })
    }

    pub fn shelves(&self) -> ForYouShelves {
        let state = self.state();
        ForYouShelves {
            picks: songs(&state.songs),
            suggested: songs(&state.suggested),
            further: songs(&state.further),
            from: state.from.clone(),
            can_go_on: state.can_go_on(),
            loaded_at_ms: state.loaded_at_ms,
        }
    }

    /// Whether these picks still stand for the artists picked, at this time.
    pub fn stands(&self, picked: Vec<String>, now_ms: i64) -> bool {
        self.state().stands(&picked, now_ms)
    }

    pub fn can_go_on(&self) -> bool {
        self.state().can_go_on()
    }

    pub fn has_queue(&self) -> bool {
        !self.state().queue.is_empty()
    }

    pub fn next_artist(&self) -> Option<String> {
        self.state().next_artist()
    }

    pub fn add(&self, found: Vec<FoundCandidate>, yours: Vec<FoundSong>, seed: u64) {
        let found: Vec<core::Candidate> = found
            .into_iter()
            .map(|c| core::Candidate {
                song: c.song.into(),
                heard: c.heard,
                known_artist: c.known_artist,
                owned: c.owned,
            })
            .collect();
        let yours: Vec<core::FoundSong> = yours.into_iter().map(Into::into).collect();
        self.state().add(&found, &yours, seed);
    }

    pub fn waiting(&self, list: ForYouShelf) -> u32 {
        self.state().waiting(list.into()) as u32
    }

    pub fn take_waiting(&self, list: ForYouShelf, want: u32) -> Vec<FoundSong> {
        songs(&self.state().take_waiting(list.into(), want as usize))
    }

    pub fn place(&self, list: ForYouShelf, found: Vec<FoundSong>) {
        self.state()
            .place(list.into(), found.into_iter().map(Into::into).collect());
    }
}
