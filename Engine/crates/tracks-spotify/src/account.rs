//! One signed-in account: its session, kept alive and reconnected with backoff, and
//! everything asked of Spotify through it.

use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::{Arc, LazyLock, Mutex, PoisonError, Weak};
use std::time::Duration;

use futures_util::{StreamExt, stream};
use librespot_audio::{AudioDecrypt, AudioFile};
use librespot_core::audio_key::AudioKey;
use librespot_core::authentication::Credentials;
use librespot_core::{FileId, Session, SessionConfig, SpotifyId, SpotifyUri};
use librespot_metadata::Metadata;
use librespot_metadata::audio::{AudioFileFormat, AudioFiles, AudioItem};
use librespot_playback::decoder::SymphoniaDecoder;
use symphonia::core::probe::Hint;

use crate::auth::{LIBRESPOT_CLIENT_ID, SCOPES};
use crate::credentials::StoredCredentials;
use crate::error::{Result, SpotifyError};
use crate::http::LibrespotTransport;
use crate::model::{Album, Artist, Page, Playlist, SearchResults, Track};
use crate::runtime::{Backoff, block_on};
use crate::stream::{LibrespotSource, PcmStream, Subfile, normalisation_factor};
use crate::webapi::{TokenSource, WebApi};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
const CALL_LIMIT: Duration = Duration::from_secs(60);
const OPEN_LIMIT: Duration = Duration::from_secs(45);
const PARALLEL_LOOKUPS: usize = 8;
/// Spotify's Ogg files start with a page of their own; the real stream follows.
const OGG_HEADER_END: u64 = 0xa7;
const NORMALISATION_OFFSET: u64 = 144;

/// Settings the host supplies.
#[derive(Debug, Clone, Default)]
pub struct Config {
    /// The OS version as the platform reports it ("18.2", "15"). Spotify refuses sign-ins from
    /// phones that report none.
    pub os_version: Option<String>,
    /// Where partly downloaded audio goes; the system's temporary folder if not set.
    pub temp_dir: Option<PathBuf>,
}

impl Config {
    pub(crate) fn apply(&self) {
        if let Some(version) = &self.os_version {
            sysinfo::set_os_version(version);
        }
    }

