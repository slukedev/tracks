//! Your own music against what you could get: what you own of an album, Get More's picks,
//! and whether a server or Lidarr can fetch.

use std::sync::Arc;

/// A song by its names, as the library or a server has them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SongName {
    pub title: String,
    pub artist: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Ownership {
    Owned,
    PartlyOwned,
    Missing,
}

impl From<tracks_library::Ownership> for Ownership {
    fn from(ownership: tracks_library::Ownership) -> Self {
        match ownership {
            tracks_library::Ownership::Owned => Self::Owned,
            tracks_library::Ownership::Partial => Self::PartlyOwned,
            tracks_library::Ownership::Missing => Self::Missing,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AlbumOwnership {
    pub ownership: Ownership,
    /// The positions of the songs you don't own, in album order.
    pub missing: Vec<u32>,
    pub owned_count: u32,
}

/// The songs in your library. Built once each time the library changes, off the main
/// thread, then asked as often as pages need.
#[derive(Debug, uniffi::Object)]
pub struct OwnedLibrary {
    songs: tracks_library::OwnedSongs,
}

#[uniffi::export]
impl OwnedLibrary {
    #[uniffi::constructor]
    pub fn new(songs: Vec<SongName>) -> Arc<Self> {
        Arc::new(Self {
            songs: tracks_library::OwnedSongs::from_songs(
                songs
                    .iter()
                    .map(|song| (song.title.as_str(), song.artist.as_str())),
            ),
        })
    }

    pub fn owns(&self, title: String, artist: String) -> bool {
        self.songs.owns(&title, &artist)
    }

    /// An album's songs, in album order, judged against the library.
    pub fn classify_album(&self, tracks: Vec<SongName>) -> AlbumOwnership {
        let result = tracks_library::classify_album(
            tracks
                .iter()
                .map(|song| (song.title.as_str(), song.artist.as_str())),
            &self.songs,
        );
        AlbumOwnership {
            ownership: result.ownership.into(),
            missing: result
                .missing
                .into_iter()
                .map(|index| index as u32)
                .collect(),
            owned_count: result.owned_count as u32,
        }
    }

    /// How many different songs it holds.
    pub fn song_count(&self) -> u64 {
        self.songs.len() as u64
    }
}

/// How much of an album is yours from counts alone: songs of it you have, and how many it
/// has, where that's known.
#[uniffi::export]
pub fn ownership_of_counts(owned: u32, total: Option<u32>) -> Ownership {
    tracks_library::ownership_of_counts(owned, total).into()
}

// MARK: - Get More

#[derive(Debug, Clone, uniffi::Record)]
pub struct ArtistTaste {
    pub name: String,
    pub plays: u32,
    pub last_played_ms: Option<i64>,
}

/// An artist like one you play, closest first for each.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SimilarArtistLink {
    pub of: String,
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GetCandidateKind {
    Song,
    Album,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GetCandidate {
    pub id: String,
    pub kind: GetCandidateKind,
    pub artist: String,
    /// From 0 to 1; anything at 1 is left out unless it's been asked for.
    pub owned_share: f64,
    pub is_requested: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum GetMoreReason {
    YourArtist { artist: String },
    LikeYourArtist { of: String },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GetMorePick {
    pub id: String,
    pub reason: GetMoreReason,
    pub score: f64,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct GetMoreRules {
    pub limit: u32,
    pub per_artist: u32,
    pub now_ms: i64,
    pub half_life_days: f64,
}

/// What you don't own, ranked by what you play: your artists first, then artists like them,
/// albums you own part of before ones you have nothing of, artists kept apart.
#[uniffi::export]
pub fn rank_get_more(
    candidates: Vec<GetCandidate>,
    taste: Vec<ArtistTaste>,
    similar: Vec<SimilarArtistLink>,
    rules: GetMoreRules,
) -> Vec<GetMorePick> {
    let candidates: Vec<tracks_library::Candidate> = candidates
        .into_iter()
        .map(|c| tracks_library::Candidate {
            id: c.id,
            kind: match c.kind {
                GetCandidateKind::Song => tracks_library::CandidateKind::Song,
                GetCandidateKind::Album => tracks_library::CandidateKind::Album,
            },
            artist: c.artist,
            owned_share: c.owned_share,
            is_requested: c.is_requested,
        })
        .collect();
    let taste: Vec<tracks_library::ArtistTaste> = taste
        .into_iter()
        .map(|t| tracks_library::ArtistTaste {
            name: t.name,
            plays: t.plays,
            last_played_ms: t.last_played_ms,
        })
        .collect();
    let similar: Vec<tracks_library::SimilarArtist> = similar
        .into_iter()
        .map(|s| tracks_library::SimilarArtist {
            of: s.of,
            name: s.name,
        })
        .collect();
    let rules = tracks_library::Rules {
        limit: rules.limit as usize,
        per_artist: rules.per_artist as usize,
        now_ms: rules.now_ms,
        half_life_days: rules.half_life_days,
    };
    tracks_library::rank(&candidates, &taste, &similar, &rules)
        .into_iter()
        .map(|pick| GetMorePick {
            id: pick.id,
            reason: match pick.reason {
                tracks_library::Reason::YourArtist { artist } => {
                    GetMoreReason::YourArtist { artist }
                }
                tracks_library::Reason::LikeYourArtist { of } => {
                    GetMoreReason::LikeYourArtist { of }
                }
            },
            score: pick.score,
        })
        .collect()
}

// MARK: - Fetching

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct FetchSources {
    /// A server you can reach that finds songs it doesn't have.
    pub server_finds: bool,
    /// Lidarr, connected.
    pub lidarr: bool,
}

impl From<FetchSources> for tracks_library::FetchSources {
    fn from(sources: FetchSources) -> Self {
        Self {
            server_finds: sources.server_finds,
            lidarr: sources.lidarr,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GetRoute {
    Server,
    Lidarr,
}

impl From<tracks_library::GetRoute> for GetRoute {
    fn from(route: tracks_library::GetRoute) -> Self {
        match route {
            tracks_library::GetRoute::Server => Self::Server,
            tracks_library::GetRoute::Lidarr => Self::Lidarr,
        }
    }
}

/// Whether a search answer shows its server finds songs it doesn't have.
#[uniffi::export]
pub fn search_shows_finds(returned: u32, outside_library: u32) -> bool {
    tracks_library::search_shows_finds(returned, outside_library)
}

/// Whether anything can fetch, so the places that offer to get music show at all.
#[uniffi::export]
pub fn can_get(sources: FetchSources) -> bool {
    tracks_library::can_get(sources.into())
}

#[uniffi::export]
pub fn song_get_route(
    found_on_server: bool,
    has_album: bool,
    sources: FetchSources,
) -> Option<GetRoute> {
    tracks_library::song_route(found_on_server, has_album, sources.into()).map(Into::into)
}

#[uniffi::export]
pub fn album_get_route(found_on_server: bool, sources: FetchSources) -> Option<GetRoute> {
    tracks_library::album_route(found_on_server, sources.into()).map(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn name(title: &str, artist: &str) -> SongName {
        SongName {
            title: title.into(),
            artist: artist.into(),
        }
    }

    #[test]
    fn an_album_crosses_the_boundary_with_its_missing_songs() {
        let library = OwnedLibrary::new(vec![name("Two", "Mara Solis")]);
        let album = library.classify_album(vec![
            name("One", "Mara Solis"),
            name("Two", "Mara Solis"),
            name("Three", "Mara Solis"),
        ]);
        assert_eq!(album.ownership, Ownership::PartlyOwned);
        assert_eq!(album.missing, [0, 2]);
        assert_eq!(album.owned_count, 1);
        assert_eq!(library.song_count(), 1);
        assert!(library.owns("two".into(), "MARA SOLIS".into()));
    }

    #[test]
    fn picks_cross_the_boundary_with_their_reasons() {
        let picks = rank_get_more(
            vec![
                GetCandidate {
                    id: "like".into(),
                    kind: GetCandidateKind::Song,
                    artist: "Nils Frahm".into(),
                    owned_share: 0.0,
                    is_requested: false,
                },
                GetCandidate {
                    id: "yours".into(),
                    kind: GetCandidateKind::Album,
                    artist: "Mara Solis".into(),
                    owned_share: 0.5,
                    is_requested: false,
                },
                GetCandidate {
                    id: "owned".into(),
                    kind: GetCandidateKind::Album,
                    artist: "Mara Solis".into(),
                    owned_share: 1.0,
                    is_requested: false,
                },
            ],
            vec![ArtistTaste {
                name: "Mara Solis".into(),
                plays: 30,
                last_played_ms: None,
            }],
            vec![SimilarArtistLink {
                of: "Mara Solis".into(),
                name: "Nils Frahm".into(),
            }],
            GetMoreRules {
                limit: 10,
                per_artist: 3,
                now_ms: 0,
                half_life_days: 90.0,
            },
        );
        let ids: Vec<&str> = picks.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["yours", "like"]);
        assert_eq!(
            picks[1].reason,
            GetMoreReason::LikeYourArtist {
                of: "Mara Solis".into()
            }
        );
    }

    #[test]
    fn routes_cross_the_boundary() {
        let both = FetchSources {
            server_finds: true,
            lidarr: true,
        };
        assert!(can_get(both));
        assert!(!can_get(FetchSources {
            server_finds: false,
            lidarr: false
        }));
        assert_eq!(song_get_route(true, true, both), Some(GetRoute::Server));
        assert_eq!(album_get_route(true, both), Some(GetRoute::Lidarr));
        assert!(search_shows_finds(60, 48));
    }
}
