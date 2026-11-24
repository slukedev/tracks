use std::time::Duration;

use librespot_core::audio_key::AudioKeyError;
use librespot_core::error::ErrorKind;
use librespot_core::http_client::HttpClientError;

/// What went wrong, in terms the app can show. Details never hold a token or credential.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SpotifyError {
    /// Playing needs Spotify Premium.
    #[error("Spotify Premium is needed to play")]
    NotPremium,
    /// Spotify would not hand over the key for the audio. Since November 2026 it refuses
    /// clients like this one for accounts it hasn't seen on them before, and there is no fix.
    #[error("Spotify refused to play this on Tracks")]
    AudioKeyRefused,
    #[error("network: {detail}")]
    Network { detail: String },
    #[error("rate limited, retry in {retry_after_secs} s")]
    RateLimited { retry_after_secs: u64 },
    /// Sign-in failed or was revoked; signing in again is the fix.
    #[error("sign-in: {detail}")]
    Auth { detail: String },
    /// Not on Spotify, not in this country, or not something this build can play.
    #[error("unavailable: {detail}")]
    Unavailable { detail: String },
}

pub type Result<T> = std::result::Result<T, SpotifyError>;

impl SpotifyError {
    pub(crate) fn network(detail: impl Into<String>) -> Self {
        Self::Network {
            detail: detail.into(),
        }
    }

    pub(crate) fn auth(detail: impl Into<String>) -> Self {
        Self::Auth {
            detail: detail.into(),
        }
    }

    pub(crate) fn unavailable(detail: impl Into<String>) -> Self {
        Self::Unavailable {
            detail: detail.into(),
        }
    }

    pub(crate) fn rate_limited(retry_after: Duration) -> Self {
        Self::RateLimited {
            retry_after_secs: retry_after.as_secs().max(1),
        }
    }

    /// Whether trying again, after a pause or a reconnect, might work.
    pub(crate) const fn is_transient(&self) -> bool {
        matches!(self, Self::Network { .. })
    }

    /// The error for an HTTP status that isn't a success.
    pub(crate) fn from_status(status: u16, retry_after: Option<Duration>) -> Self {
        match status {
            401 => Self::auth("Spotify no longer accepts this sign-in"),
            403 => Self::auth("Spotify does not allow this"),
            404 => Self::unavailable("not on Spotify"),
            429 => Self::rate_limited(retry_after.unwrap_or(Duration::from_secs(5))),
            500.. => Self::network(format!("Spotify answered {status}")),
            _ => Self::unavailable(format!("Spotify answered {status}")),
        }
    }
}

impl From<librespot_core::Error> for SpotifyError {
    fn from(error: librespot_core::Error) -> Self {
        if let Some(key) = error.error.downcast_ref::<AudioKeyError>() {
            return match key {
                AudioKeyError::AesKey => Self::AudioKeyRefused,
                other => Self::network(other.to_string()),
            };
        }
        if let Some(HttpClientError::StatusCode(status)) =
            error.error.downcast_ref::<HttpClientError>()
        {
            return Self::from_status(status.as_u16(), None);
        }
        if error
            .error
            .downcast_ref::<librespot_metadata::MetadataError>()
            .is_some()
        {
            return Self::unavailable(error.error.to_string());
        }
        let detail = error.error.to_string();
        match error.kind {
            ErrorKind::PermissionDenied | ErrorKind::Unauthenticated
                if detail.contains("Premium") =>
            {
                Self::NotPremium
            }
            ErrorKind::PermissionDenied | ErrorKind::Unauthenticated => Self::Auth { detail },
            ErrorKind::ResourceExhausted => Self::rate_limited(Duration::from_secs(5)),
            ErrorKind::Unavailable
            | ErrorKind::DeadlineExceeded
            | ErrorKind::Aborted
            | ErrorKind::Cancelled => Self::Network { detail },
            _ => Self::Unavailable { detail },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_refused_audio_key_is_its_own_error() {
        let error: librespot_core::Error = AudioKeyError::AesKey.into();
        assert_eq!(SpotifyError::from(error), SpotifyError::AudioKeyRefused);
    }

    #[test]
    fn an_audio_key_timeout_is_a_network_error() {
        let error: librespot_core::Error = AudioKeyError::Timeout.into();
        assert!(SpotifyError::from(error).is_transient());
    }

    #[test]
    fn a_premium_login_failure_reads_as_not_premium() {
        let error = librespot_core::Error::permission_denied(
            "Login failed with reason: Premium account required",
        );
        assert_eq!(SpotifyError::from(error), SpotifyError::NotPremium);
    }

    #[test]
    fn bad_credentials_are_an_auth_error() {
        let error =
            librespot_core::Error::permission_denied("Login failed with reason: Bad credentials");
        assert!(matches!(
            SpotifyError::from(error),
            SpotifyError::Auth { .. }
        ));
    }

    #[test]
    fn statuses_map_to_what_the_app_can_do_about_them() {
        assert!(matches!(
            SpotifyError::from_status(401, None),
            SpotifyError::Auth { .. }
        ));
        assert!(matches!(
            SpotifyError::from_status(404, None),
            SpotifyError::Unavailable { .. }
        ));
        assert!(SpotifyError::from_status(503, None).is_transient());
        assert_eq!(
            SpotifyError::from_status(429, Some(Duration::from_secs(30))),
            SpotifyError::RateLimited {
                retry_after_secs: 30
            }
        );
    }
}
