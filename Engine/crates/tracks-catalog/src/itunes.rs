//! Apple's iTunes Search API: free, keyless, with songs, albums, artists, covers and 30
//! second previews. <https://performance-partners.apple.com/search-api>
//!
//! Every answer has the same shape, `{"resultCount": n, "results": [...]}`, with each result
//! saying what it is in `wrapperType` (and `kind` for tracks). A lookup of an artist's
//! albums leads with the artist itself, and of an album's songs with the album.

use serde::Deserialize;

use crate::error::{CatalogError, Result};
use crate::web::{Request, Response, query};

const SEARCH: &str = "https://itunes.apple.com/search";
const LOOKUP: &str = "https://itunes.apple.com/lookup";

/// The most results the API gives for one request.
const MAX_LIMIT: u32 = 200;

/// What to search for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SearchKind {
    Song,
    Album,
    Artist,
}

impl SearchKind {
    const fn entity(self) -> &'static str {
        match self {
            Self::Song => "song",
            Self::Album => "album",
            Self::Artist => "musicArtist",
        }
    }
}

/// Builds requests for one storefront.
#[derive(Debug, Clone)]
pub struct Itunes {
    country: String,
}

impl Itunes {
    /// A client for a storefront, by its two letter country code ("GB", "us"). Anything else
    /// falls back to the US store, which has the widest catalog.
    pub fn new(country: &str) -> Self {
        let code = country.trim();
        let country = if code.len() == 2 && code.bytes().all(|b| b.is_ascii_alphabetic()) {
            code.to_ascii_lowercase()
        } else {
            "us".to_owned()
        };
        Self { country }
    }

    pub fn country(&self) -> &str {
        &self.country
    }

    /// Songs, albums or artists matching a search, best first.
    pub fn search(&self, term: &str, kind: SearchKind, limit: u32) -> Request {
        let limit = clamp(limit).to_string();
        Request::get(format!(
            "{SEARCH}?{}",
            query(&[
                ("term", term.trim()),
                ("country", &self.country),
                ("media", "music"),
                ("entity", kind.entity()),
                ("limit", &limit),
            ])
        ))
    }

    /// An artist's albums and singles, by their catalog id.
    pub fn artist_albums(&self, artist_id: u64, limit: u32) -> Request {
        self.lookup(artist_id, "album", Some(limit))
    }

    /// An artist's most popular songs, by their catalog id.
    pub fn artist_songs(&self, artist_id: u64, limit: u32) -> Request {
        self.lookup(artist_id, "song", Some(limit))
    }

    /// Every song on an album, in order.
    pub fn album_songs(&self, album_id: u64) -> Request {
        self.lookup(album_id, "song", None)
    }

    fn lookup(&self, id: u64, entity: &str, limit: Option<u32>) -> Request {
        let id = id.to_string();
        let limit = limit.map(|l| clamp(l).to_string());
        let mut pairs = vec![
            ("id", id.as_str()),
            ("entity", entity),
            ("country", self.country.as_str()),
        ];
        if let Some(limit) = &limit {
            pairs.push(("limit", limit));
        }
        Request::get(format!("{LOOKUP}?{}", query(&pairs)))
    }
}

fn clamp(limit: u32) -> u32 {
    limit.clamp(1, MAX_LIMIT)
}

/// A song in the catalog.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogSong {
    pub id: u64,
    pub title: String,
    pub artist_name: String,
    pub artist_id: Option<u64>,
    pub album_title: Option<String>,
    pub album_id: Option<u64>,
    /// A 600 pixel cover.
    pub artwork_url: Option<String>,
    /// A 30 second preview, AAC in an M4A.
    pub preview_url: Option<String>,
    pub duration_ms: Option<u64>,
    pub track_number: Option<u32>,
    pub disc_number: Option<u32>,
    pub year: Option<u32>,
    pub genre: Option<String>,
    /// Its page in Apple Music.
    pub store_url: Option<String>,
}

