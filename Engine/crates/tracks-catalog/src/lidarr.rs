//! Lidarr, which looks after a music collection: it follows artists, finds their albums and
//! files them where a music server plays them. Its v1 API, as the Swift app's
//! `LidarrClient` uses it: add artists, ask for albums, and see what's on its way.
//!
//! The key goes in a header, never the address, so it isn't kept in logs or caches.

use serde::{Deserialize, Serialize};
use tracks_model::folded;

use crate::error::{CatalogError, Result};
use crate::matching::{artist_candidates, match_album};
use crate::web::{Method, Request, Response, query};

/// Builds requests for one Lidarr.
#[derive(Clone)]
pub struct Lidarr {
    base: String,
    key: String,
}

impl std::fmt::Debug for Lidarr {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Lidarr")
            .field("base", &self.base)
            .field("key", &"<redacted>")
            .finish()
    }
}

/// What Lidarr does with an artist Tracks adds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Monitor {
    /// Every album, past and future.
    All,
    /// Only albums from now on.
    Future,
    /// The newest album, and what comes after.
    Latest,
    /// Nothing yet: albums are asked for one at a time.
    None,
}

impl Monitor {
    const fn as_str(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Future => "future",
            Self::Latest => "latest",
            Self::None => "none",
        }
    }
}

/// Where and how Lidarr files an artist Tracks adds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AddOptions {
    pub quality_profile_id: i64,
    pub metadata_profile_id: i64,
    pub root_folder_path: String,
    pub monitor: Monitor,
    /// Look for what's wanted straight away, rather than at Lidarr's next check.
    pub search_now: bool,
}

impl Lidarr {
    /// A client for Lidarr at `base` (`http://nas:8686`), with its API key. The address is
    /// read as [`base_url`] reads it; one that can't be Lidarr's is kept as typed, so its
    /// requests fail as an unreachable address rather than reach somewhere else.
    pub fn new(base: &str, key: &str) -> Self {
        Self {
            base: base_url(base).unwrap_or_else(|| base.trim().trim_end_matches('/').to_owned()),
            key: key.trim().to_owned(),
        }
    }

    pub fn status(&self) -> Request {
        self.get("system/status", &[])
    }

    pub fn quality_profiles(&self) -> Request {
        self.get("qualityprofile", &[])
    }

    pub fn metadata_profiles(&self) -> Request {
        self.get("metadataprofile", &[])
    }

    pub fn root_folders(&self) -> Request {
        self.get("rootfolder", &[])
    }

    /// Every artist Lidarr follows.
    pub fn artists(&self) -> Request {
        self.get("artist", &[])
    }

    /// Artists by name, from `MusicBrainz`, as Lidarr's own search finds them.
    pub fn look_up_artists(&self, term: &str) -> Request {
        self.get("artist/lookup", &[("term", term.trim())])
    }

    /// Follows an artist found with [`look_up_artists`](Self::look_up_artists).
    pub fn add_artist(&self, artist: &LidarrArtist, options: &AddOptions) -> Request {
        let body = AddArtist {
            artist_name: &artist.artist_name,
            foreign_artist_id: &artist.foreign_artist_id,
            quality_profile_id: options.quality_profile_id,
            metadata_profile_id: options.metadata_profile_id,
            root_folder_path: &options.root_folder_path,
            monitored: true,
            monitor_new_items: if options.monitor == Monitor::None {
                "none"
            } else {
                "all"
            },
            add_options: AddArtistOptions {
                monitor: options.monitor.as_str(),
                search_for_missing_albums: options.search_now,
            },
        };
        self.send(Method::Post, "artist", &body)
    }

    pub fn albums_of_artist(&self, artist_id: i64) -> Request {
        self.get("album", &[("artistId", &artist_id.to_string())])
    }

    /// Every track of an artist's, each with whether Lidarr has its file.
    pub fn tracks_of_artist(&self, artist_id: i64) -> Request {
        self.get("track", &[("artistId", &artist_id.to_string())])
    }

    /// Follows or stops following albums.
    pub fn set_monitored(&self, album_ids: &[i64], monitored: bool) -> Request {
        self.send(
            Method::Put,
            "album/monitor",
            &MonitorBody {
                album_ids,
                monitored,
            },
        )
    }