    fn session_config(&self, device_id: &str) -> SessionConfig {
        let mut config = SessionConfig {
            client_id: LIBRESPOT_CLIENT_ID.to_owned(),
            device_id: device_id.to_owned(),
            ..SessionConfig::default()
        };
        if let Some(dir) = &self.temp_dir {
            config.tmp_dir.clone_from(dir);
        }
        config
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Bitrate {
    Low,
    #[default]
    Normal,
    High,
}

impl Bitrate {
    /// Formats to try, best match first. Only the ones librespot's decoder reads.
    const fn formats(self) -> [AudioFileFormat; 7] {
        use AudioFileFormat::{
            MP3_96, MP3_160, MP3_256, MP3_320, OGG_VORBIS_96, OGG_VORBIS_160, OGG_VORBIS_320,
        };
        match self {
            Self::Low => [
                OGG_VORBIS_96,
                MP3_96,
                OGG_VORBIS_160,
                MP3_160,
                MP3_256,
                OGG_VORBIS_320,
                MP3_320,
            ],
            Self::Normal => [
                OGG_VORBIS_160,
                MP3_160,
                OGG_VORBIS_96,
                MP3_96,
                MP3_256,
                OGG_VORBIS_320,
                MP3_320,
            ],
            Self::High => [
                OGG_VORBIS_320,
                MP3_320,
                MP3_256,
                OGG_VORBIS_160,
                MP3_160,
                OGG_VORBIS_96,
                MP3_96,
            ],
        }
    }
}

const fn bytes_per_second(format: AudioFileFormat) -> usize {
    use AudioFileFormat::{MP3_96, MP3_160, MP3_256, OGG_VORBIS_96, OGG_VORBIS_160};
    let kbps = match format {
        OGG_VORBIS_96 | MP3_96 => 96,
        OGG_VORBIS_160 | MP3_160 => 160,
        MP3_256 => 256,
        _ => 320,
    };
    kbps * 1000 / 8
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct StreamOptions {
    pub bitrate: Bitrate,
    /// Bring the track to Spotify's reference loudness.
    pub normalise: bool,
}

/// A track's decoded audio. See [`crate::stream`].
pub struct Stream {
    inner: Mutex<PcmStream<LibrespotSource>>,
}

impl std::fmt::Debug for Stream {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Stream").finish_non_exhaustive()
    }
}

impl Stream {
    fn lock(&self) -> std::sync::MutexGuard<'_, PcmStream<LibrespotSource>> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub const fn sample_rate(&self) -> u32 {
        crate::stream::SAMPLE_RATE
    }

    pub const fn channels(&self) -> u16 {
        crate::stream::CHANNELS
    }

    pub fn duration_ms(&self) -> u32 {
        self.lock().duration_ms()
    }

    pub fn position_ms(&self) -> u32 {
        self.lock().position_ms()
    }

    /// Up to `max_frames` interleaved stereo frames; empty at the end. Blocks while audio
    /// downloads, up to librespot's download timeout.
    pub fn read(&self, max_frames: u32) -> Result<Vec<f32>> {
        self.lock().read(max_frames)
    }

    pub fn seek(&self, position_ms: u32) -> Result<u32> {
        self.lock().seek(position_ms)
    }

    /// Stops the download. Reads after this return nothing.
    pub fn close(&self) {
        self.lock().close();
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        self.lock().close();
    }
}

struct Inner {
    config: Config,
    stored: Mutex<StoredCredentials>,
    session: tokio::sync::Mutex<Option<Session>>,
}

impl Drop for Inner {
    fn drop(&mut self) {
        if let Some(session) = self.session.get_mut().take() {
            session.shutdown();
        }
    }
}

/// Open accounts by username, so asking for the same one twice shares its session.
static ACCOUNTS: LazyLock<Mutex<HashMap<String, Weak<Inner>>>> = LazyLock::new(Mutex::default);

/// A signed-in Spotify account. Cheap to clone; every method is safe to call from any
/// thread except inside an async runtime, and blocks for at most a minute.
#[derive(Clone)]
pub struct Account {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Account {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Account")
            .field("username", &self.username())
            .finish_non_exhaustive()
    }
}

fn parse_uri(uri: &str) -> Result<SpotifyUri> {
    SpotifyUri::from_uri(uri.trim()).map_err(|_| SpotifyError::unavailable("not a Spotify link"))
}

impl Account {
    /// Opens the account saved as `credentials` (from a sign-in), connecting on first use.
    pub fn open(credentials: &[u8], config: Config) -> Result<Self> {
        let stored = StoredCredentials::from_blob(credentials)?;
        config.apply();
        let mut accounts = ACCOUNTS.lock().unwrap_or_else(PoisonError::into_inner);
        accounts.retain(|_, weak| weak.strong_count() > 0);
        if let Some(inner) = accounts.get(stored.username()).and_then(Weak::upgrade) {
            return Ok(Self { inner });
        }
        let username = stored.username().to_owned();
        let inner = Arc::new(Inner {
            config,
            stored: Mutex::new(stored),
            session: tokio::sync::Mutex::new(None),
        });
        accounts.insert(username, Arc::downgrade(&inner));
        Ok(Self { inner })
    }

    /// An account from a session that just signed in.
    pub(crate) fn adopt(stored: StoredCredentials, session: Session, config: Config) -> Self {
        let mut accounts = ACCOUNTS.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(inner) = accounts.get(stored.username()).and_then(Weak::upgrade) {
            *inner.stored.lock().unwrap_or_else(PoisonError::into_inner) = stored;
            if let Ok(mut slot) = inner.session.try_lock() {
                if let Some(old) = slot.replace(session) {
                    old.shutdown();
                }
            } else {
                session.shutdown();
            }
            return Self { inner };
        }
        let username = stored.username().to_owned();
        let inner = Arc::new(Inner {
            config,
            stored: Mutex::new(stored),
            session: tokio::sync::Mutex::new(Some(session)),
        });
        accounts.insert(username, Arc::downgrade(&inner));
        Self { inner }
    }

