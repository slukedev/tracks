//! What the host keeps in the Keychain or Keystore: librespot's reusable credentials and the
//! device ID they were issued to, as one opaque blob.

use std::fmt::{self, Write as _};

use librespot_core::authentication::Credentials;
use librespot_protocol::authentication::AuthenticationType;
use serde::{Deserialize, Serialize};

use crate::error::{Result, SpotifyError};

const VERSION: u8 = 1;

#[derive(Clone, PartialEq, Serialize, Deserialize)]
pub(crate) struct StoredCredentials {
    version: u8,
    pub device_id: String,
    pub credentials: Credentials,
}

impl fmt::Debug for StoredCredentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StoredCredentials")
            .field("username", &self.username())
            .finish_non_exhaustive()
    }
}

impl StoredCredentials {
    pub(crate) fn new(device_id: String, username: String, auth_data: Vec<u8>) -> Self {
        Self {
            version: VERSION,
            device_id,
            credentials: Credentials {
                username: Some(username),
                auth_type: AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS,
                auth_data,
            },
        }
    }

    pub(crate) fn username(&self) -> &str {
        self.credentials.username.as_deref().unwrap_or_default()
    }

    pub(crate) fn to_blob(&self) -> Vec<u8> {
        serde_json::to_vec(self).unwrap_or_default()
    }

    pub(crate) fn from_blob(blob: &[u8]) -> Result<Self> {
        let stored: Self = serde_json::from_slice(blob)
            .map_err(|_| SpotifyError::auth("the saved sign-in is unreadable"))?;
        if stored.version != VERSION
            || stored.username().is_empty()
            || stored.credentials.auth_data.is_empty()
        {
            return Err(SpotifyError::auth("the saved sign-in is unreadable"));
        }
        Ok(stored)
    }

    /// The same sign-in after Spotify rotated its reusable credentials.
    pub(crate) fn with_auth_data(&self, auth_data: Vec<u8>) -> Self {
        let mut next = self.clone();
        next.credentials.auth_data = auth_data;
        next
    }
}

/// A device ID for a new sign-in: 20 random bytes in hex, the shape Spotify's own use.
pub(crate) fn new_device_id() -> String {
    let mut bytes = [0u8; 20];
    rand::fill(&mut bytes);
    bytes.iter().fold(String::with_capacity(40), |mut hex, b| {
        let _ = write!(hex, "{b:02x}");
        hex
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stored() -> StoredCredentials {
        StoredCredentials::new(new_device_id(), "luke".into(), b"opaque".to_vec())
    }

    #[test]
    fn credentials_round_trip_through_their_blob() {
        let original = stored();
        assert_eq!(
            StoredCredentials::from_blob(&original.to_blob()),
            Ok(original)
        );
    }

    #[test]
    fn a_damaged_blob_is_an_auth_error() {
        assert!(matches!(
            StoredCredentials::from_blob(b"{"),
            Err(SpotifyError::Auth { .. })
        ));
    }

    #[test]
    fn a_blob_without_credentials_is_refused() {
        let empty = StoredCredentials::new(new_device_id(), "luke".into(), Vec::new());
        assert!(StoredCredentials::from_blob(&empty.to_blob()).is_err());
    }

    #[test]
    fn credentials_never_show_in_debug_output() {
        let text = format!("{:?}", stored());
        assert!(!text.contains("opaque") && !text.contains("b3BhcXVl"));
    }

    #[test]
    fn device_ids_are_forty_hex_digits() {
        let id = new_device_id();
        assert_eq!(id.len(), 40);
        assert!(id.bytes().all(|b| b.is_ascii_hexdigit()));
    }
}
