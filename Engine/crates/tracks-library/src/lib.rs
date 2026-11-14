//! Your own music measured against what you could get: which of an album's songs you own,
//! what to get next from your listening, and whether a server can fetch at all.
//!
//! Pure and synchronous, with no I/O. The host talks to its servers (Subsonic, Octo, Lidarr)
//! with its own HTTP stack and hands the answers in; every platform then shows the same
//! ownership marks and the same "Get More" picks for the same library and history.
//!
//! ```
//! use tracks_library::{OwnedSongs, Ownership, classify_album};
//!
//! let owned = OwnedSongs::from_songs([("Havana", "Camila Cabello")]);
//! let album = classify_album([("Havana (feat. Young Thug)", "Camila Cabello & Young Thug"), ("Never Be the Same", "Camila Cabello")], &owned);
//! assert_eq!(album.ownership, Ownership::Partial);
//! assert_eq!(album.missing, [1]);
//! ```

mod fetch;
mod get_more;
mod ownership;

pub use fetch::{
    FetchSources, GetRoute, MIN_FINDS, album_route, can_get, search_shows_finds, song_route,
};
pub use get_more::{
    ArtistTaste, Candidate, CandidateKind, Pick, Reason, Rules, SimilarArtist, rank,
};
pub use ownership::{
    AlbumOwnership, OwnedSongs, Ownership, classify_album, loose_key, loose_title,
};
