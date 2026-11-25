//! The Web API calls librespot has no Mercury equivalent for: the saved library and search.
//! Rate limits are honoured: a short Retry-After is waited out once, a long one is handed to
//! the app as `RateLimited` instead of holding the call.

use std::future::Future;
use std::time::Duration;

use bytes::Bytes;
use http::Request;
use http::header::{ACCEPT, AUTHORIZATION};
use serde::de::DeserializeOwned;

use crate::error::{Result, SpotifyError};
use crate::http::Transport;
use crate::model::{
    Album, Page, Playlist, SavedAlbum, SavedTrack, SearchResults, Track, WebPage, WebPlaylist,
    WebSearch,
};

const BASE: &str = "https://api.spotify.com/v1";
/// The longest Retry-After waited out inside a call.
const LONGEST_WAIT: Duration = Duration::from_secs(3);
const ATTEMPTS: u32 = 3;
/// Spotify's ceiling for search pages since February 2026.
const SEARCH_LIMIT: u32 = 10;
const PAGE_LIMIT: u32 = 50;

/// Where Web API tokens come from: the session's, refreshed on request.
pub(crate) trait TokenSource {
    fn token(&self, refresh: bool) -> impl Future<Output = Result<String>>;
}

pub(crate) struct WebApi<'a, T, S> {
    pub transport: &'a T,
    pub tokens: &'a S,
}

impl<T: Transport, S: TokenSource> WebApi<'_, T, S> {
    async fn get<R: DeserializeOwned>(&self, path_and_query: &str) -> Result<R> {
        let mut refreshed = false;
        let mut attempt = 0;
        loop {
            attempt += 1;
            let token = self.tokens.token(refreshed).await?;
            let request = Request::get(format!("{BASE}{path_and_query}"))
                .header(AUTHORIZATION, format!("Bearer {token}"))
                .header(ACCEPT, "application/json")
                .body(Bytes::new())
                .map_err(|e| SpotifyError::unavailable(e.to_string()))?;
            let response = self.transport.send(request).await;
            let retry = match response {
                Ok(response) if response.is_success() => {
                    return serde_json::from_slice(&response.body)
                        .map_err(|_| SpotifyError::unavailable("Spotify's answer was unreadable"));
                }
                Ok(response) if response.status == 401 && !refreshed => {
                    refreshed = true;
                    continue;
                }
                Ok(response) => SpotifyError::from_status(response.status, response.retry_after),
                Err(error) => error,
            };
            let wait = match &retry {
                SpotifyError::RateLimited { retry_after_secs }
                    if Duration::from_secs(*retry_after_secs) <= LONGEST_WAIT =>
                {
                    Duration::from_secs(*retry_after_secs)
                }
                error if error.is_transient() => Duration::from_millis(500) * attempt,
                _ => return Err(retry),
            };
            if attempt >= ATTEMPTS {
                return Err(retry);
            }
            tokio::time::sleep(wait).await;
        }
    }

    fn paging(offset: u32, limit: u32, ceiling: u32) -> String {
        format!("offset={offset}&limit={}", limit.clamp(1, ceiling))
    }

    pub(crate) async fn saved_tracks(&self, offset: u32, limit: u32) -> Result<Page<Track>> {
        let page: WebPage<SavedTrack> = self
            .get(&format!(
                "/me/tracks?{}",
                Self::paging(offset, limit, PAGE_LIMIT)
            ))
            .await?;
        Ok(flattened(
            page.into_page(|saved| saved.track.map(Track::from)),
        ))
    }

    pub(crate) async fn saved_albums(&self, offset: u32, limit: u32) -> Result<Page<Album>> {
        let page: WebPage<SavedAlbum> = self
            .get(&format!(
                "/me/albums?{}",
                Self::paging(offset, limit, PAGE_LIMIT)
            ))
            .await?;
        Ok(flattened(
            page.into_page(|saved| saved.album.map(Album::from)),
        ))
    }

    pub(crate) async fn playlists(&self, offset: u32, limit: u32) -> Result<Page<Playlist>> {
        let page: WebPage<WebPlaylist> = self
            .get(&format!(
                "/me/playlists?{}",
                Self::paging(offset, limit, PAGE_LIMIT)
            ))
            .await?;
        Ok(page.into_page(Playlist::from))
    }

    pub(crate) async fn search(
        &self,
        query: &str,
        offset: u32,
        limit: u32,
    ) -> Result<SearchResults> {
        let query = query.trim();
        if query.is_empty() {
            return Ok(SearchResults {
                tracks: empty(),
                albums: empty(),
                artists: empty(),
                playlists: empty(),
            });
        }
        let q: String = form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let search: WebSearch = self
            .get(&format!(
                "/search?q={q}&type=track,album,artist,playlist&{}",
                Self::paging(offset, limit, SEARCH_LIMIT)
            ))
            .await?;
        Ok(SearchResults {
            tracks: search.tracks.into_page(Into::into),
            albums: search.albums.into_page(Into::into),
            artists: search.artists.into_page(Into::into),
            playlists: search.playlists.into_page(Into::into),
        })
    }
}

