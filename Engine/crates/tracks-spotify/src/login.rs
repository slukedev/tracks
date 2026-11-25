//! A sign-in from start to finish, with an optional loopback listener for redirects to
//! `http://127.0.0.1:<port>/login`, which is the only redirect librespot's client ID accepts.

use std::sync::{Mutex, PoisonError};
use std::time::Duration;

use librespot_core::http_client::HttpClient;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::Notify;

use crate::account::{Account, Config, connect_with_token};
use crate::auth::{Authorization, LIBRESPOT_CLIENT_ID, loopback_port};
use crate::error::{Result, SpotifyError};
use crate::http::LibrespotTransport;
use crate::runtime::block_on;

const FINISH_LIMIT: Duration = Duration::from_secs(60);
const PAGE: &str = "HTTP/1.1 200 OK\r\ncontent-type: text/html; charset=utf-8\r\nconnection: close\r\n\r\n<!doctype html><meta name=viewport content=\"width=device-width\"><title>Tracks</title><p style=\"font:17px -apple-system,system-ui,sans-serif;text-align:center;margin-top:40vh\">You can close this page.</p>";

#[derive(Debug)]
pub struct Login {
    authorization: Authorization,
    listener: Mutex<Option<std::net::TcpListener>>,
    cancelled: Notify,
}

impl Login {
    /// Starts a sign-in that redirects to `redirect_uri`. For a loopback redirect the port is
    /// claimed now, before the page opens, so nothing else can take it.
    pub fn new(redirect_uri: &str) -> Result<Self> {
        let authorization = Authorization::new(LIBRESPOT_CLIENT_ID, redirect_uri)?;
        let listener = match loopback_port(redirect_uri) {
            Some(port) => {
                let listener = std::net::TcpListener::bind(("127.0.0.1", port)).map_err(|e| {
                    SpotifyError::auth(format!("cannot listen for the sign-in: {e}"))
                })?;
                listener
                    .set_nonblocking(true)
                    .map_err(|e| SpotifyError::auth(e.to_string()))?;
                Some(listener)
            }
            None => None,
        };
        Ok(Self {
            authorization,
            listener: Mutex::new(listener),
            cancelled: Notify::new(),
        })
    }

    /// The page to open in the system browser.
    pub fn url(&self) -> &str {
        self.authorization.url()
    }

    /// Waits for the browser to come back to the loopback address and returns where it
    /// landed, for `finish`.
    pub fn wait_for_redirect(&self, timeout: Duration) -> Result<String> {
        let listener = self
            .listener
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take()
            .ok_or_else(|| SpotifyError::auth("this sign-in is not listening"))?;
        block_on(timeout, async {
            let listener = tokio::net::TcpListener::from_std(listener)
                .map_err(|e| SpotifyError::auth(e.to_string()))?;
            loop {
                let (mut socket, _) = tokio::select! {
                    accepted = listener.accept() => accepted.map_err(|e| SpotifyError::auth(e.to_string()))?,
                    () = self.cancelled.notified() => return Err(SpotifyError::auth("sign-in was cancelled")),
                };
                let mut line = String::new();
                let (reader, mut writer) = socket.split();
                let read = BufReader::new(reader).read_line(&mut line).await;
                // Browsers also ask for /favicon.ico; only the redirect carries a query.
                let Some(target) = read.ok().and_then(|_| request_target(&line)) else {
                    continue;
                };
                let _ = writer.write_all(PAGE.as_bytes()).await;
                let _ = writer.shutdown().await;
                let origin = url::Url::parse(self.authorization.redirect_uri())
                    .map(|url| url.origin().ascii_serialization())
                    .unwrap_or_default();
                return Ok(format!("{origin}{target}"));
            }
        })
    }

    /// Stops a `wait_for_redirect` that is under way, or the next one.
    pub fn cancel(&self) {
        self.cancelled.notify_waiters();
        self.cancelled.notify_one();
    }

    /// Finishes with the address Spotify redirected to: trades it for a token, connects, and
    /// returns the account. Its `credentials` are what the host saves.
    pub fn finish(&self, callback: &str, config: Config) -> Result<Account> {
        config.apply();
        let (stored, session) = block_on(FINISH_LIMIT, async {
            let client = HttpClient::new(None);
            let token = self
                .authorization
                .exchange(&LibrespotTransport(&client), callback)
                .await?;
            connect_with_token(token.access_token, &config).await
        })?;
        Ok(Account::adopt(stored, session, config))
    }
}

/// The path and query of a request line that carries one.
fn request_target(line: &str) -> Option<&str> {
    let mut parts = line.split_whitespace();
    let (method, target) = (parts.next()?, parts.next()?);
    (method == "GET" && target.contains('?')).then_some(target)
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::TcpStream;

    use super::*;

    fn free_port() -> u16 {
        std::net::TcpListener::bind(("127.0.0.1", 0))
            .and_then(|l| l.local_addr())
            .map(|a| a.port())
            .expect("port")
    }

    #[test]
    fn only_requests_with_a_query_are_the_redirect() {
        assert_eq!(
            request_target("GET /login?code=a&state=b HTTP/1.1\r\n"),
            Some("/login?code=a&state=b")
        );
        assert_eq!(request_target("GET /favicon.ico HTTP/1.1\r\n"), None);
        assert_eq!(request_target("POST /login?x HTTP/1.1\r\n"), None);
    }

    #[test]
    fn the_loopback_listener_hands_back_the_redirect() {
        let port = free_port();
        let redirect = format!("http://127.0.0.1:{port}/login");
        let login = Login::new(&redirect).expect("login");
        let browser = std::thread::spawn(move || {
            let mut favicon = TcpStream::connect(("127.0.0.1", port)).expect("connect");
            favicon
                .write_all(b"GET /favicon.ico HTTP/1.1\r\n\r\n")
                .expect("write");
            drop(favicon);
            let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect");
            stream
                .write_all(b"GET /login?code=abc&state=xyz HTTP/1.1\r\nHost: x\r\n\r\n")
                .expect("write");
            let mut page = String::new();
            stream.read_to_string(&mut page).expect("read");
            page
        });
        let callback = login
            .wait_for_redirect(Duration::from_secs(10))
            .expect("redirect");
        assert_eq!(callback, format!("{redirect}?code=abc&state=xyz"));
        assert!(browser.join().expect("browser").contains("close this page"));
    }

    #[test]
    fn waiting_gives_up_at_the_timeout() {
        let login = Login::new(&format!("http://127.0.0.1:{}/login", free_port())).expect("login");
        let result = login.wait_for_redirect(Duration::from_millis(50));
        assert!(matches!(result, Err(SpotifyError::Network { .. })));
    }

    #[test]
    fn a_custom_scheme_redirect_does_not_listen() {
        let login = Login::new("tracks://spotify/login").expect("login");
        assert!(login.wait_for_redirect(Duration::from_millis(10)).is_err());
        assert!(
            login
                .url()
                .starts_with("https://accounts.spotify.com/authorize?")
        );
    }

    #[test]
    fn a_wait_can_be_cancelled() {
        let login = std::sync::Arc::new(
            Login::new(&format!("http://127.0.0.1:{}/login", free_port())).expect("login"),
        );
        let canceller = std::sync::Arc::clone(&login);
        let handle = std::thread::spawn(move || canceller.cancel());
        let result = login.wait_for_redirect(Duration::from_secs(10));
        handle.join().expect("join");
        assert_eq!(result, Err(SpotifyError::auth("sign-in was cancelled")));
    }
}
