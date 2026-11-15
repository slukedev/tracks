//! Music you don't have yet, sans-IO: the iTunes catalog, Lidarr, matching what you have and
//! ranking suggestions. The engine builds each request and reads each answer; the host sends
//! it with its own HTTP stack.

use std::sync::Arc;

use tracks_catalog::lidarr as core_lidarr;
use tracks_catalog::suggest as core_suggest;

use crate::lastfm::{HttpHeader, HttpResponse};

/// Why a catalog or Lidarr answer couldn't be used.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CatalogError {
    #[error("the API key was refused")]
    WrongKey,
    #[error("{detail}")]
    Rejected { detail: String },
    #[error("the answer wasn't from the expected service")]
    WrongService,
    #[error("HTTP {status}")]
    Http { status: u16 },
}

impl From<tracks_catalog::CatalogError> for CatalogError {
    fn from(error: tracks_catalog::CatalogError) -> Self {
        use tracks_catalog::CatalogError as E;
        match error {
            E::WrongKey => Self::WrongKey,
            E::Rejected(detail) => Self::Rejected { detail },
            E::WrongService => Self::WrongService,
            E::Http { status } => Self::Http { status },
        }
    }
}

type Result<T> = std::result::Result<T, CatalogError>;

/// A request to send exactly as is. The method is its wire name ("GET", "PUT").
#[derive(Debug, Clone, uniffi::Record)]
pub struct WebRequest {
    pub method: String,
    pub url: String,
    pub headers: Vec<HttpHeader>,
    pub body: Vec<u8>,
}