fn flattened<T>(page: Page<Option<T>>) -> Page<T> {
    Page {
        items: page.items.into_iter().flatten().collect(),
        total: page.total,
        next_offset: page.next_offset,
    }
}

const fn empty<T>() -> Page<T> {
    Page {
        items: Vec::new(),
        total: 0,
        next_offset: None,
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU32, Ordering};

    use super::*;
    use crate::http::fake::{ScriptedTransport, ok, status};

    #[derive(Default)]
    struct Tokens {
        refreshes: AtomicU32,
    }

    impl TokenSource for Tokens {
        fn token(&self, refresh: bool) -> impl Future<Output = Result<String>> {
            let token = if refresh {
                self.refreshes.fetch_add(1, Ordering::Relaxed);
                "fresh"
            } else {
                "stale"
            };
            std::future::ready(Ok(token.to_owned()))
        }
    }

    const SAVED: &str = r#"{
        "items": [
            {"added_at": "2026-11-01T00:00:00Z", "track": {
                "uri": "spotify:track:1", "name": "Halo",
                "artists": [{"uri": "spotify:artist:1", "name": "Beyoncé"}],
                "album": {"uri": "spotify:album:1", "name": "I Am", "images": [
                    {"url": "https://i.scdn.co/image/small", "width": 64},
                    {"url": "https://i.scdn.co/image/large", "width": 640}
                ]},
                "duration_ms": 261000, "explicit": false,
                "external_ids": {"isrc": "USSM10804555"}, "track_number": 2, "disc_number": 1
            }},
            {"added_at": "2026-11-01T00:00:00Z", "track": null}
        ],
        "total": 120, "offset": 50, "limit": 50, "next": "https://api.spotify.com/v1/me/tracks?offset=52"
    }"#;

    async fn run<R>(
        transport: &ScriptedTransport,
        tokens: &Tokens,
        call: impl AsyncFnOnce(&WebApi<'_, ScriptedTransport, Tokens>) -> R,
    ) -> R {
        let api = WebApi { transport, tokens };
        call(&api).await
    }

    #[tokio::test]
    async fn saved_tracks_read_into_tracks_and_skip_removed_ones() {
        let transport = ScriptedTransport::answering([ok(SAVED)]);
        let page = run(&transport, &Tokens::default(), async |api| {
            api.saved_tracks(50, 50).await
        })
        .await
        .expect("page");
        assert_eq!(page.items.len(), 1);
        let track = &page.items[0];
        assert_eq!(track.title, "Halo");
        assert_eq!(track.isrc.as_deref(), Some("USSM10804555"));
        assert_eq!(
            track.artwork_url.as_deref(),
            Some("https://i.scdn.co/image/large")
        );
        assert_eq!(page.total, 120);
        assert_eq!(page.next_offset, Some(52));
    }

    #[tokio::test]
    async fn a_rejected_token_is_refreshed_once() {
        let transport = ScriptedTransport::answering([status(401, None), ok(SAVED)]);
        let tokens = Tokens::default();
        let page = run(&transport, &tokens, async |api| {
            api.saved_tracks(0, 50).await
        })
        .await;
        assert!(page.is_ok());
        assert_eq!(tokens.refreshes.load(Ordering::Relaxed), 1);
        let asked = transport.asked.lock().expect("asked");
        assert_eq!(asked[1].1.as_deref(), Some("Bearer fresh"));
    }

    #[tokio::test]
    async fn a_token_rejected_twice_is_an_auth_error() {
        let transport = ScriptedTransport::answering([status(401, None), status(401, None)]);
        let result = run(&transport, &Tokens::default(), async |api| {
            api.playlists(0, 20).await
        })
        .await;
        assert!(matches!(result, Err(SpotifyError::Auth { .. })));
    }

    #[tokio::test(start_paused = true)]
    async fn a_short_rate_limit_is_waited_out() {
        let transport = ScriptedTransport::answering([status(429, Some(2)), ok(SAVED)]);
        let result = run(&transport, &Tokens::default(), async |api| {
            api.saved_tracks(0, 50).await
        })
        .await;
        assert!(result.is_ok());
    }

    #[tokio::test(start_paused = true)]
    async fn a_long_rate_limit_is_handed_back_at_once() {
        let transport = ScriptedTransport::answering([status(429, Some(120))]);
        let result = run(&transport, &Tokens::default(), async |api| {
            api.saved_tracks(0, 50).await
        })
        .await;
        assert_eq!(
            result,
            Err(SpotifyError::RateLimited {
                retry_after_secs: 120
            })
        );
        assert_eq!(transport.asked_uris().len(), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn server_errors_are_retried_then_reported_as_network() {
        let transport =
            ScriptedTransport::answering([status(503, None), status(502, None), status(500, None)]);
        let result = run(&transport, &Tokens::default(), async |api| {
            api.saved_albums(0, 50).await
        })
        .await;
        assert!(matches!(result, Err(SpotifyError::Network { .. })));
        assert_eq!(transport.asked_uris().len(), 3);
    }

    #[tokio::test]
    async fn search_escapes_the_query_and_keeps_to_spotifys_page_size() {
        let transport = ScriptedTransport::answering([ok(r#"{
            "tracks": {"items": [], "total": 0, "offset": 0, "next": null},
            "playlists": {"items": [null, {"uri": "spotify:playlist:1", "name": "Mix",
                "owner": {"display_name": "Luke"}, "images": null, "items": {"total": 12}}],
                "total": 2, "offset": 0, "next": null}
        }"#)]);
        let results = run(&transport, &Tokens::default(), async |api| {
            api.search("AC/DC & Björk", 0, 50).await
        })
        .await
        .expect("results");
        let uri = &transport.asked_uris()[0];
        assert!(uri.contains("q=AC%2FDC+%26+Bj%C3%B6rk"), "{uri}");
        assert!(uri.contains("limit=10"), "{uri}");
        assert_eq!(results.playlists.items.len(), 1);
        assert_eq!(results.playlists.items[0].track_count, 12);
        assert_eq!(results.playlists.items[0].owner.as_deref(), Some("Luke"));
        assert_eq!(results.albums.items.len(), 0);
    }

    #[tokio::test]
    async fn an_empty_search_asks_nothing() {
        let transport = ScriptedTransport::answering([]);
        let results = run(&transport, &Tokens::default(), async |api| {
            api.search("  ", 0, 10).await
        })
        .await
        .expect("results");
        assert_eq!(results.tracks.items.len(), 0);
        assert_eq!(transport.asked_uris().len(), 0);
    }

    #[tokio::test]
    async fn saved_albums_carry_their_tracks_and_year() {
        let transport = ScriptedTransport::answering([ok(r#"{"items": [{"album": {
            "uri": "spotify:album:1", "name": "I Am", "release_date": "2008-11-12",
            "artists": [{"uri": "spotify:artist:1", "name": "Beyoncé"}],
            "images": [], "tracks": {"items": [{"uri": "spotify:track:1"}], "total": 1}
        }}], "total": 1, "offset": 0, "next": null}"#)]);
        let page = run(&transport, &Tokens::default(), async |api| {
            api.saved_albums(0, 50).await
        })
        .await
        .expect("page");
        assert_eq!(page.items[0].release_year, Some(2008));
        assert_eq!(page.items[0].track_uris, ["spotify:track:1"]);
        assert_eq!(page.next_offset, None);
    }
}
