//! Deezer's public API, for artists like the ones you play: free, keyless, and answering
//! `/artist/{id}/related` with the artists its listeners play alongside. Only the artists are
//! used; songs and albums still come from the iTunes catalog, which has the previews, store
//! pages and ids the rest of the app works with. <https://developers.deezer.com/api>
//!
//! A failure comes back as `200` with `{"error": {"type", "message", "code"}}`. Code 800
//! ("no data") means nothing was found; code 4 is the rate limit.

use serde::Deserialize;

use crate::error::{CatalogError, Result};
use crate::web::{Request, Response, query};

const API: &str = "https://api.deezer.com";

/// Builds requests for Deezer's public API.
#[derive(Debug, Clone, Copy, Default)]
pub struct Deezer;

impl Deezer {
    /// Artists matching a name, best first.
    pub fn search_artists(self, name: &str, limit: u32) -> Request {
        let limit = limit.clamp(1, 25).to_string();
        Request::get(format!("{API}/search/artist?{}", query(&[("q", name.trim()), ("limit", &limit)])))
    }

    /// The artists Deezer's listeners play alongside this one, closest first.
    pub fn related(self, artist_id: u64, limit: u32) -> Request {
        let limit = limit.clamp(1, 50).to_string();
        Request::get(format!("{API}/artist/{artist_id}/related?{}", query(&[("limit", &limit)])))
    }
}

/// An artist on Deezer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeezerArtist {
    pub id: u64,
    pub name: String,
    /// A 500 pixel photo, when Deezer has one.
    pub picture_url: Option<String>,
    pub fans: Option<u64>,
}

/// Reads a search or a related list. "No data" is an empty list, not a failure.
pub fn parse_artists(response: &Response) -> Result<Vec<DeezerArtist>> {
    if !response.is_success() {
        return Err(CatalogError::Http {
            status: response.status,
        });
    }
    let envelope: Envelope = serde_json::from_slice(&response.body).map_err(|_| CatalogError::WrongService)?;
    if let Some(error) = envelope.error {
        return match error.code {
            Some(800) => Ok(Vec::new()),
            Some(4) => Err(CatalogError::Http { status: 429 }),
            _ => Err(CatalogError::Rejected(error.message.unwrap_or_default())),
        };
    }
    let data = envelope.data.ok_or(CatalogError::WrongService)?;
    Ok(data
        .into_iter()
        .filter_map(|a| {
            let name = a.name.as_deref().map(str::trim).filter(|n| !n.is_empty())?.to_owned();
            Some(DeezerArtist {
                id: a.id?,
                name,
                picture_url: a.picture_big.filter(|url| !is_placeholder(url)),
                fans: a.nb_fan,
            })
        })
        .collect())
}

/// The search result that is this very artist, by position: the same folded name, and of
/// several (tribute bands, namesakes), the one with the most fans.
pub fn artist_match(name: &str, results: &[DeezerArtist]) -> Option<usize> {
    let wanted = tracks_model::folded(name);
    results
        .iter()
        .enumerate()
        .filter(|(_, a)| tracks_model::folded(&a.name) == wanted)
        .max_by_key(|(index, a)| (a.fans.unwrap_or(0), std::cmp::Reverse(*index)))
        .map(|(index, _)| index)
}

/// Deezer's stand-in for an artist without a photo has no image hash in its address
/// (".../images/artist//500x500-...").
fn is_placeholder(url: &str) -> bool {
    url.contains("/artist//")
}

#[derive(Deserialize)]
struct Envelope {
    data: Option<Vec<Item>>,
    error: Option<Problem>,
}

#[derive(Deserialize)]
struct Item {
    id: Option<u64>,
    name: Option<String>,
    picture_big: Option<String>,
    nb_fan: Option<u64>,
}

#[derive(Deserialize)]
struct Problem {
    message: Option<String>,
    code: Option<u32>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_requests() {
        assert_eq!(
            Deezer.search_artists(" Florence + the Machine ", 5).url,
            "https://api.deezer.com/search/artist?q=Florence%20%2B%20the%20Machine&limit=5"
        );
        assert_eq!(Deezer.related(399, 500).url, "https://api.deezer.com/artist/399/related?limit=50");
    }

    #[test]
    fn photos_without_a_hash_are_dropped() {
        assert!(is_placeholder("https://e-cdns-images.dzcdn.net/images/artist//500x500-000000-80-0-0.jpg"));
        assert!(!is_placeholder("https://cdn-images.dzcdn.net/images/artist/0f95/500x500-000000-80-0-0.jpg"));
    }

    #[test]
    fn the_artist_with_most_fans_wins_a_shared_name() {
        let artist = |id, name: &str, fans| DeezerArtist { id, name: name.into(), picture_url: None, fans: Some(fans) };
        let results = [artist(1, "Nirvana", 10), artist(2, "Nirvana", 9_000), artist(3, "Nirvana UK", 50_000)];
        assert_eq!(artist_match("nirvana", &results), Some(1));
        assert_eq!(artist_match("Blur", &results), None);
    }
}