    fn stored(&self) -> StoredCredentials {
        self.inner
            .stored
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    pub fn username(&self) -> String {
        self.stored().username().to_owned()
    }

    /// The latest reusable credentials. Spotify can rotate them on connect, so the host saves
    /// these again after `connect`.
    pub fn credentials(&self) -> Vec<u8> {
        self.stored().to_blob()
    }

    async fn connect_once(&self) -> Result<Session> {
        let stored = self.stored();
        let session = Session::new(self.inner.config.session_config(&stored.device_id), None);
        tokio::time::timeout(
            CONNECT_TIMEOUT,
            session.connect(stored.credentials.clone(), false),
        )
        .await
        .map_err(|_| SpotifyError::network("connecting timed out"))?
        .map_err(SpotifyError::from)?;
        let auth_data = session.auth_data();
        if !auth_data.is_empty() && auth_data != stored.credentials.auth_data {
            *self
                .inner
                .stored
                .lock()
                .unwrap_or_else(PoisonError::into_inner) = stored.with_auth_data(auth_data);
        }
        Ok(session)
    }

    /// The live session, connecting (with backoff) if there is none or it dropped. Callers
    /// arriving while a connect is under way wait for it rather than starting another.
    async fn session(&self) -> Result<Session> {
        let mut slot = self.inner.session.lock().await;
        if let Some(session) = slot.as_ref().filter(|s| !s.is_invalid()) {
            return Ok(session.clone());
        }
        if let Some(old) = slot.take() {
            old.shutdown();
        }
        let session = Backoff::CONNECT.retry(|_| self.connect_once()).await?;
        *slot = Some(session.clone());
        Ok(session)
    }

    /// Runs `operation` on the session, once more on a fresh one if the connection dropped.
    async fn with_session<T, F, Fut>(&self, operation: F) -> Result<T>
    where
        F: Fn(Session) -> Fut,
        Fut: Future<Output = Result<T>>,
    {
        let session = self.session().await?;
        match operation(session.clone()).await {
            Err(error) if error.is_transient() && session.is_invalid() => {
                operation(self.session().await?).await
            }
            result => result,
        }
    }

    /// Connects now rather than on first use.
    pub fn connect(&self) -> Result<()> {
        block_on(CALL_LIMIT, async { self.session().await.map(drop) })
    }

    /// Whether the account has Premium, once Spotify has said; `None` before connecting.
    pub fn is_premium(&self) -> Option<bool> {
        let slot = self.inner.session.try_lock().ok()?;
        let session = slot.as_ref()?;
        session
            .get_user_attribute("type")
            .map(|kind| kind == "premium")
    }

    /// Ends the session and forgets the account. The host deletes its saved credentials.
    pub fn sign_out(&self) {
        let mut accounts = ACCOUNTS.lock().unwrap_or_else(PoisonError::into_inner);
        accounts.remove(&self.username());
        if let Ok(mut slot) = self.inner.session.try_lock()
            && let Some(session) = slot.take()
        {
            session.shutdown();
        }
    }

    pub fn track(&self, uri: &str) -> Result<Track> {
        let uri = parse_uri(uri)?;
        block_on(
            CALL_LIMIT,
            self.with_session(|session| {
                let uri = uri.clone();
                async move {
                    let track = librespot_metadata::Track::get(&session, &uri).await?;
                    Ok(Track::from(&track))
                }
            }),
        )
    }

    /// Tracks by URI, in order, leaving out any Spotify no longer has.
    pub fn tracks(&self, uris: &[String]) -> Result<Vec<Track>> {
        let uris = uris
            .iter()
            .map(|uri| parse_uri(uri))
            .collect::<Result<Vec<_>>>()?;
        block_on(
            CALL_LIMIT,
            self.with_session(|session| {
                let uris = uris.clone();
                async move {
                    let found: Vec<Result<Track>> = stream::iter(uris)
                        .map(|uri| {
                            let session = session.clone();
                            async move {
                                librespot_metadata::Track::get(&session, &uri)
                                    .await
                                    .map(|t| Track::from(&t))
                                    .map_err(SpotifyError::from)
                            }
                        })
                        .buffered(PARALLEL_LOOKUPS)
                        .collect()
                        .await;
                    found
                        .into_iter()
                        .filter(|r| !matches!(r, Err(SpotifyError::Unavailable { .. })))
                        .collect()
                }
            }),
        )
    }

    pub fn album(&self, uri: &str) -> Result<Album> {
        let uri = parse_uri(uri)?;
        block_on(
            CALL_LIMIT,
            self.with_session(|session| {
                let uri = uri.clone();
                async move {
                    let album = librespot_metadata::Album::get(&session, &uri).await?;
                    Ok(Album::from(&album))
                }
            }),
        )
    }

    pub fn artist(&self, uri: &str) -> Result<Artist> {
        let uri = parse_uri(uri)?;
        block_on(
            CALL_LIMIT,
            self.with_session(|session| {
                let uri = uri.clone();
                async move {
                    let artist = librespot_metadata::Artist::get(&session, &uri).await?;
                    Ok(Artist::from_metadata(&artist, &session.country()))
                }
            }),
        )
    }

    pub fn playlist(&self, uri: &str) -> Result<Playlist> {
        let uri = parse_uri(uri)?;
        block_on(
            CALL_LIMIT,
            self.with_session(|session| {
                let uri = uri.clone();
                async move {
                    let playlist = librespot_metadata::Playlist::get(&session, &uri).await?;
                    Ok(Playlist::from(&playlist))
                }
            }),
        )
    }

    async fn web<T, F, Fut>(&self, call: F) -> Result<T>
    where
        F: Fn(Session) -> Fut,
        Fut: Future<Output = Result<T>>,
    {
        self.with_session(call).await
    }

    pub fn saved_tracks(&self, offset: u32, limit: u32) -> Result<Page<Track>> {
        block_on(
            CALL_LIMIT,
            self.web(|session| async move {
                let transport = LibrespotTransport(session.http_client());
                let tokens = SessionTokens(&session);
                WebApi {
                    transport: &transport,
                    tokens: &tokens,
                }
                .saved_tracks(offset, limit)
                .await
            }),
        )
    }

    pub fn saved_albums(&self, offset: u32, limit: u32) -> Result<Page<Album>> {
        block_on(
            CALL_LIMIT,
            self.web(|session| async move {
                let transport = LibrespotTransport(session.http_client());
                let tokens = SessionTokens(&session);
                WebApi {
                    transport: &transport,
                    tokens: &tokens,
                }
                .saved_albums(offset, limit)
                .await
            }),
        )
    }

    pub fn playlists(&self, offset: u32, limit: u32) -> Result<Page<Playlist>> {
        block_on(
            CALL_LIMIT,
            self.web(|session| async move {
                let transport = LibrespotTransport(session.http_client());
                let tokens = SessionTokens(&session);
                WebApi {
                    transport: &transport,
                    tokens: &tokens,
                }
                .playlists(offset, limit)
                .await
            }),
        )
    }

    pub fn search(&self, query: &str, offset: u32, limit: u32) -> Result<SearchResults> {
        block_on(
            CALL_LIMIT,
            self.web(|session| async move {
                let transport = LibrespotTransport(session.http_client());
                let tokens = SessionTokens(&session);
                WebApi {
                    transport: &transport,
                    tokens: &tokens,
                }
                .search(query, offset, limit)
                .await
            }),
        )
    }

    /// Opens a track for decoding. Fails with `AudioKeyRefused` when Spotify won't hand over
    /// the key, rather than decoding noise or hanging.
    pub fn open_stream(&self, uri: &str, options: StreamOptions) -> Result<Stream> {
        let uri = parse_uri(uri)?;
        if !matches!(uri, SpotifyUri::Track { .. }) {
            return Err(SpotifyError::unavailable("only songs can be played"));
        }
        let (source, duration_ms, gain) = block_on(
            OPEN_LIMIT,
            self.with_session(|session| open_track(session, uri.clone(), options)),
        )?;
        Ok(Stream {
            inner: Mutex::new(PcmStream::new(source, duration_ms, gain)),
        })
    }
}

/// Web API tokens from the session: login5's, or keymaster's when that is refused.
struct SessionTokens<'a>(&'a Session);

impl TokenSource for SessionTokens<'_> {
    async fn token(&self, refresh: bool) -> Result<String> {
        if !refresh && let Ok(token) = self.0.login5().auth_token().await {
            return Ok(token.access_token);
        }
        let token = self.0.token_provider().get_token(&SCOPES.join(",")).await?;
        Ok(token.access_token)
    }
}

