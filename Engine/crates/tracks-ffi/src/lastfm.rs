//! Last.fm, sans-IO: the engine builds and signs each request and reads each answer, the host
//! sends it with its own HTTP stack (`URLSession`, `OkHttp`).

use std::sync::Arc;

/// Something Last.fm, or reading its answer, went wrong with.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum LastFmError {
    #[error("Last.fm is not configured in this build")]
    NotConfigured,
    #[error("no Last.fm account is connected")]
    NotConnected,
    #[error("the Last.fm token has not been authorised yet")]
    NotAuthorised,
    #[error("Last.fm answered HTTP {status}")]
    Http { status: u16 },
    #[error("Last.fm error {code}: {detail}")]
    Service { code: i64, detail: String },
    #[error("Last.fm's answer could not be read")]
    MalformedResponse,
}

impl From<tracks_lastfm::LastFmError> for LastFmError {
    fn from(error: tracks_lastfm::LastFmError) -> Self {
        use tracks_lastfm::LastFmError as E;
        match error {
            E::NotConfigured => Self::NotConfigured,
            E::NotConnected => Self::NotConnected,
            E::NotAuthorised => Self::NotAuthorised,
            E::Http { status } => Self::Http { status },
            E::Service { code, message } => Self::Service {
                code,
                detail: message,
            },
            E::MalformedResponse => Self::MalformedResponse,
        }
    }
}

impl LastFmError {
    fn inner(&self) -> tracks_lastfm::LastFmError {
        use tracks_lastfm::LastFmError as E;
        match self {
            Self::NotConfigured => E::NotConfigured,
            Self::NotConnected => E::NotConnected,
            Self::NotAuthorised => E::NotAuthorised,
            Self::Http { status } => E::Http { status: *status },
            Self::Service { code, detail } => E::Service {
                code: *code,
                message: detail.clone(),
            },
            Self::MalformedResponse => E::MalformedResponse,
        }
    }
}

/// Whether trying again later may work.
#[uniffi::export]
pub fn lastfm_error_is_retryable(error: &LastFmError) -> bool {
    error.inner().is_retryable()
}

/// Whether the account needs reconnecting.
#[uniffi::export]
pub fn lastfm_error_is_account_problem(error: &LastFmError) -> bool {
    error.inner().is_account_problem()
}

type Result<T> = std::result::Result<T, LastFmError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HttpMethod {
    Get,
    Post,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct HttpHeader {
    pub name: String,
    pub value: String,
}

/// A request for the host to send exactly as is.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HttpRequest {
    pub method: HttpMethod,
    pub url: String,
    pub headers: Vec<HttpHeader>,
    pub body: Vec<u8>,
}

impl From<tracks_lastfm::HttpRequest> for HttpRequest {
    fn from(r: tracks_lastfm::HttpRequest) -> Self {
        Self {
            method: match r.method {
                tracks_lastfm::Method::Get => HttpMethod::Get,
                tracks_lastfm::Method::Post => HttpMethod::Post,
            },
            url: r.url,
            headers: r
                .headers
                .into_iter()
                .map(|(name, value)| HttpHeader { name, value })
                .collect(),
            body: r.body,
        }
    }
}

/// What came back. A transport failure is the host's to report, never one of these.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

impl From<HttpResponse> for tracks_lastfm::HttpResponse {
    fn from(r: HttpResponse) -> Self {
        Self::new(r.status, r.body)
    }
}

/// The connected account.
#[derive(Clone, uniffi::Record)]
pub struct LastFmSession {
    pub username: String,
    pub session_key: String,
}

impl std::fmt::Debug for LastFmSession {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LastFmSession")
            .field("username", &self.username)
            .field("session_key", &"<redacted>")
            .finish()
    }
}

