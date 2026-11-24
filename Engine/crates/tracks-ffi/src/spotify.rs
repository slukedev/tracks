//! Spotify, built only with the `spotify` feature. See `tracks-spotify` for how it works.
//!
//! Every call blocks for a bounded time; call off the main thread. `SpotifyStream.read` blocks
//! while audio downloads, so pull it from a feeder thread into a buffer the render callback
//! reads, never from the render callback itself.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tracks_spotify as spotify;

/// What went wrong, for the app to say plainly.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SpotifyError {
    #[error("Spotify Premium is needed to play")]
    NotPremium,
    /// Spotify refuses audio keys to this kind of client for accounts it hasn't seen on one
    /// before (since November 2026). There is no fix; browsing still works.
    #[error("Spotify refused to play this on Tracks")]
    AudioKeyRefused,
    #[error("network: {detail}")]
    Network { detail: String },
    #[error("rate limited, retry in {retry_after_secs} s")]
    RateLimited { retry_after_secs: u64 },
    /// The sign-in failed or was revoked; sign in again.
    #[error("sign-in: {detail}")]
    Auth { detail: String },
    #[error("unavailable: {detail}")]
    Unavailable { detail: String },
}

impl From<spotify::SpotifyError> for SpotifyError {
    fn from(error: spotify::SpotifyError) -> Self {
        match error {
            spotify::SpotifyError::NotPremium => Self::NotPremium,
            spotify::SpotifyError::AudioKeyRefused => Self::AudioKeyRefused,
            spotify::SpotifyError::Network { detail } => Self::Network { detail },
            spotify::SpotifyError::RateLimited { retry_after_secs } => {
                Self::RateLimited { retry_after_secs }
            }
            spotify::SpotifyError::Auth { detail } => Self::Auth { detail },
            spotify::SpotifyError::Unavailable { detail } => Self::Unavailable { detail },
        }
    }
}

type Result<T> = std::result::Result<T, SpotifyError>;

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyConfig {
    /// The OS version as the platform reports it (`UIDevice.systemVersion`,
    /// `Build.VERSION.RELEASE`). Spotify refuses phones that report none.
    pub os_version: Option<String>,
    /// Where partly downloaded audio goes; the system's temporary folder if unset.
    pub temp_dir: Option<String>,
}