impl CatalogSong {
    /// The identity history and the library group this song on.
    pub fn identity(&self) -> String {
        tracks_model::song_key(&self.title, &self.artist_name)
    }
}

/// An album, single or EP. Apple names singles "Song - Single"; the title here leaves the
/// suffix off and says so in [`kind`](Self::kind).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogAlbum {
    pub id: u64,
    pub title: String,
    pub artist_name: String,
    pub artist_id: Option<u64>,
    pub artwork_url: Option<String>,
    pub track_count: Option<u32>,
    pub year: Option<u32>,
    pub genre: Option<String>,
    pub kind: AlbumKind,
    pub store_url: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AlbumKind {
    Album,
    Single,
    Ep,
    Compilation,
}

/// An artist in the catalog.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogArtist {
    pub id: u64,
    pub name: String,
    pub genre: Option<String>,
    pub store_url: Option<String>,
}

/// Whatever an answer held, sorted by what it is, each in the catalog's order.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CatalogResults {
    pub songs: Vec<CatalogSong>,
    pub albums: Vec<CatalogAlbum>,
    pub artists: Vec<CatalogArtist>,
}

/// Reads any search or lookup answer. Results the app has no use for (videos, podcasts)
/// are left out.
pub fn parse_catalog(response: &Response) -> Result<CatalogResults> {
    if !response.is_success() {
        return Err(CatalogError::Http {
            status: response.status,
        });
    }
    let envelope: Envelope =
        serde_json::from_slice(&response.body).map_err(|_| CatalogError::WrongService)?;
    let mut results = CatalogResults::default();
    for item in envelope.results {
        match item.wrapper_type.as_deref() {
            Some("track") if item.kind.as_deref() == Some("song") => {
                if let Some(song) = item.song() {
                    results.songs.push(song);
                }
            }
            Some("collection") => {
                if let Some(album) = item.album() {
                    results.albums.push(album);
                }
            }
            Some("artist") => {
                if let Some(artist) = item.artist() {
                    results.artists.push(artist);
                }
            }
            _ => {}
        }
    }
    Ok(results)
}

/// The same cover at 600 pixels: the API hands out 100 pixel ones, and the size is part of
/// the address ("…/100x100bb.jpg").
pub fn larger_artwork(url: &str) -> String {
    match url.rfind('/') {
        Some(slash) if url[slash..].contains('x') && url[slash..].contains("bb.") => {
            let ext = url[slash..].rsplit('.').next().unwrap_or("jpg");
            format!("{}/600x600bb.{ext}", &url[..slash])
        }
        _ => url.to_owned(),
    }
}

#[derive(Deserialize)]
struct Envelope {
    #[serde(default)]
    results: Vec<Item>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Item {
    wrapper_type: Option<String>,
    kind: Option<String>,
    collection_type: Option<String>,
    artist_type: Option<String>,
    artist_id: Option<u64>,
    collection_id: Option<u64>,
    track_id: Option<u64>,
    artist_name: Option<String>,
    collection_name: Option<String>,
    track_name: Option<String>,
    artwork_url100: Option<String>,
    artwork_url60: Option<String>,
    preview_url: Option<String>,
    track_time_millis: Option<u64>,
    track_number: Option<u32>,
    disc_number: Option<u32>,
    track_count: Option<u32>,
    release_date: Option<String>,
    primary_genre_name: Option<String>,
    track_view_url: Option<String>,
    collection_view_url: Option<String>,
    artist_link_url: Option<String>,
}

impl Item {
    fn artwork(&self) -> Option<String> {
        self.artwork_url100
            .as_deref()
            .or(self.artwork_url60.as_deref())
            .map(larger_artwork)
    }

    fn year(&self) -> Option<u32> {
        self.release_date.as_deref()?.get(..4)?.parse().ok()
    }