async fn playable_item(session: &Session, uri: SpotifyUri) -> Result<AudioItem> {
    let item = AudioItem::get_file(session, uri).await?;
    let Err(reason) = &item.availability else {
        return Ok(item);
    };
    for alternative in item.alternatives.iter().flat_map(|tracks| tracks.iter()) {
        if let Ok(candidate) = AudioItem::get_file(session, alternative.clone()).await
            && candidate.availability.is_ok()
        {
            return Ok(candidate);
        }
    }
    Err(SpotifyError::unavailable(reason.to_string()))
}

async fn audio_key(session: &Session, track: SpotifyId, file: FileId) -> Result<AudioKey> {
    // A slow answer is worth asking again; a refusal is final.
    let mut tries = 0;
    loop {
        tries += 1;
        match session.audio_key().request(track, file).await {
            Ok(key) => return Ok(key),
            Err(error) => {
                let error = SpotifyError::from(error);
                if !error.is_transient() || tries == 3 {
                    return Err(error);
                }
            }
        }
    }
}

fn read_normalisation(file: &mut (impl Read + Seek)) -> Option<(f32, f32)> {
    let mut data = [0u8; 8];
    file.seek(SeekFrom::Start(NORMALISATION_OFFSET)).ok()?;
    file.read_exact(&mut data).ok()?;
    let gain = f32::from_le_bytes([data[0], data[1], data[2], data[3]]);
    let peak = f32::from_le_bytes([data[4], data[5], data[6], data[7]]);
    (gain.is_finite() && peak.is_finite()).then_some((gain, peak))
}