impl From<&LastFmSession> for tracks_lastfm::Session {
    fn from(s: &LastFmSession) -> Self {
        Self::new(s.username.clone(), s.session_key.clone())
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Scrobble {
    pub artist: String,
    pub track: String,
    pub album: Option<String>,
    /// Unix seconds.
    pub played_at: i64,
}

/// A `track.scrobble` request, and how many of the scrobbles given went into it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScrobbleSubmission {
    pub request: HttpRequest,
    pub sent: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ScrobbleOutcome {
    Accepted,
    Ignored {
        code: i64,
        message: String,
        retryable: bool,
    },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SimilarTrack {
    pub title: String,
    pub artist_name: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TrackInfo {
    pub listeners: Option<i64>,
    pub scrobbles: Option<i64>,
    pub your_scrobbles: Option<i64>,
    pub tags: Vec<String>,
    pub summary: Option<String>,
    pub url: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ScrobbledTrack {
    pub title: String,
    pub artist_name: String,
    pub album_title: Option<String>,
    pub artwork_url: Option<String>,
    /// Unix seconds.
    pub played_at: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ScrobbleHistoryPage {
    pub scrobbles: Vec<ScrobbledTrack>,
    pub total: i64,
    pub total_pages: i64,
}

/// Builds Last.fm requests signed for this application. Never touches the network.
#[derive(Debug, uniffi::Object)]
pub struct LastFmClient {
    client: tracks_lastfm::Client,
}

#[uniffi::export]
impl LastFmClient {
    #[uniffi::constructor]
    pub fn new(api_key: String, secret: String, user_agent: String) -> Arc<Self> {
        Arc::new(Self {
            client: tracks_lastfm::Client::new(api_key, secret, user_agent),
        })
    }

    /// Step one: `auth.getToken`. Read with `lastfm_parse_token`.
    pub fn auth_get_token(&self) -> HttpRequest {
        self.client.auth_get_token().into()
    }

    /// Where the person approves `token`.
    pub fn authorisation_url(&self, token: String) -> String {
        self.client.authorisation_url(&token)
    }

    /// Step two: `auth.getSession`. Read with `lastfm_parse_session`.
    pub fn auth_get_session(&self, token: String) -> HttpRequest {
        self.client.auth_get_session(&token).into()
    }

    /// Up to 50 scrobbles in one request, or `None` for none.
    pub fn scrobble(
        &self,
        scrobbles: Vec<Scrobble>,
        session: LastFmSession,
    ) -> Option<ScrobbleSubmission> {
        let batch: Vec<tracks_lastfm::Scrobble> = scrobbles
            .into_iter()
            .map(|s| {
                let scrobble = tracks_lastfm::Scrobble::new(s.artist, s.track, s.played_at);
                match s.album {
                    Some(album) => scrobble.with_album(album),
                    None => scrobble,
                }
            })
            .collect();
        self.client
            .scrobble(&batch, &(&session).into())
            .map(|submission| ScrobbleSubmission {
                request: submission.request.into(),
                sent: submission.sent as u32,
            })
    }

    pub fn update_now_playing(
        &self,
        artist: String,
        track: String,
        album: Option<String>,
        session: LastFmSession,
    ) -> HttpRequest {
        self.client
            .update_now_playing(&artist, &track, album.as_deref(), &(&session).into())
            .into()
    }

    pub fn recent_tracks(
        &self,
        session: LastFmSession,
        after: Option<i64>,
        before: Option<i64>,
        limit: u32,
    ) -> HttpRequest {
        self.client
            .recent_tracks(&(&session).into(), after, before, limit)
            .into()
    }

    pub fn track_info(
        &self,
        title: String,
        artist: String,
        username: Option<String>,
    ) -> HttpRequest {
        self.client
            .track_info(&title, &artist, username.as_deref())
            .into()
    }

    pub fn similar_tracks(&self, title: String, artist: String, limit: u32) -> HttpRequest {
        self.client.similar_tracks(&title, &artist, limit).into()
    }
}

#[uniffi::export]
pub fn lastfm_parse_token(response: HttpResponse) -> Result<String> {
    Ok(tracks_lastfm::parse_token(&response.into())?)
}

#[uniffi::export]
pub fn lastfm_parse_session(response: HttpResponse) -> Result<LastFmSession> {
    let session = tracks_lastfm::parse_session(&response.into())?;
    Ok(LastFmSession {
        username: session.username.clone(),
        session_key: session.session_key.clone(),
    })
}

#[uniffi::export]
pub fn lastfm_parse_now_playing(response: HttpResponse) -> Result<()> {
    Ok(tracks_lastfm::parse_now_playing(&response.into())?)
}

/// What Last.fm did with each scrobble sent, in order.
#[uniffi::export]
pub fn lastfm_parse_scrobbles(response: HttpResponse, sent: u32) -> Result<Vec<ScrobbleOutcome>> {
    Ok(
        tracks_lastfm::parse_scrobbles(&response.into(), sent as usize)?
            .into_iter()
            .map(|outcome| {
                let retryable = outcome.is_retryable();
                match outcome {
                    tracks_lastfm::ScrobbleOutcome::Accepted => ScrobbleOutcome::Accepted,
                    tracks_lastfm::ScrobbleOutcome::Ignored { code, message } => {
                        ScrobbleOutcome::Ignored {
                            code,
                            message,
                            retryable,
                        }
                    }
                }
            })
            .collect(),
    )
}

#[uniffi::export]
pub fn lastfm_parse_track_info(
    response: HttpResponse,
    title: String,
    artist: String,
) -> Result<Option<TrackInfo>> {
    Ok(
        tracks_lastfm::parse_track_info(&response.into(), &title, &artist)?.map(|info| TrackInfo {
            listeners: info.listeners,
            scrobbles: info.scrobbles,
            your_scrobbles: info.your_scrobbles,
            tags: info.tags,
            summary: info.summary,
            url: info.url,
        }),
    )
}

#[uniffi::export]
pub fn lastfm_parse_similar_tracks(response: HttpResponse) -> Result<Vec<SimilarTrack>> {
    Ok(tracks_lastfm::parse_similar_tracks(&response.into())?
        .into_iter()
        .map(|t| SimilarTrack {
            title: t.title,
            artist_name: t.artist_name,
        })
        .collect())
}

#[uniffi::export]
pub fn lastfm_parse_recent_tracks(response: HttpResponse) -> Result<ScrobbleHistoryPage> {
    let page = tracks_lastfm::parse_recent_tracks(&response.into())?;
    Ok(ScrobbleHistoryPage {
        total: page.total,
        total_pages: page.total_pages,
        scrobbles: page
            .scrobbles
            .into_iter()
            .map(|s| ScrobbledTrack {
                title: s.title,
                artist_name: s.artist_name,
                album_title: s.album_title,
                artwork_url: s.artwork_url,
                played_at: s.played_at,
            })
            .collect(),
    })
}

/// Whether a track of `duration` seconds, played for `played_for`, counts for Last.fm.
#[uniffi::export]
pub fn lastfm_should_scrobble(duration: f64, played_for: f64) -> bool {
    tracks_lastfm::policy::should_scrobble(duration, played_for)
}

/// Seconds a track must play to count, or `None` when it never can.
#[uniffi::export]
pub fn lastfm_required_playback(duration: Option<f64>) -> Option<f64> {
    tracks_lastfm::policy::required_playback(duration)
}
