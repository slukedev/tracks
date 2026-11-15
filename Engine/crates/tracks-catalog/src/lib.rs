//! Music you don't have yet, for every platform: searching Apple's free iTunes catalog,
//! asking Lidarr for albums, telling whether something is already yours, and ranking what to
//! suggest.
//!
//! Sans-IO like `tracks-lastfm`: each client builds a [`Request`] that the host sends with
//! its own HTTP stack, and reads the [`Response`] it hands back. Nothing here reads a clock
//! or opens a socket, so every behaviour is testable on recorded answers.
//!
//! ```
//! use tracks_catalog::{Itunes, Response, SearchKind};
//!
//! let itunes = Itunes::new("GB");
//! let request = itunes.search("Radiohead", SearchKind::Album, 10);
//! assert!(request.url.starts_with("https://itunes.apple.com/search?"));
//! let response = Response::new(200, r#"{"resultCount":0,"results":[]}"#);
//! assert!(tracks_catalog::parse_catalog(&response).expect("readable").albums.is_empty());
//! ```

mod error;
mod itunes;
pub mod lidarr;
pub mod matching;
pub mod suggest;
mod web;

pub use error::{CatalogError, Result};
pub use itunes::{
    AlbumKind, CatalogAlbum, CatalogArtist, CatalogResults, CatalogSong, Itunes, SearchKind,
    larger_artwork, parse_catalog,
};
pub use web::{Method, Request, Response, query_escape};