async fn open_track(
    session: Session,
    uri: SpotifyUri,
    options: StreamOptions,
) -> Result<(LibrespotSource, u32, f32)> {
    if session
        .get_user_attribute("type")
        .is_some_and(|kind| kind != "premium")
    {
        return Err(SpotifyError::NotPremium);
    }
    let item = playable_item(&session, uri).await?;
    let (format, file_id) = options
        .bitrate
        .formats()
        .into_iter()
        .find_map(|format| item.files.get(&format).map(|&id| (format, id)))
        .ok_or_else(|| SpotifyError::unavailable("no format Tracks can play"))?;
    let track_id = SpotifyId::try_from(&item.track_id)
        .map_err(|_| SpotifyError::unavailable("not a Spotify song"))?;

    let file = AudioFile::open(&session, file_id, bytes_per_second(format)).await?;
    let controller = file.get_stream_loader_controller()?;
    let key = match audio_key(&session, track_id, file_id).await {
        Ok(key) => key,
        Err(error) => {
            controller.close();
            return Err(error);
        }
    };
    let mut decrypted = AudioDecrypt::new(Some(key), file);

    // The reads below wait for the download, which runs on the runtime's workers; this
    // future runs on the caller's thread, so waiting here blocks no one else.
    let is_ogg = AudioFiles::is_ogg_vorbis(format);
    let gain = if options.normalise && is_ogg {
        read_normalisation(&mut decrypted)
            .map_or(1.0, |(gain, peak)| normalisation_factor(gain, peak))
    } else {
        1.0
    };
    let failure = Arc::default();
    let offset = if is_ogg { OGG_HEADER_END } else { 0 };
    let subfile = Subfile::new(
        decrypted,
        offset,
        controller.len() as u64,
        Arc::clone(&failure),
    )
    .map_err(|error| SpotifyError::network(error.to_string()))?;
    let mut hint = Hint::new();
    if let Some(mime) = AudioFiles::mime_type(format) {
        hint.mime_type(mime);
    }
    let decoder = match SymphoniaDecoder::new(subfile, hint) {
        Ok(decoder) => decoder,
        Err(error) => {
            controller.close();
            let timed_out = failure
                .lock()
                .ok()
                .and_then(|mut slot| slot.take())
                .is_some();
            return Err(if timed_out {
                SpotifyError::network("the audio stopped arriving")
            } else {
                SpotifyError::unavailable(error.to_string())
            });
        }
    };
    controller.set_stream_mode();
    Ok((
        LibrespotSource {
            decoder,
            controller,
            failure,
        },
        item.duration_ms,
        gain,
    ))
}