    fn song(self) -> Option<CatalogSong> {
        let title = non_empty(self.track_name.as_deref())?;
        let artist_name = non_empty(self.artist_name.as_deref())?;
        Some(CatalogSong {
            id: self.track_id?,
            artwork_url: self.artwork(),
            year: self.year(),
            title,
            artist_name,
            artist_id: self.artist_id,
            album_title: non_empty(self.collection_name.as_deref()),
            album_id: self.collection_id,
            preview_url: self.preview_url,
            duration_ms: self.track_time_millis,
            track_number: self.track_number,
            disc_number: self.disc_number,
            genre: self.primary_genre_name,
            store_url: self.track_view_url,
        })
    }

    fn album(self) -> Option<CatalogAlbum> {
        let name = non_empty(self.collection_name.as_deref())?;
        let artist_name = non_empty(self.artist_name.as_deref())?;
        let (title, mut kind) = split_kind(&name);
        if self.collection_type.as_deref() == Some("Compilation") {
            kind = AlbumKind::Compilation;
        }
        Some(CatalogAlbum {
            id: self.collection_id?,
            artwork_url: self.artwork(),
            year: self.year(),
            title,
            artist_name,
            artist_id: self.artist_id,
            track_count: self.track_count,
            genre: self.primary_genre_name,
            kind,
            store_url: self.collection_view_url,
        })
    }

    fn artist(self) -> Option<CatalogArtist> {
        if self.artist_type.as_deref().is_some_and(|t| t != "Artist") {
            return None;
        }
        Some(CatalogArtist {
            id: self.artist_id?,
            name: non_empty(self.artist_name.as_deref())?,
            genre: self.primary_genre_name,
            store_url: self.artist_link_url,
        })
    }
}

fn non_empty(text: Option<&str>) -> Option<String> {
    text.map(str::trim)
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
}

fn split_kind(name: &str) -> (String, AlbumKind) {
    for (suffix, kind) in [(" - Single", AlbumKind::Single), (" - EP", AlbumKind::Ep)] {
        if let Some(title) = name.strip_suffix(suffix) {
            return (title.trim().to_owned(), kind);
        }
    }
    (name.to_owned(), AlbumKind::Album)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn storefront_falls_back_to_us() {
        assert_eq!(Itunes::new("GB").country(), "gb");
        assert_eq!(Itunes::new("").country(), "us");
        assert_eq!(Itunes::new("419").country(), "us");
    }

    #[test]
    fn builds_search_and_lookups() {
        let itunes = Itunes::new("gb");
        assert_eq!(
            itunes
                .search(" Florence + the Machine ", SearchKind::Artist, 500)
                .url,
            "https://itunes.apple.com/search?term=Florence%20%2B%20the%20Machine&country=gb&media=music&entity=musicArtist&limit=200"
        );
        assert_eq!(
            itunes.album_songs(1_097_861_387).url,
            "https://itunes.apple.com/lookup?id=1097861387&entity=song&country=gb"
        );
        assert_eq!(
            itunes.artist_albums(657_515, 0).url,
            "https://itunes.apple.com/lookup?id=657515&entity=album&country=gb&limit=1"
        );
    }

    #[test]
    fn covers_grow_to_600() {
        assert_eq!(
            larger_artwork("https://is1-ssl.mzstatic.com/image/thumb/a/b.png/100x100bb.jpg"),
            "https://is1-ssl.mzstatic.com/image/thumb/a/b.png/600x600bb.jpg"
        );
        assert_eq!(
            larger_artwork("https://example.com/cover.jpg"),
            "https://example.com/cover.jpg"
        );
    }

    #[test]
    fn singles_and_eps_lose_their_suffix() {
        assert_eq!(
            split_kind("Creep - EP"),
            ("Creep".to_owned(), AlbumKind::Ep)
        );
        assert_eq!(
            split_kind("Lift - Single"),
            ("Lift".to_owned(), AlbumKind::Single)
        );
        assert_eq!(split_kind("Kid A"), ("Kid A".to_owned(), AlbumKind::Album));
    }
}
