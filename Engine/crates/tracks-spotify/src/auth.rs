//! Signing in: OAuth's authorization code flow with PKCE, split so the host can show the page
//! in the system browser (`ASWebAuthenticationSession`, Custom Tabs) and hand back where it
//! redirected. Only librespot's reusable credentials outlive sign-in; the OAuth tokens are
//! used once to connect and dropped.

use std::fmt;

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bytes::Bytes;
use http::Request;
use http::header::{ACCEPT, CONTENT_TYPE};
use sha2::{Digest, Sha256};
use url::Url;

use crate::error::{Result, SpotifyError};
use crate::http::Transport;

/// The client ID librespot signs in with, whose redirect accepts any loopback port at
/// `http://127.0.0.1:<port>/login`.
pub const LIBRESPOT_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

const AUTHORIZE_URL: &str = "https://accounts.spotify.com/authorize";
const TOKEN_URL: &str = "https://accounts.spotify.com/api/token";

/// The scopes librespot asks for that Tracks needs: playback, and reading the library.
pub(crate) const SCOPES: &[&str] = &[
    "streaming",
    "user-read-private",
    "user-library-read",
    "playlist-read-private",
    "playlist-read-collaborative",
];

/// A sign-in that has started: the page to show, and what's needed to finish it.
pub struct Authorization {
    client_id: String,
    redirect_uri: String,
    state: String,
    verifier: String,
    url: String,
}

impl fmt::Debug for Authorization {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Authorization")
            .field("redirect_uri", &self.redirect_uri)
            .finish_non_exhaustive()
    }
}

fn random_token(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::fill(&mut buf[..]);
    URL_SAFE_NO_PAD.encode(buf)
}

fn challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

impl Authorization {
    pub fn new(client_id: &str, redirect_uri: &str) -> Result<Self> {
        Url::parse(redirect_uri)
            .map_err(|_| SpotifyError::auth("the redirect address is not a URL"))?;
        // 64 random bytes give an 86-character verifier, inside RFC 7636's 43 to 128.
        let verifier = random_token(64);
        let state = random_token(16);
        let mut url = Url::parse(AUTHORIZE_URL).map_err(|e| SpotifyError::auth(e.to_string()))?;
        url.query_pairs_mut()
            .append_pair("response_type", "code")
            .append_pair("client_id", client_id)
            .append_pair("redirect_uri", redirect_uri)
            .append_pair("scope", &SCOPES.join(" "))
            .append_pair("state", &state)
            .append_pair("code_challenge_method", "S256")
            .append_pair("code_challenge", &challenge(&verifier));
        Ok(Self {
            client_id: client_id.to_owned(),
            redirect_uri: redirect_uri.to_owned(),
            state,
            verifier,
            url: url.into(),
        })
    }

    /// The page to open.
    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn redirect_uri(&self) -> &str {
        &self.redirect_uri
    }

    /// The code from the address Spotify redirected to, once its state matches this sign-in.
    pub(crate) fn code_from(&self, callback: &str) -> Result<String> {
        let url = Url::parse(callback)
            .map_err(|_| SpotifyError::auth("the sign-in did not come back"))?;
        let mut code = None;
        let mut state = None;
        let mut error = None;
        for (key, value) in url.query_pairs() {
            match key.as_ref() {
                "code" => code = Some(value.into_owned()),
                "state" => state = Some(value.into_owned()),
                "error" => error = Some(value.into_owned()),
                _ => {}
            }
        }
        if state.as_deref() != Some(self.state.as_str()) {
            return Err(SpotifyError::auth("the sign-in did not match"));
        }
        if let Some(error) = error {
            return Err(SpotifyError::auth(if error == "access_denied" {
                "sign-in was cancelled".to_owned()
            } else {
                format!("Spotify refused the sign-in ({error})")
            }));
        }
        code.ok_or_else(|| SpotifyError::auth("the sign-in did not come back"))
    }

    /// Trades the redirect for an access token, which is good for connecting once.
    pub(crate) async fn exchange(
        &self,
        transport: &impl Transport,
        callback: &str,
    ) -> Result<AccessToken> {
        let code = self.code_from(callback)?;
        let body = form_urlencoded::Serializer::new(String::new())
            .append_pair("grant_type", "authorization_code")
            .append_pair("code", &code)
            .append_pair("redirect_uri", &self.redirect_uri)
            .append_pair("client_id", &self.client_id)
            .append_pair("code_verifier", &self.verifier)
            .finish();
        let request = Request::post(TOKEN_URL)
            .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
            .header(ACCEPT, "application/json")
            .body(Bytes::from(body))
            .map_err(|e| SpotifyError::auth(e.to_string()))?;
        let response = transport.send(request).await?;
        if response.status == 400 || response.status == 401 {
            let reason = serde_json::from_slice::<TokenError>(&response.body)
                .map(|e| e.error)
                .unwrap_or_default();
            return Err(SpotifyError::auth(format!(
                "Spotify refused the sign-in ({reason})"
            )));
        }
        if !response.is_success() {
            return Err(SpotifyError::from_status(
                response.status,
                response.retry_after,
            ));
        }
        serde_json::from_slice::<AccessToken>(&response.body)
            .map_err(|_| SpotifyError::auth("Spotify's answer to the sign-in was unreadable"))
    }
}