impl From<tracks_catalog::Request> for WebRequest {
    fn from(r: tracks_catalog::Request) -> Self {
        Self {
            method: r.method.as_str().to_owned(),
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

fn response(r: HttpResponse) -> tracks_catalog::Response {
    tracks_catalog::Response::new(r.status, r.body)
}

// MARK: - The iTunes catalog

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CatalogSearchKind {
    Song,
    Album,
    Artist,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CatalogAlbumKind {
    Album,
    Single,
    Ep,
    Compilation,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatalogSong {
    pub id: u64,
    pub title: String,
    pub artist_name: String,
    pub artist_id: Option<u64>,
    pub album_title: Option<String>,
    pub album_id: Option<u64>,
    pub artwork_url: Option<String>,
    pub preview_url: Option<String>,
    pub duration_ms: Option<u64>,
    pub track_number: Option<u32>,
    pub disc_number: Option<u32>,
    pub year: Option<u32>,
    pub genre: Option<String>,
    pub store_url: Option<String>,
    /// The identity history and the library group it on.
    pub identity: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatalogAlbum {
    pub id: u64,
    pub title: String,
    pub artist_name: String,
    pub artist_id: Option<u64>,
    pub artwork_url: Option<String>,
    pub track_count: Option<u32>,
    pub year: Option<u32>,
    pub genre: Option<String>,
    pub kind: CatalogAlbumKind,
    /// The day it came out, or comes out, as "2016-05-08".
    pub released: Option<String>,
    pub store_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatalogArtist {
    pub id: u64,
    pub name: String,
    pub genre: Option<String>,
    pub store_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatalogResults {
    pub songs: Vec<CatalogSong>,
    pub albums: Vec<CatalogAlbum>,
    pub artists: Vec<CatalogArtist>,
}

impl From<tracks_catalog::CatalogSong> for CatalogSong {
    fn from(s: tracks_catalog::CatalogSong) -> Self {
        let identity = s.identity();
        Self {
            id: s.id,
            title: s.title,
            artist_name: s.artist_name,
            artist_id: s.artist_id,
            album_title: s.album_title,
            album_id: s.album_id,
            artwork_url: s.artwork_url,
            preview_url: s.preview_url,
            duration_ms: s.duration_ms,
            track_number: s.track_number,
            disc_number: s.disc_number,
            year: s.year,
            genre: s.genre,
            store_url: s.store_url,
            identity,
        }
    }
}

impl From<tracks_catalog::CatalogAlbum> for CatalogAlbum {
    fn from(a: tracks_catalog::CatalogAlbum) -> Self {
        use tracks_catalog::AlbumKind as K;
        Self {
            id: a.id,
            title: a.title,
            artist_name: a.artist_name,
            artist_id: a.artist_id,
            artwork_url: a.artwork_url,
            track_count: a.track_count,
            year: a.year,
            genre: a.genre,
            kind: match a.kind {
                K::Album => CatalogAlbumKind::Album,
                K::Single => CatalogAlbumKind::Single,
                K::Ep => CatalogAlbumKind::Ep,
                K::Compilation => CatalogAlbumKind::Compilation,
            },
            released: a.released,
            store_url: a.store_url,
        }
    }
}

impl From<tracks_catalog::CatalogArtist> for CatalogArtist {
    fn from(a: tracks_catalog::CatalogArtist) -> Self {
        Self {
            id: a.id,
            name: a.name,
            genre: a.genre,
            store_url: a.store_url,
        }
    }
}

/// Builds requests for one iTunes storefront.
#[derive(Debug, uniffi::Object)]
pub struct ItunesCatalog {
    inner: tracks_catalog::Itunes,
}

#[uniffi::export]
impl ItunesCatalog {
    /// A storefront by its two letter country code; anything else is the US store.
    #[uniffi::constructor]
    pub fn new(country: String) -> Arc<Self> {
        Arc::new(Self {
            inner: tracks_catalog::Itunes::new(&country),
        })
    }

    pub fn country(&self) -> String {
        self.inner.country().to_owned()
    }

    pub fn search(&self, term: String, kind: CatalogSearchKind, limit: u32) -> WebRequest {
        let kind = match kind {
            CatalogSearchKind::Song => tracks_catalog::SearchKind::Song,
            CatalogSearchKind::Album => tracks_catalog::SearchKind::Album,
            CatalogSearchKind::Artist => tracks_catalog::SearchKind::Artist,
        };
        self.inner.search(&term, kind, limit).into()
    }

    pub fn artist_albums(&self, artist_id: u64, limit: u32) -> WebRequest {
        self.inner.artist_albums(artist_id, limit).into()
    }

    pub fn artist_songs(&self, artist_id: u64, limit: u32) -> WebRequest {
        self.inner.artist_songs(artist_id, limit).into()
    }

    pub fn album_songs(&self, album_id: u64) -> WebRequest {
        self.inner.album_songs(album_id).into()
    }
}

/// Reads any iTunes search or lookup answer.
#[uniffi::export]
pub fn catalog_parse(response: HttpResponse) -> Result<CatalogResults> {
    let found = tracks_catalog::parse_catalog(&self::response(response))?;
    Ok(CatalogResults {
        songs: found.songs.into_iter().map(Into::into).collect(),
        albums: found.albums.into_iter().map(Into::into).collect(),
        artists: found.artists.into_iter().map(Into::into).collect(),
    })
}

// MARK: - Matching

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SongName {
    pub title: String,
    pub artist: String,
}

/// The first candidate that's the same song, by its position.
#[uniffi::export]
pub fn catalog_best_match(title: String, artist: String, candidates: Vec<SongName>) -> Option<u32> {
    let names: Vec<tracks_catalog::matching::SongName<'_>> = candidates
        .iter()
        .map(|c| tracks_catalog::matching::SongName {
            title: &c.title,
            artist: &c.artist,
        })
        .collect();
    tracks_catalog::matching::best_match(&title, &artist, &names).map(|i| i as u32)
}

/// An album's title less its edition: "Coastlines (Deluxe Edition)" is "coastlines".
#[uniffi::export]
pub fn catalog_album_key(title: String) -> String {
    tracks_catalog::matching::album_key(&title)
}

/// The album a title means among `candidates`, by its position.
#[uniffi::export]
pub fn catalog_match_album(title: String, candidates: Vec<String>) -> Option<u32> {
    let titles: Vec<&str> = candidates.iter().map(String::as_str).collect();
    tracks_catalog::matching::match_album(&title, &titles).map(|i| i as u32)
}

// MARK: - Suggestions

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SuggestionSeed {
    pub artist: String,
    pub weight: f64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SuggestionCandidate {
    pub key: String,
    pub artist: String,
    pub rank: u32,
    pub owned: bool,
    pub is_minor: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AlbumOwnership {
    Owned,
    Partial,
    Missing,
}

/// The candidates worth suggesting, best first, as positions in `candidates`.
#[uniffi::export]
pub fn suggestions_rank(
    seeds: Vec<SuggestionSeed>,
    candidates: Vec<SuggestionCandidate>,
    per_artist: u32,
    limit: u32,
) -> Vec<u32> {
    let seeds: Vec<core_suggest::Seed> = seeds
        .into_iter()
        .map(|s| core_suggest::Seed {
            artist: s.artist,
            weight: s.weight,
        })
        .collect();
    let candidates: Vec<core_suggest::Candidate> = candidates
        .into_iter()
        .map(|c| core_suggest::Candidate {
            key: c.key,
            artist: c.artist,
            rank: c.rank,
            owned: c.owned,
            is_minor: c.is_minor,
        })
        .collect();
    core_suggest::rank(&seeds, &candidates, per_artist as usize, limit as usize)
        .into_iter()
        .map(|i| i as u32)
        .collect()
}

/// What one artist's listeners play alongside them, closest first.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RelatedArtists {
    pub from: String,
    pub names: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SimilarArtist {
    pub name: String,
    /// Your artists they're like, the closest first.
    pub like: Vec<String>,
    /// To rank their songs by, below every artist of yours.
    pub weight: f64,
}

/// Artists like yours, best first, leaving out yours and `excluded`.
#[uniffi::export]
pub fn suggestions_similar_artists(
    seeds: Vec<SuggestionSeed>,
    related: Vec<RelatedArtists>,
    excluded: Vec<String>,
    limit: u32,
) -> Vec<SimilarArtist> {
    let seeds: Vec<core_suggest::Seed> = seeds
        .into_iter()
        .map(|s| core_suggest::Seed {
            artist: s.artist,
            weight: s.weight,
        })
        .collect();
    let related: Vec<core_suggest::Related> = related
        .into_iter()
        .map(|r| core_suggest::Related {
            from: r.from,
            names: r.names,
        })
        .collect();
    core_suggest::similar_artists(&seeds, &related, &excluded, limit as usize)
        .into_iter()
        .map(|a| SimilarArtist {
            name: a.name,
            like: a.like,
            weight: a.weight,
        })
        .collect()
}

// MARK: - Deezer

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeezerArtist {
    pub id: u64,
    pub name: String,
    pub picture_url: Option<String>,
    pub fans: Option<u64>,
}

impl From<&DeezerArtist> for tracks_catalog::deezer::DeezerArtist {
    fn from(a: &DeezerArtist) -> Self {
        Self {
            id: a.id,
            name: a.name.clone(),
            picture_url: a.picture_url.clone(),
            fans: a.fans,
        }
    }
}

/// Requests for Deezer's public API, which needs no key.
#[uniffi::export]
pub fn deezer_search_artists(name: String, limit: u32) -> WebRequest {
    tracks_catalog::deezer::Deezer.search_artists(&name, limit).into()
}

#[uniffi::export]
pub fn deezer_related(artist_id: u64, limit: u32) -> WebRequest {
    tracks_catalog::deezer::Deezer.related(artist_id, limit).into()
}

/// Reads a search or a related list; "no data" is an empty list.
#[uniffi::export]
pub fn deezer_parse_artists(response: HttpResponse) -> Result<Vec<DeezerArtist>> {
    Ok(tracks_catalog::deezer::parse_artists(&self::response(response))?
        .into_iter()
        .map(|a| DeezerArtist {
            id: a.id,
            name: a.name,
            picture_url: a.picture_url,
            fans: a.fans,
        })
        .collect())
}

/// The search result that is this very artist, by position.
#[uniffi::export]
pub fn deezer_artist_match(name: String, results: Vec<DeezerArtist>) -> Option<u32> {
    let results: Vec<tracks_catalog::deezer::DeezerArtist> = results.iter().map(Into::into).collect();
    tracks_catalog::deezer::artist_match(&name, &results).map(|i| i as u32)
}

/// How much of an album you have, from how many of its songs are yours.
#[uniffi::export]
pub fn album_ownership(owned: u32, total: Option<u32>) -> AlbumOwnership {
    match core_suggest::Ownership::of(owned, total) {
        core_suggest::Ownership::Owned => AlbumOwnership::Owned,
        core_suggest::Ownership::Partial => AlbumOwnership::Partial,
        core_suggest::Ownership::Missing => AlbumOwnership::Missing,
    }
}

// MARK: - Lidarr

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LidarrMonitor {
    All,
    Future,
    Latest,
    None,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrAddOptions {
    pub quality_profile_id: i64,
    pub metadata_profile_id: i64,
    pub root_folder_path: String,
    pub monitor: LidarrMonitor,
    pub search_now: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrStatus {
    pub version: String,
    pub app_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrProfile {
    pub id: i64,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrRootFolder {
    pub id: i64,
    pub path: String,
    pub free_space: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrArtist {
    pub id: Option<i64>,
    pub artist_name: String,
    pub foreign_artist_id: String,
    pub monitored: bool,
    pub disambiguation: Option<String>,
    pub picture_url: Option<String>,
    pub album_count: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrAlbum {
    pub id: i64,
    pub title: String,
    pub album_type: Option<String>,
    pub release_date: Option<String>,
    pub monitored: bool,
    pub artist_id: Option<i64>,
    pub artist_name: Option<String>,
    pub cover_url: Option<String>,
    pub track_file_count: u32,
    pub total_track_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LidarrTrack {
    pub id: i64,
    pub title: String,
    pub has_file: bool,
    pub album_id: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LidarrQueueItem {
    pub id: i64,
    pub title: Option<String>,
    pub status: Option<String>,
    pub progress: f64,
    pub time_left: Option<String>,
    pub error_message: Option<String>,
    pub album_title: Option<String>,
    pub artist_name: Option<String>,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum LidarrSongState {
    Collected { album: String },
    Wanted,
    Missing,
}

impl From<core_lidarr::LidarrArtist> for LidarrArtist {
    fn from(a: core_lidarr::LidarrArtist) -> Self {
        Self {
            id: a.id,
            artist_name: a.artist_name,
            foreign_artist_id: a.foreign_artist_id,
            monitored: a.monitored,
            disambiguation: a.disambiguation,
            picture_url: a.picture_url,
            album_count: a.album_count,
        }
    }
}

impl From<&LidarrArtist> for core_lidarr::LidarrArtist {
    fn from(a: &LidarrArtist) -> Self {
        Self {
            id: a.id,
            artist_name: a.artist_name.clone(),
            foreign_artist_id: a.foreign_artist_id.clone(),
            monitored: a.monitored,
            disambiguation: a.disambiguation.clone(),
            picture_url: a.picture_url.clone(),
            album_count: a.album_count,
        }
    }
}

impl From<core_lidarr::LidarrAlbum> for LidarrAlbum {
    fn from(a: core_lidarr::LidarrAlbum) -> Self {
        Self {
            id: a.id,
            title: a.title,
            album_type: a.album_type,
            release_date: a.release_date,
            monitored: a.monitored,
            artist_id: a.artist_id,
            artist_name: a.artist_name,
            cover_url: a.cover_url,
            track_file_count: a.track_file_count,
            total_track_count: a.total_track_count,
        }
    }
}

impl From<&LidarrAlbum> for core_lidarr::LidarrAlbum {
    fn from(a: &LidarrAlbum) -> Self {
        Self {
            id: a.id,
            title: a.title.clone(),
            album_type: a.album_type.clone(),
            release_date: a.release_date.clone(),
            monitored: a.monitored,
            artist_id: a.artist_id,
            artist_name: a.artist_name.clone(),
            cover_url: a.cover_url.clone(),
            track_file_count: a.track_file_count,
            total_track_count: a.total_track_count,
        }
    }
}

fn core_tracks(tracks: &[LidarrTrack]) -> Vec<core_lidarr::LidarrTrack> {
    tracks
        .iter()
        .map(|t| core_lidarr::LidarrTrack {
            id: t.id,
            title: t.title.clone(),
            has_file: t.has_file,
            album_id: t.album_id,
        })
        .collect()
}

/// Builds requests for one Lidarr, its key sent in a header.
#[derive(Debug, uniffi::Object)]
pub struct LidarrApi {
    inner: core_lidarr::Lidarr,
}

#[uniffi::export]
impl LidarrApi {
    #[uniffi::constructor]
    pub fn new(base_url: String, api_key: String) -> Arc<Self> {
        Arc::new(Self {
            inner: core_lidarr::Lidarr::new(&base_url, &api_key),
        })
    }

    pub fn status(&self) -> WebRequest {
        self.inner.status().into()
    }

    pub fn quality_profiles(&self) -> WebRequest {
        self.inner.quality_profiles().into()
    }

    pub fn metadata_profiles(&self) -> WebRequest {
        self.inner.metadata_profiles().into()
    }

    pub fn root_folders(&self) -> WebRequest {
        self.inner.root_folders().into()
    }

    pub fn artists(&self) -> WebRequest {
        self.inner.artists().into()
    }

    pub fn look_up_artists(&self, term: String) -> WebRequest {
        self.inner.look_up_artists(&term).into()
    }

    pub fn add_artist(&self, artist: LidarrArtist, options: LidarrAddOptions) -> WebRequest {
        let options = core_lidarr::AddOptions {
            quality_profile_id: options.quality_profile_id,
            metadata_profile_id: options.metadata_profile_id,
            root_folder_path: options.root_folder_path,
            monitor: match options.monitor {
                LidarrMonitor::All => core_lidarr::Monitor::All,
                LidarrMonitor::Future => core_lidarr::Monitor::Future,
                LidarrMonitor::Latest => core_lidarr::Monitor::Latest,
                LidarrMonitor::None => core_lidarr::Monitor::None,
            },
            search_now: options.search_now,
        };
        self.inner.add_artist(&(&artist).into(), &options).into()
    }

    pub fn albums_of_artist(&self, artist_id: i64) -> WebRequest {
        self.inner.albums_of_artist(artist_id).into()
    }

    pub fn tracks_of_artist(&self, artist_id: i64) -> WebRequest {
        self.inner.tracks_of_artist(artist_id).into()
    }

    pub fn set_monitored(&self, album_ids: Vec<i64>, monitored: bool) -> WebRequest {
        self.inner.set_monitored(&album_ids, monitored).into()
    }

    pub fn search_albums(&self, album_ids: Vec<i64>) -> WebRequest {
        self.inner.search_albums(&album_ids).into()
    }

    pub fn queue(&self) -> WebRequest {
        self.inner.queue().into()
    }
}

/// Whether Lidarr took a request whose answer doesn't matter.
#[uniffi::export]
pub fn lidarr_check(response: HttpResponse) -> Result<()> {
    Ok(core_lidarr::check(&self::response(response))?)
}

#[uniffi::export]
pub fn lidarr_parse_status(response: HttpResponse) -> Result<LidarrStatus> {
    let status = core_lidarr::parse_status(&self::response(response))?;
    Ok(LidarrStatus {
        version: status.version,
        app_name: status.app_name,
    })
}

#[uniffi::export]
pub fn lidarr_parse_profiles(response: HttpResponse) -> Result<Vec<LidarrProfile>> {
    Ok(core_lidarr::parse_profiles(&self::response(response))?
        .into_iter()
        .map(|p| LidarrProfile { id: p.id, name: p.name })
        .collect())
}

#[uniffi::export]
pub fn lidarr_parse_root_folders(response: HttpResponse) -> Result<Vec<LidarrRootFolder>> {
    Ok(core_lidarr::parse_root_folders(&self::response(response))?
        .into_iter()
        .map(|f| LidarrRootFolder {
            id: f.id,
            path: f.path,
            free_space: f.free_space,
        })
        .collect())
}

/// A list of artists, or the one artist an add answers with.
#[uniffi::export]
pub fn lidarr_parse_artists(response: HttpResponse) -> Result<Vec<LidarrArtist>> {
    Ok(core_lidarr::parse_artists(&self::response(response))?
        .into_iter()
        .map(Into::into)
        .collect())
}

#[uniffi::export]
pub fn lidarr_parse_albums(response: HttpResponse) -> Result<Vec<LidarrAlbum>> {
    Ok(core_lidarr::parse_albums(&self::response(response))?
        .into_iter()
        .map(Into::into)
        .collect())
}

#[uniffi::export]
pub fn lidarr_parse_tracks(response: HttpResponse) -> Result<Vec<LidarrTrack>> {
    Ok(core_lidarr::parse_tracks(&self::response(response))?
        .into_iter()
        .map(|t| LidarrTrack {
            id: t.id,
            title: t.title,
            has_file: t.has_file,
            album_id: t.album_id,
        })
        .collect())
}

#[uniffi::export]
pub fn lidarr_parse_queue(response: HttpResponse) -> Result<Vec<LidarrQueueItem>> {
    Ok(core_lidarr::parse_queue(&self::response(response))?
        .into_iter()
        .map(|q| LidarrQueueItem {
            id: q.id,
            title: q.title,
            status: q.status,
            progress: q.progress,
            time_left: q.time_left,
            error_message: q.error_message,
            album_title: q.album_title,
            artist_name: q.artist_name,
            cover_url: q.cover_url,
        })
        .collect())
}

/// The artist Lidarr follows by this name, by position: the whole credit, then its lead.
#[uniffi::export]
pub fn lidarr_followed_artist(name: String, artists: Vec<LidarrArtist>) -> Option<u32> {
    let artists: Vec<core_lidarr::LidarrArtist> = artists.iter().map(Into::into).collect();
    core_lidarr::followed_artist(&name, &artists).map(|i| i as u32)
}

/// The search result that is exactly this artist, by position.
#[uniffi::export]
pub fn lidarr_lookup_match(name: String, results: Vec<LidarrArtist>) -> Option<u32> {
    let results: Vec<core_lidarr::LidarrArtist> = results.iter().map(Into::into).collect();
    core_lidarr::lookup_match(&name, &results).map(|i| i as u32)
}

/// The album of this title among an artist's, by position.
#[uniffi::export]
pub fn lidarr_album_match(title: String, albums: Vec<LidarrAlbum>) -> Option<u32> {
    let albums: Vec<core_lidarr::LidarrAlbum> = albums.iter().map(Into::into).collect();
    core_lidarr::album_match(&title, &albums).map(|i| i as u32)
}

/// What Lidarr knows of a song, from its artist's albums and tracks.
#[uniffi::export]
pub fn lidarr_song_state(title: String, albums: Vec<LidarrAlbum>, tracks: Vec<LidarrTrack>) -> LidarrSongState {
    let albums: Vec<core_lidarr::LidarrAlbum> = albums.iter().map(Into::into).collect();
    match core_lidarr::song_state(&title, &albums, &core_tracks(&tracks)) {
        core_lidarr::SongState::Collected { album } => LidarrSongState::Collected { album },
        core_lidarr::SongState::Wanted => LidarrSongState::Wanted,
        core_lidarr::SongState::Missing => LidarrSongState::Missing,
    }
}
