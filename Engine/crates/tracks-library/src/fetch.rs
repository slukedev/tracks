//! Whether music you don't own can be got, and which way.
//!
//! Two things can fetch: a Subsonic server that finds songs it doesn't have (Octo, in front
//! of Navidrome, does: it answers a search with songs from elsewhere, and starring one
//! fetches its file), and Lidarr, which fetches whole albums into the library a server plays.
//! A plain Subsonic server only has what's on it, so with nothing else nothing can be got.

/// What can fetch music right now.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct FetchSources {
    /// A server you can reach that finds songs it doesn't have.
    pub server_finds: bool,
    /// Lidarr, connected.
    pub lidarr: bool,
}

/// How to get something.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GetRoute {
    /// Ask the server to keep it: it fetches the file.
    Server,
    /// Ask Lidarr for its album.
    Lidarr,
}

/// The fewest songs from outside a server's library one search must bring to show the server
/// finds songs. A plain server's search only strays outside its last sync for songs added
/// since, which are rarely this many and rarely most of an answer.
pub const MIN_FINDS: u32 = 4;

/// Whether a search answer shows its server finds songs it doesn't have: at least
/// [`MIN_FINDS`] of them from outside its synced library, and more than half the answer.
pub fn search_shows_finds(returned: u32, outside_library: u32) -> bool {
    outside_library <= returned
        && outside_library >= MIN_FINDS
        && u64::from(outside_library) * 2 > u64::from(returned)
}

/// Whether anything can fetch, so the places that offer to get music show at all.
pub fn can_get(sources: FetchSources) -> bool {
    sources.server_finds || sources.lidarr
}

/// How to get one song: from the server that found it, or else its album from Lidarr.
/// - `found_on_server`: a server that finds songs has this one to fetch.
/// - `has_album`: the album it's on is known, which Lidarr needs.
pub fn song_route(
    found_on_server: bool,
    has_album: bool,
    sources: FetchSources,
) -> Option<GetRoute> {
    if found_on_server && sources.server_finds {
        Some(GetRoute::Server)
    } else if has_album && sources.lidarr {
        Some(GetRoute::Lidarr)
    } else {
        None
    }
}

/// How to get an album: Lidarr first, which files the whole album at the quality you chose,
/// or else song by song from a server that found it.
/// - `found_on_server`: a server that finds songs has its songs to fetch.
pub fn album_route(found_on_server: bool, sources: FetchSources) -> Option<GetRoute> {
    if sources.lidarr {
        Some(GetRoute::Lidarr)
    } else if found_on_server && sources.server_finds {
        Some(GetRoute::Server)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOTHING: FetchSources = FetchSources {
        server_finds: false,
        lidarr: false,
    };
    const SERVER: FetchSources = FetchSources {
        server_finds: true,
        lidarr: false,
    };
    const LIDARR: FetchSources = FetchSources {
        server_finds: false,
        lidarr: true,
    };
    const BOTH: FetchSources = FetchSources {
        server_finds: true,
        lidarr: true,
    };

    #[test]
    fn a_search_mostly_from_outside_the_library_shows_finds() {
        // As Octo answers a full search: 12 from the library, 48 found elsewhere.
        assert!(search_shows_finds(60, 48));
    }

    #[test]
    fn a_search_answered_from_the_library_shows_no_finds() {
        assert!(!search_shows_finds(50, 0));
    }

    #[test]
    fn a_few_songs_newer_than_the_last_sync_show_no_finds() {
        assert!(!search_shows_finds(3, 3));
        assert!(!search_shows_finds(40, 12));
    }

    #[test]
    fn exactly_half_from_outside_shows_no_finds() {
        assert!(!search_shows_finds(8, 4));
        assert!(search_shows_finds(7, 4));
    }

    #[test]
    fn an_empty_answer_shows_no_finds() {
        assert!(!search_shows_finds(0, 0));
    }

    #[test]
    fn a_nonsensical_count_shows_no_finds() {
        assert!(!search_shows_finds(2, 10));
    }

    #[test]
    fn nothing_can_get_without_a_source() {
        assert!(!can_get(NOTHING));
        assert!(can_get(SERVER));
        assert!(can_get(LIDARR));
        assert!(can_get(BOTH));
    }

    #[test]
    fn a_song_comes_from_the_server_that_found_it() {
        assert_eq!(song_route(true, true, SERVER), Some(GetRoute::Server));
        assert_eq!(song_route(true, true, BOTH), Some(GetRoute::Server));
    }

    #[test]
    fn a_song_no_server_found_comes_with_its_album_from_lidarr() {
        assert_eq!(song_route(false, true, BOTH), Some(GetRoute::Lidarr));
        assert_eq!(song_route(false, true, LIDARR), Some(GetRoute::Lidarr));
    }

    #[test]
    fn a_song_with_no_album_cant_come_from_lidarr() {
        assert_eq!(song_route(false, false, LIDARR), None);
    }

    #[test]
    fn a_song_found_on_a_server_that_doesnt_find_isnt_got_from_it() {
        assert_eq!(song_route(true, true, NOTHING), None);
        assert_eq!(song_route(true, false, LIDARR), None);
    }

    #[test]
    fn an_album_comes_from_lidarr_first() {
        assert_eq!(album_route(true, BOTH), Some(GetRoute::Lidarr));
        assert_eq!(album_route(false, LIDARR), Some(GetRoute::Lidarr));
    }

    #[test]
    fn an_album_comes_from_the_server_without_lidarr() {
        assert_eq!(album_route(true, SERVER), Some(GetRoute::Server));
        assert_eq!(album_route(false, SERVER), None);
        assert_eq!(album_route(true, NOTHING), None);
    }
}
