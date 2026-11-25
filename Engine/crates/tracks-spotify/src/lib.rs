//! Spotify for Tracks, on [librespot](https://github.com/librespot-org/librespot): sign-in,
//! a session kept alive across flaky networks, metadata, the saved library and search, and
//! decoded audio the host renders itself.
//!
//! Every call is synchronous, thread-safe and time-limited; hosts call it off their main
//! thread. One Tokio runtime, owned here, does the networking.
//!
//! Spotify stopped handing audio keys to clients like this one for accounts it hasn't seen on
//! them before (librespot issue 1649, November 2026). Opening a stream then fails with
//! [`SpotifyError::AudioKeyRefused`]; everything else keeps working.

mod account;
mod auth;
mod credentials;
mod error;
mod http;
mod login;
mod model;
mod runtime;
mod stream;
mod webapi;

pub use account::{Account, Bitrate, Config, Stream, StreamOptions};
pub use auth::LIBRESPOT_CLIENT_ID;
pub use error::{Result, SpotifyError};
pub use login::Login;
pub use model::{Album, AlbumRef, Artist, ArtistRef, Page, Playlist, SearchResults, Track};
pub use stream::{CHANNELS, SAMPLE_RATE};
