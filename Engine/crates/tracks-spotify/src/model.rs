//! Tracks, albums, artists and playlists as the app shows them, from librespot's metadata or
//! the Web API's JSON.

use librespot_core::SpotifyUri;
use librespot_metadata::image::Images;
use serde::Deserialize;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArtistRef {
    pub uri: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlbumRef {
    pub uri: String,
    pub title: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Track {
    pub uri: String,
    pub title: String,
    pub artists: Vec<ArtistRef>,
    pub album: Option<AlbumRef>,
    pub duration_ms: u32,
    pub explicit: bool,
    /// For matching the same recording across sources.
    pub isrc: Option<String>,
    pub artwork_url: Option<String>,
    pub track_number: u32,
    pub disc_number: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Album {
    pub uri: String,
    pub title: String,
    pub artists: Vec<ArtistRef>,
    pub release_year: Option<i32>,
    pub artwork_url: Option<String>,
    pub track_uris: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Artist {
    pub uri: String,
    pub name: String,
    pub artwork_url: Option<String>,
    pub top_track_uris: Vec<String>,
    pub album_uris: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Playlist {
    pub uri: String,
    pub name: String,
    pub owner: Option<String>,
    pub artwork_url: Option<String>,
    pub track_uris: Vec<String>,
    pub track_count: u32,
}

/// One page of a longer list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Page<T> {
    pub items: Vec<T>,
    pub total: u32,
    /// Where the next page starts, or nothing at the end.
    pub next_offset: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResults {
    pub tracks: Page<Track>,
    pub albums: Page<Album>,
    pub artists: Page<Artist>,
    pub playlists: Page<Playlist>,
}

fn uri_string(uri: &SpotifyUri) -> String {
    uri.to_uri().unwrap_or_default()
}

fn image_url(images: &Images) -> Option<String> {
    images
        .iter()
        .max_by_key(|image| image.width)
        .and_then(|image| image.id.to_base16().ok())
        .map(|id| format!("https://i.scdn.co/image/{id}"))
}

fn artist_refs(artists: &[librespot_metadata::Artist]) -> Vec<ArtistRef> {
    artists
        .iter()
        .map(|a| ArtistRef {
            uri: uri_string(&a.id),
            name: a.name.clone(),
        })
        .collect()
}

impl From<&librespot_metadata::Track> for Track {
    fn from(track: &librespot_metadata::Track) -> Self {
        Self {
            uri: uri_string(&track.id),
            title: track.name.clone(),
            artists: artist_refs(&track.artists),
            album: Some(AlbumRef {
                uri: uri_string(&track.album.id),
                title: track.album.name.clone(),
            }),
            duration_ms: u32::try_from(track.duration).unwrap_or(0),
            explicit: track.is_explicit,
            isrc: track
                .external_ids
                .iter()
                .find(|id| id.external_type.eq_ignore_ascii_case("isrc"))
                .map(|id| id.id.clone()),
            artwork_url: image_url(&track.album.covers),
            track_number: u32::try_from(track.number).unwrap_or(0),
            disc_number: u32::try_from(track.disc_number).unwrap_or(0),
        }
    }
}

impl From<&librespot_metadata::Album> for Album {
    fn from(album: &librespot_metadata::Album) -> Self {
        Self {
            uri: uri_string(&album.id),
            title: album.name.clone(),
            artists: artist_refs(&album.artists),
            release_year: Some(album.date.as_utc().year()).filter(|&y| y > 0),
            artwork_url: image_url(&album.covers),
            track_uris: album.tracks().map(uri_string).collect(),
        }
    }
}

impl Artist {
    pub(crate) fn from_metadata(artist: &librespot_metadata::Artist, country: &str) -> Self {
        Self {
            uri: uri_string(&artist.id),
            name: artist.name.clone(),
            artwork_url: image_url(&artist.portraits),
            top_track_uris: artist
                .top_tracks
                .for_country(country)
                .iter()
                .map(uri_string)
                .collect(),
            album_uris: artist
                .albums_current()
                .chain(artist.singles_current())
                .map(uri_string)
                .collect(),
        }
    }
}

impl From<&librespot_metadata::Playlist> for Playlist {
    fn from(playlist: &librespot_metadata::Playlist) -> Self {
        let track_uris: Vec<String> = playlist.tracks().map(uri_string).collect();
        Self {
            uri: uri_string(&playlist.id),
            name: playlist.name().to_owned(),
            owner: None,
            artwork_url: playlist
                .attributes
                .picture_sizes
                .iter()
                .max_by_key(|size| size.target_name == "large")
                .map(|size| size.url.clone()),
            track_count: u32::try_from(playlist.length)
                .unwrap_or_default()
                .max(u32::try_from(track_uris.len()).unwrap_or(u32::MAX)),
            track_uris,
        }
    }
}

// MARK: - Web API JSON

#[derive(Debug, Deserialize)]
pub(crate) struct WebImage {
    url: String,
    #[serde(default)]
    width: Option<u32>,
}

fn web_image(images: &[WebImage]) -> Option<String> {
    images
        .iter()
        .max_by_key(|image| image.width.unwrap_or(0))
        .map(|image| image.url.clone())
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebArtistRef {
    uri: String,
    name: String,
}

impl From<WebArtistRef> for ArtistRef {
    fn from(artist: WebArtistRef) -> Self {
        Self {
            uri: artist.uri,
            name: artist.name,
        }
    }
}

#[derive(Debug, Default, Deserialize)]
struct WebExternalIds {
    isrc: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebAlbumRef {
    uri: String,
    name: String,
    #[serde(default)]
    images: Vec<WebImage>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebTrack {
    uri: String,
    name: String,
    #[serde(default)]
    artists: Vec<WebArtistRef>,
    album: Option<WebAlbumRef>,
    #[serde(default)]
    duration_ms: u32,
    #[serde(default)]
    explicit: bool,
    #[serde(default)]
    external_ids: WebExternalIds,
    #[serde(default)]
    track_number: u32,
    #[serde(default)]
    disc_number: u32,
}

impl From<WebTrack> for Track {
    fn from(track: WebTrack) -> Self {
        let artwork_url = track.album.as_ref().and_then(|a| web_image(&a.images));
        Self {
            uri: track.uri,
            title: track.name,
            artists: track.artists.into_iter().map(Into::into).collect(),
            album: track.album.map(|a| AlbumRef {
                uri: a.uri,
                title: a.name,
            }),
            duration_ms: track.duration_ms,
            explicit: track.explicit,
            isrc: track.external_ids.isrc,
            artwork_url,
            track_number: track.track_number,
            disc_number: track.disc_number,
        }
    }
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebUri {
    uri: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebAlbum {
    uri: String,
    name: String,
    #[serde(default)]
    artists: Vec<WebArtistRef>,
    #[serde(default)]
    release_date: Option<String>,
    #[serde(default)]
    images: Vec<WebImage>,
    #[serde(default)]
    tracks: Option<WebPage<WebUri>>,
}

impl From<WebAlbum> for Album {
    fn from(album: WebAlbum) -> Self {
        Self {
            uri: album.uri,
            title: album.name,
            artists: album.artists.into_iter().map(Into::into).collect(),
            release_year: album
                .release_date
                .as_deref()
                .and_then(|d| d.get(..4))
                .and_then(|y| y.parse().ok()),
            artwork_url: web_image(&album.images),
            track_uris: album
                .tracks
                .map(|page| page.items.into_iter().flatten().map(|t| t.uri).collect())
                .unwrap_or_default(),
        }
    }
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebArtist {
    uri: String,
    name: String,
    #[serde(default)]
    images: Vec<WebImage>,
}

impl From<WebArtist> for Artist {
    fn from(artist: WebArtist) -> Self {
        Self {
            uri: artist.uri,
            name: artist.name,
            artwork_url: web_image(&artist.images),
            top_track_uris: Vec::new(),
            album_uris: Vec::new(),
        }
    }
}

#[derive(Debug, Deserialize)]
struct WebOwner {
    display_name: Option<String>,
    id: Option<String>,
}

#[derive(Debug, Deserialize)]
struct WebCount {
    #[serde(default)]
    total: u32,
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebPlaylist {
    uri: String,
    name: String,
    owner: Option<WebOwner>,
    #[serde(default)]
    images: Option<Vec<WebImage>>,
    // Spotify renamed `tracks` to `items` in February 2026; older answers still use `tracks`.
    #[serde(default, alias = "tracks")]
    items: Option<WebCount>,
}

impl From<WebPlaylist> for Playlist {
    fn from(playlist: WebPlaylist) -> Self {
        Self {
            uri: playlist.uri,
            name: playlist.name,
            owner: playlist.owner.and_then(|o| o.display_name.or(o.id)),
            artwork_url: playlist.images.as_deref().and_then(web_image),
            track_uris: Vec::new(),
            track_count: playlist.items.map_or(0, |c| c.total),
        }
    }
}

/// A page of the Web API. Search can hold `null` where an item was removed.
#[derive(Debug, Deserialize)]
pub(crate) struct WebPage<T> {
    #[serde(default = "Vec::new")]
    pub items: Vec<Option<T>>,
    #[serde(default)]
    pub total: u32,
    #[serde(default)]
    pub offset: u32,
    #[serde(default)]
    pub next: Option<String>,
}

impl<T> WebPage<T> {
    pub(crate) fn into_page<U>(self, convert: impl Fn(T) -> U) -> Page<U> {
        let count = u32::try_from(self.items.len()).unwrap_or(u32::MAX);
        let next_offset = self
            .next
            .is_some()
            .then(|| self.offset.saturating_add(count));
        Page {
            items: self.items.into_iter().flatten().map(convert).collect(),
            total: self.total,
            next_offset,
        }
    }
}

impl<T> Default for WebPage<T> {
    fn default() -> Self {
        Self {
            items: Vec::new(),
            total: 0,
            offset: 0,
            next: None,
        }
    }
}

#[derive(Debug, Deserialize)]
pub(crate) struct SavedTrack {
    pub track: Option<WebTrack>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct SavedAlbum {
    pub album: Option<WebAlbum>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct WebSearch {
    #[serde(default)]
    pub tracks: WebPage<WebTrack>,
    #[serde(default)]
    pub albums: WebPage<WebAlbum>,
    #[serde(default)]
    pub artists: WebPage<WebArtist>,
    #[serde(default)]
    pub playlists: WebPage<WebPlaylist>,
}

#[cfg(test)]
mod tests {
    use librespot_core::FileId;
    use librespot_metadata::image::{Image, ImageSize};

    use super::*;

    #[test]
    fn the_widest_cover_is_used() {
        let image = |byte: u8, width| Image {
            id: FileId::from_raw(&[byte; 20]),
            size: ImageSize::DEFAULT,
            width,
            height: width,
        };
        let images = Images(vec![image(0xaa, 64), image(0xbb, 640), image(0xcc, 300)]);
        assert_eq!(
            image_url(&images),
            Some(format!("https://i.scdn.co/image/{}", "bb".repeat(20)))
        );
        assert_eq!(image_url(&Images::default()), None);
    }

    #[test]
    fn the_next_page_starts_after_every_item_including_removed_ones() {
        let page = WebPage {
            items: vec![Some(1), None, Some(3)],
            total: 10,
            offset: 4,
            next: Some("next".into()),
        };
        let page = page.into_page(|n| n * 2);
        assert_eq!(page.items, [2, 6]);
        assert_eq!(page.next_offset, Some(7));
    }

    #[test]
    fn a_playlist_reads_its_count_from_items_or_tracks() {
        for field in ["items", "tracks"] {
            let json = format!(
                r#"{{"uri":"spotify:playlist:1","name":"Mix","owner":{{"id":"luke"}},"{field}":{{"total":7}}}}"#
            );
            let playlist: Playlist = serde_json::from_str::<WebPlaylist>(&json)
                .expect("json")
                .into();
            assert_eq!(playlist.track_count, 7);
            assert_eq!(playlist.owner.as_deref(), Some("luke"));
        }
    }
}