impl From<SpotifyConfig> for spotify::Config {
    fn from(config: SpotifyConfig) -> Self {
        Self {
            os_version: config.os_version,
            temp_dir: config.temp_dir.map(PathBuf::from),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SpotifyBitrate {
    Low,
    Normal,
    High,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct SpotifyStreamOptions {
    pub bitrate: SpotifyBitrate,
    pub normalise: bool,
}

impl From<SpotifyStreamOptions> for spotify::StreamOptions {
    fn from(options: SpotifyStreamOptions) -> Self {
        Self {
            bitrate: match options.bitrate {
                SpotifyBitrate::Low => spotify::Bitrate::Low,
                SpotifyBitrate::Normal => spotify::Bitrate::Normal,
                SpotifyBitrate::High => spotify::Bitrate::High,
            },
            normalise: options.normalise,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyArtistRef {
    pub uri: String,
    pub name: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyAlbumRef {
    pub uri: String,
    pub title: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyTrack {
    pub uri: String,
    pub title: String,
    pub artists: Vec<SpotifyArtistRef>,
    pub album: Option<SpotifyAlbumRef>,
    pub duration_ms: u32,
    pub explicit: bool,
    pub isrc: Option<String>,
    pub artwork_url: Option<String>,
    pub track_number: u32,
    pub disc_number: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyAlbum {
    pub uri: String,
    pub title: String,
    pub artists: Vec<SpotifyArtistRef>,
    pub release_year: Option<i32>,
    pub artwork_url: Option<String>,
    pub track_uris: Vec<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyArtist {
    pub uri: String,
    pub name: String,
    pub artwork_url: Option<String>,
    pub top_track_uris: Vec<String>,
    pub album_uris: Vec<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyPlaylist {
    pub uri: String,
    pub name: String,
    pub owner: Option<String>,
    pub artwork_url: Option<String>,
    pub track_uris: Vec<String>,
    pub track_count: u32,
}

fn artist_refs(artists: Vec<spotify::ArtistRef>) -> Vec<SpotifyArtistRef> {
    artists
        .into_iter()
        .map(|a| SpotifyArtistRef {
            uri: a.uri,
            name: a.name,
        })
        .collect()
}

impl From<spotify::Track> for SpotifyTrack {
    fn from(t: spotify::Track) -> Self {
        Self {
            uri: t.uri,
            title: t.title,
            artists: artist_refs(t.artists),
            album: t.album.map(|a| SpotifyAlbumRef {
                uri: a.uri,
                title: a.title,
            }),
            duration_ms: t.duration_ms,
            explicit: t.explicit,
            isrc: t.isrc,
            artwork_url: t.artwork_url,
            track_number: t.track_number,
            disc_number: t.disc_number,
        }
    }
}

impl From<spotify::Album> for SpotifyAlbum {
    fn from(a: spotify::Album) -> Self {
        Self {
            uri: a.uri,
            title: a.title,
            artists: artist_refs(a.artists),
            release_year: a.release_year,
            artwork_url: a.artwork_url,
            track_uris: a.track_uris,
        }
    }
}

impl From<spotify::Artist> for SpotifyArtist {
    fn from(a: spotify::Artist) -> Self {
        Self {
            uri: a.uri,
            name: a.name,
            artwork_url: a.artwork_url,
            top_track_uris: a.top_track_uris,
            album_uris: a.album_uris,
        }
    }
}

impl From<spotify::Playlist> for SpotifyPlaylist {
    fn from(p: spotify::Playlist) -> Self {
        Self {
            uri: p.uri,
            name: p.name,
            owner: p.owner,
            artwork_url: p.artwork_url,
            track_uris: p.track_uris,
            track_count: p.track_count,
        }
    }
}

macro_rules! page {
    ($name:ident, $item:ty) => {
        /// One page; `next_offset` is where the next starts, or nothing at the end.
        #[derive(Debug, Clone, uniffi::Record)]
        pub struct $name {
            pub items: Vec<$item>,
            pub total: u32,
            pub next_offset: Option<u32>,
        }

        impl<T: Into<$item>> From<spotify::Page<T>> for $name {
            fn from(page: spotify::Page<T>) -> Self {
                Self {
                    items: page.items.into_iter().map(Into::into).collect(),
                    total: page.total,
                    next_offset: page.next_offset,
                }
            }
        }
    };
}

page!(SpotifyTrackPage, SpotifyTrack);
page!(SpotifyAlbumPage, SpotifyAlbum);
page!(SpotifyArtistPage, SpotifyArtist);
page!(SpotifyPlaylistPage, SpotifyPlaylist);

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifySearchResults {
    pub tracks: SpotifyTrackPage,
    pub albums: SpotifyAlbumPage,
    pub artists: SpotifyArtistPage,
    pub playlists: SpotifyPlaylistPage,
}

impl From<spotify::SearchResults> for SpotifySearchResults {
    fn from(r: spotify::SearchResults) -> Self {
        Self {
            tracks: r.tracks.into(),
            albums: r.albums.into(),
            artists: r.artists.into(),
            playlists: r.playlists.into(),
        }
    }
}

/// A sign-in. Open `url` in the system browser; for the loopback redirect
/// (`http://127.0.0.1:<port>/login`) call `wait_for_redirect` meanwhile, then `finish` with
/// what it returns.
#[derive(Debug, uniffi::Object)]
pub struct SpotifyLogin {
    login: spotify::Login,
}

#[uniffi::export]
impl SpotifyLogin {
    #[uniffi::constructor]
    pub fn new(redirect_uri: String) -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            login: spotify::Login::new(&redirect_uri)?,
        }))
    }

    pub fn url(&self) -> String {
        self.login.url().to_owned()
    }

    /// Waits up to `timeout_secs` for the browser to come back, and returns the address.
    pub fn wait_for_redirect(&self, timeout_secs: u32) -> Result<String> {
        Ok(self
            .login
            .wait_for_redirect(Duration::from_secs(timeout_secs.into()))?)
    }

    pub fn cancel(&self) {
        self.login.cancel();
    }

    /// Finishes with the redirect address. Save the account's `credentials` in the
    /// Keychain or Keystore; the OAuth tokens are never kept.
    pub fn finish(
        &self,
        callback_url: String,
        config: SpotifyConfig,
    ) -> Result<Arc<SpotifyAccount>> {
        let account = self.login.finish(&callback_url, config.into())?;
        Ok(Arc::new(SpotifyAccount { account }))
    }
}

/// A signed-in account. Opening the same account twice shares one session.
#[derive(Debug, uniffi::Object)]
pub struct SpotifyAccount {
    account: spotify::Account,
}

#[uniffi::export]
impl SpotifyAccount {
    /// Opens the account saved as `credentials`. Connects on first use.
    #[uniffi::constructor]
    pub fn open(credentials: Vec<u8>, config: SpotifyConfig) -> Result<Arc<Self>> {
        Ok(Arc::new(Self {
            account: spotify::Account::open(&credentials, config.into())?,
        }))
    }

    pub fn username(&self) -> String {
        self.account.username()
    }

    /// The latest credentials; save them again after `connect`, as Spotify may rotate them.
    pub fn credentials(&self) -> Vec<u8> {
        self.account.credentials()
    }

    /// Whether the account has Premium, once known.
    pub fn is_premium(&self) -> Option<bool> {
        self.account.is_premium()
    }

    pub fn connect(&self) -> Result<()> {
        Ok(self.account.connect()?)
    }

    /// Ends the session. Delete the saved credentials too.
    pub fn sign_out(&self) {
        self.account.sign_out();
    }

    pub fn track(&self, uri: String) -> Result<SpotifyTrack> {
        Ok(self.account.track(&uri)?.into())
    }

    /// Tracks in the order asked, leaving out any no longer on Spotify.
    pub fn tracks(&self, uris: Vec<String>) -> Result<Vec<SpotifyTrack>> {
        Ok(self
            .account
            .tracks(&uris)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn album(&self, uri: String) -> Result<SpotifyAlbum> {
        Ok(self.account.album(&uri)?.into())
    }

    pub fn artist(&self, uri: String) -> Result<SpotifyArtist> {
        Ok(self.account.artist(&uri)?.into())
    }

    pub fn playlist(&self, uri: String) -> Result<SpotifyPlaylist> {
        Ok(self.account.playlist(&uri)?.into())
    }

    pub fn saved_tracks(&self, offset: u32, limit: u32) -> Result<SpotifyTrackPage> {
        Ok(self.account.saved_tracks(offset, limit)?.into())
    }

    pub fn saved_albums(&self, offset: u32, limit: u32) -> Result<SpotifyAlbumPage> {
        Ok(self.account.saved_albums(offset, limit)?.into())
    }

    pub fn playlists(&self, offset: u32, limit: u32) -> Result<SpotifyPlaylistPage> {
        Ok(self.account.playlists(offset, limit)?.into())
    }

    /// At most 10 of each kind a page, Spotify's limit.
    pub fn search(&self, query: String, offset: u32, limit: u32) -> Result<SpotifySearchResults> {
        Ok(self.account.search(&query, offset, limit)?.into())
    }

    /// Opens a song for decoding.
    pub fn open_stream(
        &self,
        uri: String,
        options: SpotifyStreamOptions,
    ) -> Result<Arc<SpotifyStream>> {
        Ok(Arc::new(SpotifyStream {
            stream: self.account.open_stream(&uri, options.into())?,
        }))
    }
}

/// A song's audio as interleaved 32-bit float PCM, 44.1 kHz stereo.
#[derive(Debug, uniffi::Object)]
pub struct SpotifyStream {
    stream: spotify::Stream,
}

#[uniffi::export]
impl SpotifyStream {
    pub fn sample_rate(&self) -> u32 {
        self.stream.sample_rate()
    }

    pub fn channels(&self) -> u16 {
        self.stream.channels()
    }

    pub fn duration_ms(&self) -> u32 {
        self.stream.duration_ms()
    }

    pub fn position_ms(&self) -> u32 {
        self.stream.position_ms()
    }

    /// Up to `max_frames` frames (twice as many samples); empty at the end.
    pub fn read(&self, max_frames: u32) -> Result<Vec<f32>> {
        Ok(self.stream.read(max_frames)?)
    }

    /// Returns where it landed.
    pub fn seek(&self, position_ms: u32) -> Result<u32> {
        Ok(self.stream.seek(position_ms)?)
    }

    pub fn close(&self) {
        self.stream.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_error_keeps_its_meaning_across_the_boundary() {
        assert!(matches!(
            SpotifyError::from(spotify::SpotifyError::AudioKeyRefused),
            SpotifyError::AudioKeyRefused
        ));
        assert!(matches!(
            SpotifyError::from(spotify::SpotifyError::RateLimited {
                retry_after_secs: 9
            }),
            SpotifyError::RateLimited {
                retry_after_secs: 9
            }
        ));
    }

    #[test]
    fn a_damaged_saved_sign_in_is_an_auth_error() {
        let config = SpotifyConfig {
            os_version: Some("18.2".into()),
            temp_dir: None,
        };
        assert!(matches!(
            SpotifyAccount::open(b"nope".to_vec(), config),
            Err(SpotifyError::Auth { .. })
        ));
    }
}