/// Connects with the access token from a sign-in and keeps only the reusable credentials it
/// earns.
pub(crate) async fn connect_with_token(
    access_token: String,
    config: &Config,
) -> Result<(StoredCredentials, Session)> {
    let device_id = crate::credentials::new_device_id();
    let session = Session::new(config.session_config(&device_id), None);
    tokio::time::timeout(
        CONNECT_TIMEOUT,
        session.connect(Credentials::with_access_token(access_token), false),
    )
    .await
    .map_err(|_| SpotifyError::network("connecting timed out"))?
    .map_err(SpotifyError::from)?;
    let username = session.username();
    let auth_data = session.auth_data();
    if username.is_empty() || auth_data.is_empty() {
        session.shutdown();
        return Err(SpotifyError::auth("Spotify did not keep the sign-in"));
    }
    Ok((
        StoredCredentials::new(device_id, username, auth_data),
        session,
    ))
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;
    use crate::credentials::new_device_id;

    fn blob(username: &str) -> Vec<u8> {
        StoredCredentials::new(new_device_id(), username.into(), b"opaque".to_vec()).to_blob()
    }

    #[test]
    fn opening_the_same_account_twice_shares_it() {
        let a = Account::open(&blob("shared"), Config::default()).expect("open");
        let b = Account::open(&blob("shared"), Config::default()).expect("open");
        assert!(Arc::ptr_eq(&a.inner, &b.inner));
    }

    #[test]
    fn different_accounts_are_kept_apart() {
        let a = Account::open(&blob("first"), Config::default()).expect("open");
        let b = Account::open(&blob("second"), Config::default()).expect("open");
        assert!(!Arc::ptr_eq(&a.inner, &b.inner));
    }

    #[test]
    fn signing_out_forgets_the_account() {
        let a = Account::open(&blob("leaving"), Config::default()).expect("open");
        a.sign_out();
        let b = Account::open(&blob("leaving"), Config::default()).expect("open");
        assert!(!Arc::ptr_eq(&a.inner, &b.inner));
    }

    #[test]
    fn an_account_hands_back_the_credentials_it_was_opened_with() {
        let saved = blob("keeper");
        let account = Account::open(&saved, Config::default()).expect("open");
        assert_eq!(account.credentials(), saved);
        assert_eq!(account.username(), "keeper");
        assert_eq!(account.is_premium(), None);
    }

    #[test]
    fn links_that_are_not_spotify_are_refused_before_connecting() {
        let account = Account::open(&blob("links"), Config::default()).expect("open");
        assert!(matches!(
            account.track("https://example.com"),
            Err(SpotifyError::Unavailable { .. })
        ));
        assert!(matches!(
            account.open_stream(
                "spotify:album:4aawyAB9vmqN3uQ7FjRGTy",
                StreamOptions::default()
            ),
            Err(SpotifyError::Unavailable { .. })
        ));
    }

    #[test]
    fn every_bitrate_offers_every_format_once() {
        for bitrate in [Bitrate::Low, Bitrate::Normal, Bitrate::High] {
            let formats = bitrate.formats();
            for (i, format) in formats.iter().enumerate() {
                assert!(!formats[i + 1..].contains(format));
            }
        }
        assert_eq!(Bitrate::High.formats()[0], AudioFileFormat::OGG_VORBIS_320);
        assert_eq!(bytes_per_second(AudioFileFormat::OGG_VORBIS_160), 20_000);
    }

    #[test]
    fn normalisation_data_is_read_from_spotifys_header() {
        let mut file = vec![0u8; 200];
        file[144..148].copy_from_slice(&(-3.5f32).to_le_bytes());
        file[148..152].copy_from_slice(&0.8f32.to_le_bytes());
        assert_eq!(
            read_normalisation(&mut Cursor::new(file)),
            Some((-3.5, 0.8))
        );
        assert_eq!(read_normalisation(&mut Cursor::new(vec![0u8; 100])), None);
    }

    #[test]
    fn sessions_use_librespots_client_and_the_saved_device() {
        let config = Config {
            os_version: None,
            temp_dir: Some(PathBuf::from("/tmp/tracks")),
        };
        let session = config.session_config("abc");
        assert_eq!(session.client_id, LIBRESPOT_CLIENT_ID);
        assert_eq!(session.device_id, "abc");
        assert_eq!(session.tmp_dir, PathBuf::from("/tmp/tracks"));
    }
}
