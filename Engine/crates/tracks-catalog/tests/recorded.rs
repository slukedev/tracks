//! Requests and answers on recorded traffic. The iTunes answers were recorded from the live
//! API; the Lidarr ones follow its v1 API's shapes, trimmed.

use tracks_catalog::lidarr::{
    self, AddOptions, Lidarr, Monitor, SongState, album_match, followed_artist, lookup_match,
    song_state,
};
use tracks_catalog::{AlbumKind, CatalogError, Method, Response, parse_catalog};

fn fixture(name: &str) -> Response {
    let path = format!("{}/tests/fixtures/{name}.json", env!("CARGO_MANIFEST_DIR"));
    Response::new(200, std::fs::read(path).expect("fixture"))
}

#[test]
fn itunes_songs_carry_previews_and_large_covers() {
    let found = parse_catalog(&fixture("itunes_song")).expect("readable");
    assert_eq!(found.songs.len(), 3);
    let song = &found.songs[0];
    assert_eq!(
        (song.title.as_str(), song.artist_name.as_str()),
        ("Let Down", "Radiohead")
    );
    assert_eq!(song.album_title.as_deref(), Some("OK Computer"));
    assert_eq!(song.year, Some(1997));
    assert_eq!(song.track_number, Some(5));
    assert!(
        song.preview_url
            .as_deref()
            .is_some_and(|u| u.contains("AudioPreview"))
    );
    assert!(
        song.artwork_url
            .as_deref()
            .is_some_and(|u| u.ends_with("/600x600bb.jpg"))
    );
    assert_eq!(song.identity(), "let down\u{1F}radiohead");
}

#[test]
fn itunes_lookups_lead_with_what_was_looked_up() {
    let albums = parse_catalog(&fixture("itunes_artist_albums")).expect("readable");
    assert_eq!(albums.artists.len(), 1);
    assert_eq!(albums.artists[0].name, "Radiohead");
    let titles: Vec<&str> = albums.albums.iter().map(|a| a.title.as_str()).collect();
    assert_eq!(
        titles,
        [
            "A Moon Shaped Pool",
            "OK Computer",
            "In Rainbows",
            "The Bends"
        ]
    );

    let tracks = parse_catalog(&fixture("itunes_album_tracks")).expect("readable");
    assert_eq!(tracks.albums.len(), 1);
    assert_eq!(tracks.albums[0].track_count, Some(12));
    assert_eq!(tracks.songs.len(), 12);
    assert_eq!(tracks.songs[1].title, "Paranoid Android");

    let songs = parse_catalog(&fixture("itunes_artist_songs")).expect("readable");
    let ep = songs
        .songs
        .iter()
        .find(|s| s.album_title.as_deref() == Some("Creep - EP"));
    assert!(ep.is_some(), "song albums keep the catalog's name");
}

#[test]
fn itunes_artists_and_failures() {
    let found = parse_catalog(&fixture("itunes_artist")).expect("readable");
    assert_eq!(
        found.artists.iter().map(|a| a.id).collect::<Vec<_>>(),
        [657_515, 39_753_073]
    );
    assert_eq!(found.albums.first().map(|a| a.kind), None);
    assert_eq!(
        parse_catalog(&Response::new(503, "")),
        Err(CatalogError::Http { status: 503 })
    );
    assert_eq!(
        parse_catalog(&Response::new(200, "<html>")),
        Err(CatalogError::WrongService)
    );
    let album = parse_catalog(&fixture("itunes_album"))
        .expect("readable")
        .albums;
    assert_eq!(album[0].kind, AlbumKind::Album);
}

#[test]
fn lidarr_requests_carry_the_key_in_a_header() {
    let api = Lidarr::new(" http://nas:8686/ ", "secret");
    let request = api.look_up_artists("Wet Leg");
    assert_eq!(
        request.url,
        "http://nas:8686/api/v1/artist/lookup?term=Wet%20Leg"
    );
    assert_eq!(request.header("x-api-key"), Some("secret"));
    assert!(!request.url.contains("secret"));
    assert!(!format!("{api:?}").contains("secret"));

    let monitor = api.set_monitored(&[12, 13], true);
    assert_eq!(monitor.method, Method::Put);
    assert_eq!(
        String::from_utf8(monitor.body).expect("utf8"),
        r#"{"albumIds":[12,13],"monitored":true}"#
    );
    let search = api.search_albums(&[12]);
    assert_eq!(
        String::from_utf8(search.body).expect("utf8"),
        r#"{"name":"AlbumSearch","albumIds":[12]}"#
    );
}

