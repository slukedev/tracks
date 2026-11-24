//! One HTTP request at a time, behind a trait so the Web API and sign-in can be tested
//! without a network.

use std::future::Future;
use std::time::Duration;

use bytes::Bytes;
use http::Request;
use http::header::RETRY_AFTER;
use http_body_util::BodyExt;
use librespot_core::http_client::HttpClient;

use crate::error::{Result, SpotifyError};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone)]
pub(crate) struct Response {
    pub status: u16,
    pub retry_after: Option<Duration>,
    pub body: Bytes,
}

impl Response {
    pub(crate) const fn is_success(&self) -> bool {
        self.status >= 200 && self.status < 300
    }
}

pub(crate) trait Transport: Send + Sync {
    fn send(&self, request: Request<Bytes>) -> impl Future<Output = Result<Response>> + Send;
}

/// Retry-After in seconds. Spotify sends seconds; anything else waits a little.
pub(crate) fn retry_after(value: Option<&str>) -> Option<Duration> {
    let value = value?.trim();
    Some(
        value
            .parse::<u64>()
            .map_or(Duration::from_secs(5), Duration::from_secs),
    )
}

/// librespot's client: its TLS, proxy and user agent, without its retry loop, which sleeps
/// for as long as a 429 asks.
pub(crate) struct LibrespotTransport<'a>(pub &'a HttpClient);

impl Transport for LibrespotTransport<'_> {
    async fn send(&self, request: Request<Bytes>) -> Result<Response> {
        let exchange = async {
            let response = self
                .0
                .request_fut(request)
                .map_err(SpotifyError::from)?
                .await
                .map_err(|error| SpotifyError::network(error.to_string()))?;
            let status = response.status().as_u16();
            let retry_after = retry_after(
                response
                    .headers()
                    .get(RETRY_AFTER)
                    .and_then(|v| v.to_str().ok()),
            );
            let body = response
                .into_body()
                .collect()
                .await
                .map_err(|error| SpotifyError::network(error.to_string()))?
                .to_bytes();
            Ok(Response {
                status,
                retry_after,
                body,
            })
        };
        tokio::time::timeout(REQUEST_TIMEOUT, exchange)
            .await
            .unwrap_or_else(|_| Err(SpotifyError::network("request timed out")))
    }
}

#[cfg(test)]
pub(crate) mod fake {
    use std::collections::VecDeque;
    use std::sync::Mutex;

    use super::*;

    /// Answers requests from a script, and remembers what was asked.
    #[derive(Debug, Default)]
    pub(crate) struct ScriptedTransport {
        answers: Mutex<VecDeque<Response>>,
        pub asked: Mutex<Vec<(String, Option<String>, Bytes)>>,
    }

    impl ScriptedTransport {
        pub(crate) fn answering(answers: impl IntoIterator<Item = Response>) -> Self {
            Self {
                answers: Mutex::new(answers.into_iter().collect()),
                asked: Mutex::default(),
            }
        }

        pub(crate) fn asked_uris(&self) -> Vec<String> {
            self.asked
                .lock()
                .expect("asked")
                .iter()
                .map(|(uri, _, _)| uri.clone())
                .collect()
        }
    }

    pub(crate) fn ok(json: &str) -> Response {
        Response {
            status: 200,
            retry_after: None,
            body: Bytes::copy_from_slice(json.as_bytes()),
        }
    }

    pub(crate) fn status(status: u16, retry_after_secs: Option<u64>) -> Response {
        Response {
            status,
            retry_after: retry_after_secs.map(Duration::from_secs),
            body: Bytes::new(),
        }
    }

    impl Transport for ScriptedTransport {
        fn send(&self, request: Request<Bytes>) -> impl Future<Output = Result<Response>> + Send {
            let auth = request
                .headers()
                .get(http::header::AUTHORIZATION)
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned);
            self.asked.lock().expect("asked").push((
                request.uri().to_string(),
                auth,
                request.body().clone(),
            ));
            let answer = self
                .answers
                .lock()
                .expect("answers")
                .pop_front()
                .ok_or_else(|| SpotifyError::network("script ran out"));
            std::future::ready(answer)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retry_after_reads_seconds_and_falls_back_on_anything_else() {
        assert_eq!(retry_after(Some("12")), Some(Duration::from_secs(12)));
        assert_eq!(
            retry_after(Some("Wed, 21 Oct 2026 07:28:00 GMT")),
            Some(Duration::from_secs(5))
        );
        assert_eq!(retry_after(None), None);
    }
}