    /// Asks Lidarr to go and find these albums now.
    pub fn search_albums(&self, album_ids: &[i64]) -> Request {
        self.send(
            Method::Post,
            "command",
            &Command {
                name: "AlbumSearch",
                album_ids: Some(album_ids),
                artist_id: None,
            },
        )
    }

    /// What's downloading or waiting to be filed: its first page.
    pub fn queue(&self) -> Request {
        self.queue_page(1)
    }

    /// One page of the queue, from 1, of [`QUEUE_PAGE_SIZE`] items. A discography searched
    /// for at once queues hundreds; [`QueuePage::next`] says whether to ask for another.
    pub fn queue_page(&self, page: u32) -> Request {
        let page = page.max(1).to_string();
        let size = QUEUE_PAGE_SIZE.to_string();
        self.get(
            "queue",
            &[
                ("page", &page),
                ("pageSize", &size),
                ("includeArtist", "true"),
                ("includeAlbum", "true"),
            ],
        )
    }

    fn get(&self, path: &str, pairs: &[(&str, &str)]) -> Request {
        let mut url = format!("{}/api/v1/{path}", self.base);
        if !pairs.is_empty() {
            url.push('?');
            url.push_str(&query(pairs));
        }
        let mut request = Request::get(url);
        request
            .headers
            .push(("X-Api-Key".to_owned(), self.key.clone()));
        request
    }

    fn send(&self, method: Method, path: &str, body: &impl Serialize) -> Request {
        let mut request = self.get(path, &[]);
        request.method = method;
        request
            .headers
            .push(("Content-Type".to_owned(), "application/json".to_owned()));
        // Our own plain structs: serialising them can't fail.
        request.body = serde_json::to_vec(body).unwrap_or_default();
        request
    }
}

/// Items asked for in each page of the queue.
pub const QUEUE_PAGE_SIZE: u32 = 100;
/// Pages of the queue read at most, so a queue gone wrong on the server isn't read without end.
pub const QUEUE_PAGE_LIMIT: u32 = 10;

