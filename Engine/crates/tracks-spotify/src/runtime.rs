//! The crate's one Tokio runtime, and the blocking bridge the synchronous API runs on.

use std::future::Future;
use std::sync::OnceLock;
use std::time::Duration;

use tokio::runtime::{Builder, Handle, Runtime};

use crate::error::{Result, SpotifyError};

static RUNTIME: OnceLock<std::result::Result<Runtime, String>> = OnceLock::new();

fn runtime() -> Result<&'static Runtime> {
    RUNTIME
        .get_or_init(|| {
            Builder::new_multi_thread()
                .worker_threads(2)
                .thread_name("tracks-spotify")
                .enable_all()
                .build()
                .map_err(|error| error.to_string())
        })
        .as_ref()
        .map_err(|detail| SpotifyError::unavailable(format!("no runtime: {detail}")))
}

/// Runs `future` to completion on the calling thread, giving up after `limit` so no call
/// can hang. The future's own spawned work (downloads, the connection) runs on the
/// runtime's workers. Calling this from inside an async context is refused rather than
/// allowed to panic.
pub(crate) fn block_on<T>(limit: Duration, future: impl Future<Output = Result<T>>) -> Result<T> {
    if Handle::try_current().is_ok() {
        return Err(SpotifyError::unavailable(
            "called from inside an async runtime",
        ));
    }
    runtime()?.block_on(async {
        tokio::time::timeout(limit, future)
            .await
            .unwrap_or_else(|_| Err(SpotifyError::network("timed out")))
    })
}

/// How many times to try something that can fail on a bad connection, and how long to wait
/// between tries.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Backoff {
    pub attempts: u32,
    pub first_delay: Duration,
    pub max_delay: Duration,
}

impl Backoff {
    pub(crate) const CONNECT: Self = Self {
        attempts: 4,
        first_delay: Duration::from_millis(500),
        max_delay: Duration::from_secs(4),
    };

    fn delay(self, attempt: u32) -> Duration {
        let doubled = self.first_delay.saturating_mul(1 << attempt.min(16));
        let capped = doubled.min(self.max_delay);
        // Up to a quarter more, so many devices recovering at once don't retry in step.
        let jitter = capped.mul_f64(rand::random::<f64>() * 0.25);
        capped + jitter
    }

    /// Runs `operation` until it succeeds, fails in a way retrying can't fix, or runs out of
    /// attempts.
    pub(crate) async fn retry<T, F, Fut>(self, mut operation: F) -> Result<T>
    where
        F: FnMut(u32) -> Fut,
        Fut: Future<Output = Result<T>>,
    {
        let mut attempt = 0;
        loop {
            match operation(attempt).await {
                Err(error) if error.is_transient() && attempt + 1 < self.attempts => {
                    tokio::time::sleep(self.delay(attempt)).await;
                    attempt += 1;
                }
                result => return result,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU32, Ordering};

    use super::*;

    #[tokio::test(start_paused = true)]
    async fn network_errors_are_retried_until_the_attempts_run_out() {
        let calls = AtomicU32::new(0);
        let result: Result<()> = Backoff::CONNECT
            .retry(|_| async {
                calls.fetch_add(1, Ordering::Relaxed);
                Err(SpotifyError::network("down"))
            })
            .await;
        assert!(result.is_err());
        assert_eq!(calls.load(Ordering::Relaxed), Backoff::CONNECT.attempts);
    }

    #[tokio::test(start_paused = true)]
    async fn sign_in_errors_are_not_retried() {
        let calls = AtomicU32::new(0);
        let result: Result<()> = Backoff::CONNECT
            .retry(|_| async {
                calls.fetch_add(1, Ordering::Relaxed);
                Err(SpotifyError::auth("revoked"))
            })
            .await;
        assert!(matches!(result, Err(SpotifyError::Auth { .. })));
        assert_eq!(calls.load(Ordering::Relaxed), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn a_retry_that_succeeds_returns_its_value() {
        let result = Backoff::CONNECT
            .retry(|attempt| async move {
                if attempt < 2 {
                    Err(SpotifyError::network("flaky"))
                } else {
                    Ok(attempt)
                }
            })
            .await;
        assert_eq!(result, Ok(2));
    }

    #[test]
    fn delays_grow_and_stay_under_the_cap_with_jitter() {
        let backoff = Backoff::CONNECT;
        assert!(backoff.delay(0) >= backoff.first_delay);
        assert!(backoff.delay(10) <= backoff.max_delay.mul_f64(1.25));
    }

    #[test]
    fn blocking_calls_give_up_at_their_limit() {
        let result: Result<()> = block_on(Duration::from_millis(20), async {
            tokio::time::sleep(Duration::from_secs(60)).await;
            Ok(())
        });
        assert!(matches!(result, Err(SpotifyError::Network { .. })));
    }

    #[tokio::test]
    async fn blocking_from_inside_a_runtime_is_refused_not_a_panic() {
        let result = block_on(Duration::from_secs(1), async { Ok(()) });
        assert!(matches!(result, Err(SpotifyError::Unavailable { .. })));
    }
}