#[derive(serde::Deserialize)]
pub(crate) struct AccessToken {
    pub access_token: String,
}

impl fmt::Debug for AccessToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("AccessToken(..)")
    }
}

#[derive(serde::Deserialize)]
struct TokenError {
    error: String,
}

/// The loopback address and port a redirect points at, when it's one Tracks can listen on.
pub(crate) fn loopback_port(redirect_uri: &str) -> Option<u16> {
    let url = Url::parse(redirect_uri).ok()?;
    (url.scheme() == "http" && url.host_str() == Some("127.0.0.1"))
        .then(|| url.port())
        .flatten()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::{ScriptedTransport, ok, status};

    const REDIRECT: &str = "http://127.0.0.1:8898/login";

    fn authorization() -> Authorization {
        Authorization::new(LIBRESPOT_CLIENT_ID, REDIRECT).expect("authorization")
    }

    fn query(url: &str, key: &str) -> Option<String> {
        Url::parse(url)
            .ok()?
            .query_pairs()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.into_owned())
    }

    #[test]
    fn the_page_asks_for_a_code_with_an_s256_challenge_of_the_verifier() {
        let auth = authorization();
        assert_eq!(query(auth.url(), "response_type").as_deref(), Some("code"));
        assert_eq!(
            query(auth.url(), "code_challenge_method").as_deref(),
            Some("S256")
        );
        assert_eq!(
            query(auth.url(), "code_challenge"),
            Some(challenge(&auth.verifier))
        );
        assert_eq!(query(auth.url(), "redirect_uri").as_deref(), Some(REDIRECT));
    }

    #[test]
    fn verifiers_are_long_enough_and_never_repeat() {
        let (a, b) = (authorization(), authorization());
        assert!((43..=128).contains(&a.verifier.len()));
        assert_ne!(a.verifier, b.verifier);
        assert_ne!(a.state, b.state);
    }

    #[test]
    fn the_challenge_matches_rfc_7636s_example() {
        assert_eq!(
            challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
    }

    #[test]
    fn a_redirect_with_another_state_is_refused() {
        let auth = authorization();
        let result = auth.code_from(&format!("{REDIRECT}?code=abc&state=forged"));
        assert!(matches!(result, Err(SpotifyError::Auth { .. })));
    }

    #[test]
    fn a_cancelled_sign_in_says_so() {
        let auth = authorization();
        let result = auth.code_from(&format!(
            "{REDIRECT}?error=access_denied&state={}",
            auth.state
        ));
        assert_eq!(result, Err(SpotifyError::auth("sign-in was cancelled")));
    }

    #[tokio::test]
    async fn the_exchange_sends_the_code_and_verifier_and_reads_the_token() {
        let auth = authorization();
        let transport = ScriptedTransport::answering([ok(
            r#"{"access_token":"at","token_type":"Bearer","expires_in":3600,"refresh_token":"rt"}"#,
        )]);
        let token = auth
            .exchange(
                &transport,
                &format!("{REDIRECT}?code=abc&state={}", auth.state),
            )
            .await
            .expect("token");
        assert_eq!(token.access_token, "at");
        let asked = transport.asked.lock().expect("asked");
        let body = String::from_utf8(asked[0].2.to_vec()).expect("utf8");
        assert!(body.contains("code=abc"));
        assert!(body.contains(&format!("code_verifier={}", auth.verifier)));
        assert!(!body.contains("client_secret"));
    }

    #[tokio::test]
    async fn a_refused_exchange_is_an_auth_error() {
        let auth = authorization();
        let transport = ScriptedTransport::answering([status(400, None)]);
        let result = auth
            .exchange(
                &transport,
                &format!("{REDIRECT}?code=abc&state={}", auth.state),
            )
            .await;
        assert!(matches!(result, Err(SpotifyError::Auth { .. })));
    }

    #[test]
    fn tokens_never_show_in_debug_output() {
        let token = AccessToken {
            access_token: "secret".into(),
        };
        assert!(!format!("{token:?}").contains("secret"));
        assert!(!format!("{:?}", authorization()).contains("code_challenge"));
    }

    #[test]
    fn only_http_loopback_redirects_are_listened_on() {
        assert_eq!(loopback_port(REDIRECT), Some(8898));
        assert_eq!(loopback_port("tracks://spotify/callback"), None);
        assert_eq!(loopback_port("https://127.0.0.1:8898/login"), None);
    }
}