#[test]
fn lidarr_adds_an_artist_as_ios_does() {
    let api = Lidarr::new("https://lidarr.example.com", "k");
    let found = lidarr::parse_artists(&fixture("lidarr_lookup")).expect("readable");
    let index = lookup_match("Wet Leg", &found).expect("an exact name");
    assert_eq!(
        found[index].foreign_artist_id,
        "1b2a4c51-3b1e-4c1d-9f6f-2d8a5c2f0e11"
    );
    assert_eq!(found[index].id, None, "a search result isn't followed yet");
    let options = AddOptions {
        quality_profile_id: 2,
        metadata_profile_id: 1,
        root_folder_path: "/music".into(),
        monitor: Monitor::None,
        search_now: false,
    };
    let request = api.add_artist(&found[index], &options);
    assert_eq!(request.method, Method::Post);
    let body: serde_json::Value = serde_json::from_slice(&request.body).expect("json");
    assert_eq!(body["artistName"], "Wet Leg");
    assert_eq!(body["monitorNewItems"], "none");
    assert_eq!(body["addOptions"]["monitor"], "none");
    assert_eq!(body["addOptions"]["searchForMissingAlbums"], false);
}

#[test]
fn lidarr_reads_its_collection() {
    assert_eq!(
        lidarr::parse_status(&fixture("lidarr_status"))
            .expect("status")
            .version,
        "2.9.6.4552"
    );
    assert_eq!(
        lidarr::parse_profiles(&fixture("lidarr_profiles"))
            .expect("profiles")
            .len(),
        2
    );
    assert_eq!(
        lidarr::parse_root_folders(&fixture("lidarr_rootfolders")).expect("folders")[0].path,
        "/music"
    );

    let artists = lidarr::parse_artists(&fixture("lidarr_artists")).expect("artists");
    assert_eq!(
        followed_artist("Radiohead feat. Thom Yorke", &artists),
        Some(0)
    );
    assert_eq!(followed_artist("Bjork", &artists), Some(1));
    assert!(artists[0].picture_url.is_some());

    let albums = lidarr::parse_albums(&fixture("lidarr_albums")).expect("albums");
    assert!(albums[0].is_complete());
    assert!(!albums[1].has_files());
    assert_eq!(album_match("Amnesiac", &albums), Some(2));
    assert_eq!(albums[0].artist_name.as_deref(), Some("Radiohead"));

    let tracks = lidarr::parse_tracks(&fixture("lidarr_tracks")).expect("tracks");
    assert_eq!(
        song_state("Let Down", &albums, &tracks),
        SongState::Collected {
            album: "OK Computer".into()
        }
    );
    assert_eq!(
        song_state("Everything In Its Right Place", &albums, &tracks),
        SongState::Wanted
    );
    assert_eq!(
        song_state("Packt Like Sardines in a Crushd Tin Box", &albums, &tracks),
        SongState::Missing
    );
    assert_eq!(song_state("Creep", &albums, &tracks), SongState::Missing);
}

#[test]
fn lidarr_queue_and_refusals() {
    let queue = lidarr::parse_queue(&fixture("lidarr_queue")).expect("queue");
    assert_eq!(queue.len(), 2);
    assert!((queue[0].progress - 0.75).abs() < 1e-9);
    assert_eq!(queue[0].album_title.as_deref(), Some("Kid A"));
    assert_eq!(queue[0].artist_name.as_deref(), Some("Radiohead"));
    assert_eq!(queue[1].progress, 0.0);
    assert!(queue[1].error_message.is_some());

    let problem = fixture("lidarr_problem");
    let refused = Response::new(400, problem.body);
    assert_eq!(
        lidarr::check(&refused),
        Err(CatalogError::Rejected(
            "This artist has already been added.".into()
        ))
    );
    assert_eq!(
        lidarr::parse_status(&Response::new(401, "")),
        Err(CatalogError::WrongKey)
    );
    assert_eq!(
        lidarr::parse_status(&Response::new(200, "<html>")),
        Err(CatalogError::WrongService)
    );
    assert!(CatalogError::Http { status: 503 }.is_retryable());
}