/// Lidarr's address as someone types it: `http://` added when there's no scheme, since
/// Lidarr is almost always on the home network, the API's own path dropped when it's pasted
/// with it, and trailing slashes gone. `None` for an empty address or one that isn't http or
/// https.
pub fn base_url(text: &str) -> Option<String> {
    let text = text.trim();
    // Split before the slashes go, so "http://" alone isn't read as a host named "http:".
    let with_scheme = match text.split_once("://") {
        Some((scheme, rest)) => {
            let rest = rest.trim_end_matches('/');
            let scheme = scheme.to_ascii_lowercase();
            if (scheme != "http" && scheme != "https") || rest.is_empty() {
                return None;
            }
            format!("{scheme}://{rest}")
        }
        None => {
            let trimmed = text.trim_end_matches('/');
            if trimmed.is_empty() {
                return None;
            }
            format!("http://{trimmed}")
        }
    };
    let mut base = with_scheme.as_str();
    for suffix in ["/api/v1", "/api"] {
        if base.len() > suffix.len()
            && base[base.len() - suffix.len()..].eq_ignore_ascii_case(suffix)
        {
            base = &base[..base.len() - suffix.len()];
            break;
        }
    }
    let base = base.trim_end_matches('/');
    let host = base.split_once("://").map_or("", |(_, rest)| rest);
    if host.is_empty() || host.starts_with('/') {
        return None;
    }
    Some(base.to_owned())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AddArtist<'a> {
    artist_name: &'a str,
    foreign_artist_id: &'a str,
    quality_profile_id: i64,
    metadata_profile_id: i64,
    root_folder_path: &'a str,
    monitored: bool,
    monitor_new_items: &'a str,
    add_options: AddArtistOptions<'a>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AddArtistOptions<'a> {
    monitor: &'a str,
    search_for_missing_albums: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MonitorBody<'a> {
    album_ids: &'a [i64],
    monitored: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Command<'a> {
    name: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    album_ids: Option<&'a [i64]>,
    #[serde(skip_serializing_if = "Option::is_none")]
    artist_id: Option<i64>,
}

// MARK: - What Lidarr sends

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LidarrStatus {
    pub version: String,
    #[serde(default)]
    pub app_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct LidarrProfile {
    pub id: i64,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LidarrRootFolder {
    pub id: i64,
    pub path: String,
    #[serde(default)]
    pub free_space: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Image {
    cover_type: String,
    #[serde(default)]
    remote_url: Option<String>,
}

#[allow(clippy::struct_field_names)]
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Statistics {
    #[serde(default)]
    album_count: Option<u32>,
    #[serde(default)]
    track_file_count: Option<u32>,
    #[serde(default)]
    track_count: Option<u32>,
    #[serde(default)]
    total_track_count: Option<u32>,
}

fn picture(images: &[Image], preferred: &str) -> Option<String> {
    images
        .iter()
        .find(|i| i.cover_type == preferred)
        .or_else(|| images.first())
        .and_then(|i| i.remote_url.as_deref())
        .map(encrypted)
}

/// A picture's address over https: Lidarr passes on some from image hosts as plain http,
/// which every one of them also serves encrypted, and the apps load nothing from the
/// internet unencrypted.
fn encrypted(url: &str) -> String {
    match url.get(..7) {
        Some(scheme) if scheme.eq_ignore_ascii_case("http://") => format!("https://{}", &url[7..]),
        _ => url.to_owned(),
    }
}

/// An artist: one Lidarr follows (with an id) or a search result it doesn't yet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LidarrArtist {
    pub id: Option<i64>,
    pub artist_name: String,
    /// `MusicBrainz`'s id, which tells two artists of one name apart.
    pub foreign_artist_id: String,
    pub monitored: bool,
    pub disambiguation: Option<String>,
    pub picture_url: Option<String>,
    pub album_count: Option<u32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawArtist {
    #[serde(default)]
    id: Option<i64>,
    artist_name: String,
    foreign_artist_id: String,
    #[serde(default)]
    monitored: Option<bool>,
    #[serde(default)]
    disambiguation: Option<String>,
    #[serde(default)]
    images: Vec<Image>,
    #[serde(default)]
    statistics: Option<Statistics>,
}

impl From<RawArtist> for LidarrArtist {
    fn from(raw: RawArtist) -> Self {
        Self {
            // Search results carry id 0 for an artist not followed.
            id: raw.id.filter(|id| *id > 0),
            picture_url: picture(&raw.images, "poster"),
            album_count: raw.statistics.and_then(|s| s.album_count),
            artist_name: raw.artist_name,
            foreign_artist_id: raw.foreign_artist_id,
            monitored: raw.monitored.unwrap_or(false),
            disambiguation: raw.disambiguation.filter(|d| !d.is_empty()),
        }
    }
}

/// An album Lidarr knows of, filed or not.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LidarrAlbum {
    pub id: i64,
    pub title: String,
    pub album_type: Option<String>,
    /// ISO 8601, as Lidarr sends it.
    pub release_date: Option<String>,
    pub monitored: bool,
    pub artist_id: Option<i64>,
    pub artist_name: Option<String>,
    pub cover_url: Option<String>,
    pub track_file_count: u32,
    pub total_track_count: u32,
}

impl LidarrAlbum {
    /// At least one of its songs is filed.
    pub fn has_files(&self) -> bool {
        self.track_file_count > 0
    }

    /// Every track is filed.
    pub fn is_complete(&self) -> bool {
        self.total_track_count > 0 && self.track_file_count >= self.total_track_count
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawAlbum {
    id: i64,
    title: String,
    #[serde(default)]
    album_type: Option<String>,
    #[serde(default)]
    release_date: Option<String>,
    #[serde(default)]
    monitored: Option<bool>,
    #[serde(default)]
    artist_id: Option<i64>,
    #[serde(default)]
    images: Vec<Image>,
    #[serde(default)]
    statistics: Option<Statistics>,
    #[serde(default)]
    artist: Option<RawArtist>,
}

impl From<RawAlbum> for LidarrAlbum {
    fn from(raw: RawAlbum) -> Self {
        let stats = raw.statistics.unwrap_or_default();
        Self {
            id: raw.id,
            cover_url: picture(&raw.images, "cover"),
            title: raw.title,
            album_type: raw.album_type,
            release_date: raw.release_date,
            monitored: raw.monitored.unwrap_or(false),
            artist_id: raw.artist_id,
            artist_name: raw.artist.map(|a| a.artist_name),
            track_file_count: stats.track_file_count.unwrap_or(0),
            total_track_count: stats.total_track_count.or(stats.track_count).unwrap_or(0),
        }
    }
}

/// A track, with whether Lidarr has its file.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LidarrTrack {
    pub id: i64,
    pub title: String,
    #[serde(default)]
    pub has_file: bool,
    #[serde(default)]
    pub album_id: Option<i64>,
}

/// Something downloading or waiting to be filed.
#[derive(Debug, Clone, PartialEq)]
pub struct LidarrQueueItem {
    pub id: i64,
    pub title: Option<String>,
    /// "downloading", "queued", "paused", "completed", "failed" and the like.
    pub status: Option<String>,
    /// From 0 to 1.
    pub progress: f64,
    /// "00:12:34", as Lidarr sends it.
    pub time_left: Option<String>,
    pub error_message: Option<String>,
    pub album_title: Option<String>,
    pub artist_name: Option<String>,
    pub cover_url: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawQueueItem {
    id: i64,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    size: Option<f64>,
    #[serde(default)]
    sizeleft: Option<f64>,
    #[serde(default)]
    timeleft: Option<String>,
    #[serde(default)]
    error_message: Option<String>,
    #[serde(default)]
    album: Option<RawAlbum>,
    #[serde(default)]
    artist: Option<RawArtist>,
}

impl From<RawQueueItem> for LidarrQueueItem {
    fn from(raw: RawQueueItem) -> Self {
        let progress = match (raw.size, raw.sizeleft) {
            (Some(size), left) if size > 0.0 => {
                ((size - left.unwrap_or(size)) / size).clamp(0.0, 1.0)
            }
            _ => 0.0,
        };
        let album = raw.album.map(LidarrAlbum::from);
        Self {
            id: raw.id,
            title: raw.title,
            status: raw.status,
            progress,
            time_left: raw.timeleft,
            error_message: raw.error_message.filter(|m| !m.is_empty()),
            artist_name: raw
                .artist
                .map(|a| a.artist_name)
                .or_else(|| album.as_ref().and_then(|a| a.artist_name.clone())),
            cover_url: album.as_ref().and_then(|a| a.cover_url.clone()),
            album_title: album.map(|a| a.title),
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Page<T> {
    #[serde(default = "Vec::new")]
    records: Vec<T>,
    #[serde(default, rename = "page")]
    number: Option<u32>,
    #[serde(default)]
    total_records: Option<u64>,
}

/// One page of the queue, and whether there's another.
#[derive(Debug, Clone, PartialEq)]
pub struct QueuePage {
    pub items: Vec<LidarrQueueItem>,
    /// The page to ask for next, or `None` once this is the last or [`QUEUE_PAGE_LIMIT`] is
    /// reached.
    pub next: Option<u32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Problem {
    error_message: String,
}

/// Whether Lidarr took a request whose answer doesn't matter (some answer with nothing).
pub fn check(response: &Response) -> Result<()> {
    if response.status == 401 {
        return Err(CatalogError::WrongKey);
    }
    if response.is_success() {
        return Ok(());
    }
    // A refusal comes as a list of problems, each with a message.
    if let Ok(problems) = serde_json::from_slice::<Vec<Problem>>(&response.body)
        && let Some(first) = problems.into_iter().next()
    {
        return Err(CatalogError::Rejected(first.error_message));
    }
    Err(CatalogError::Http {
        status: response.status,
    })
}

fn decode<T: for<'de> Deserialize<'de>>(response: &Response) -> Result<T> {
    check(response)?;
    serde_json::from_slice(&response.body).map_err(|_| CatalogError::WrongService)
}

pub fn parse_status(response: &Response) -> Result<LidarrStatus> {
    decode(response)
}

pub fn parse_profiles(response: &Response) -> Result<Vec<LidarrProfile>> {
    decode(response)
}

pub fn parse_root_folders(response: &Response) -> Result<Vec<LidarrRootFolder>> {
    decode(response)
}

/// A list of artists, or the one artist an add answers with.
pub fn parse_artists(response: &Response) -> Result<Vec<LidarrArtist>> {
    check(response)?;
    if let Ok(list) = serde_json::from_slice::<Vec<RawArtist>>(&response.body) {
        return Ok(list.into_iter().map(Into::into).collect());
    }
    serde_json::from_slice::<RawArtist>(&response.body)
        .map(|one| vec![one.into()])
        .map_err(|_| CatalogError::WrongService)
}

pub fn parse_albums(response: &Response) -> Result<Vec<LidarrAlbum>> {
    decode::<Vec<RawAlbum>>(response).map(|list| list.into_iter().map(Into::into).collect())
}

pub fn parse_tracks(response: &Response) -> Result<Vec<LidarrTrack>> {
    decode(response)
}

pub fn parse_queue(response: &Response) -> Result<Vec<LidarrQueueItem>> {
    parse_queue_page(response, 1).map(|page| page.items)
}

/// A page of the queue, asked for as page `asked`. A short page is the last whatever the
/// total says, since the queue moves while it's read.
pub fn parse_queue_page(response: &Response, asked: u32) -> Result<QueuePage> {
    let page = decode::<Page<RawQueueItem>>(response)?;
    let number = page.number.unwrap_or(asked).max(1);
    let full = page.records.len() >= QUEUE_PAGE_SIZE as usize;
    let more_by_total = page
        .total_records
        .is_none_or(|total| u64::from(number) * u64::from(QUEUE_PAGE_SIZE) < total);
    let next = (full && more_by_total && number < QUEUE_PAGE_LIMIT).then_some(number + 1);
    Ok(QueuePage {
        items: page.records.into_iter().map(Into::into).collect(),
        next,
    })
}

// MARK: - Reading what Lidarr has

/// The artist Lidarr already follows by this name: the whole credit first, then its lead.
pub fn followed_artist(name: &str, artists: &[LidarrArtist]) -> Option<usize> {
    artist_candidates(name).iter().find_map(|candidate| {
        let wanted = folded(candidate);
        artists
            .iter()
            .position(|a| a.id.is_some() && folded(&a.artist_name) == wanted)
    })
}

/// The search result that is this artist: only an exact name, since the first result for a
/// name Lidarr doesn't know is someone else.
pub fn lookup_match(name: &str, results: &[LidarrArtist]) -> Option<usize> {
    artist_candidates(name).iter().find_map(|candidate| {
        let wanted = folded(candidate);
        results
            .iter()
            .position(|a| folded(&a.artist_name) == wanted)
    })
}

/// The album of this title among an artist's, by title or by title less its edition.
pub fn album_match(title: &str, albums: &[LidarrAlbum]) -> Option<usize> {
    let titles: Vec<&str> = albums.iter().map(|a| a.title.as_str()).collect();
    match_album(title, &titles)
}

/// What Lidarr knows of a song.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SongState {
    /// Its file is in the collection, on this album.
    Collected { album: String },
    /// Lidarr follows its album and is looking for it.
    Wanted,
    /// Lidarr doesn't have it and isn't looking.
    Missing,
}

/// A song's state from its artist's albums and tracks.
pub fn song_state(title: &str, albums: &[LidarrAlbum], tracks: &[LidarrTrack]) -> SongState {
    let wanted = folded(title);
    let album = |id: Option<i64>| id.and_then(|id| albums.iter().find(|a| a.id == id));
    let matches: Vec<&LidarrTrack> = tracks
        .iter()
        .filter(|t| folded(&t.title) == wanted)
        .collect();
    if let Some(track) = matches.iter().find(|t| t.has_file) {
        let name = album(track.album_id).map_or_else(|| title.to_owned(), |a| a.title.clone());
        return SongState::Collected { album: name };
    }
    if matches
        .iter()
        .any(|t| album(t.album_id).is_some_and(|a| a.monitored))
    {
        SongState::Wanted
    } else {
        SongState::Missing
    }
}
