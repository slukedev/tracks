//! Whether two names mean the same song or album, when the catalog, a server and Lidarr each
//! write them their own way: "Havana (feat. Young Thug)" is "Havana", "Yeat & Drake" is
//! Yeat's, "Song - Remastered 2011" is "Song", and "Coastlines (Deluxe Edition)" is
//! "Coastlines". A live, remixed or otherwise different recording still isn't the same.
//!
//! Ported from the Swift app's `ServerSongMatcher`, `CatalogMatcher.normalize` and
//! `LidarrClient.albumKey`, so every platform agrees on what you already have.

use std::collections::HashSet;

use tracks_model::{credits_by_name, folded};

/// Words in a trailing bracket that mean a different recording. "(Live)" stays when
/// comparing; "(Remastered)" and "(feat. …)" go.
const VERSION_QUALIFIERS: &[&str] = &[
    "live",
    "remix",
    "rework",
    "mixed",
    "mix",
    "acoustic",
    "demo",
    "instrumental",
    "edit",
    "version",
    "reprise",
    "cover",
    "karaoke",
    "sped up",
    "slowed",
    "extended",
    "dub",
    "bootleg",
    "flip",
    "session",
];

/// Words in a trailing bracket that only name an edition of the same album.
const EDITIONS: &[&str] = &[
    "deluxe",
    "remaster",
    "expanded",
    "anniversary",
    "bonus track",
    "special edition",
    "edition",
    "explicit",
    "clean",
    "mono",
    "stereo",
];

/// A song's name, as the matcher reads it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SongName<'a> {
    pub title: &'a str,
    pub artist: &'a str,
}

/// The first candidate that is the same song: named exactly the same if one is, else the
/// first whose title matches once the edition is set aside and that shares an artist.
pub fn best_match(title: &str, artist: &str, candidates: &[SongName<'_>]) -> Option<usize> {
    let identity = tracks_model::song_key(title, artist);
    if let Some(exact) = candidates
        .iter()
        .position(|c| tracks_model::song_key(c.title, c.artist) == identity)
    {
        return Some(exact);
    }
    let wanted_title = song_title(title);
    let wanted_artists = artists(artist);
    candidates.iter().position(|c| {
        song_title(c.title) == wanted_title && !artists(c.artist).is_disjoint(&wanted_artists)
    })
}

/// A title without what doesn't change the recording: a trailing "(feat. …)" or
/// "(Remastered)", and a " - Remastered 2011" suffix.
pub fn song_title(value: &str) -> String {
    let mut result = value;
    if let Some(dash) = result.rfind(" - ")
        && result[dash + 3..].to_lowercase().contains("remaster")
    {
        result = &result[..dash];
    }
    normalize(result)
}

/// Folded, with a trailing bracket dropped unless it names another version.
pub fn normalize(value: &str) -> String {
    let trimmed = value.trim_end();
    if let Some((open, inside)) = trailing_bracket(trimmed) {
        let qualifier = inside.to_lowercase();
        if !VERSION_QUALIFIERS.iter().any(|q| qualifier.contains(q)) {
            return folded(trimmed[..open].trim_end());
        }
    }
    folded(trimmed)
}

/// Every artist a credit names, plus the whole credit: "Yeat & Drake" is Yeat, Drake and
/// "Yeat & Drake". "Florence + the Machine" stays one artist.
pub fn artists(credit: &str) -> HashSet<String> {
    let whole = folded(credit);
    if whole.is_empty() {
        return HashSet::new();
    }
    let mut set: HashSet<String> = credits_by_name(credit, "")
        .into_iter()
        .map(|c| c.identity)
        .collect();
    set.insert(whole);
    set
}

/// An album's title for matching, less the edition the catalog and servers add:
/// "Coastlines (Deluxe Edition)" and "Coastlines - Single" are "coastlines". Anything else in
/// brackets stays: "1989 (Taylor's Version)" is its own album.
pub fn album_key(title: &str) -> String {
    let mut key = folded(title).trim().to_owned();
    for suffix in [" - single", " - ep"] {
        if let Some(stripped) = key.strip_suffix(suffix) {
            key = stripped.to_owned();
        }
    }
    while let Some((open, inside)) = trailing_bracket(&key) {
        if !EDITIONS.iter().any(|e| inside.contains(e)) {
            break;
        }
        key = key[..open].trim_end().to_owned();
    }
    key
}

/// The album a title means among `candidates`: the same folded title, or the same once
/// editions are set aside. Nothing looser: "Americana" isn't "America".
pub fn match_album(title: &str, candidates: &[&str]) -> Option<usize> {
    let exact = folded(title);
    if let Some(found) = candidates.iter().position(|c| folded(c) == exact) {
        return Some(found);
    }
    let key = album_key(title);
    candidates.iter().position(|c| album_key(c) == key)
}

/// A credited name, then its first artist where several are credited: "Drake & 21 Savage"
/// is looked for as itself, then as Drake.
pub fn artist_candidates(name: &str) -> Vec<String> {
    let lead = tracks_model::credits::lead(name);
    if lead.is_empty() || lead == name {
        vec![name.to_owned()]
    } else {
        vec![name.to_owned(), lead]
    }
}

/// Where a trailing "(...)" or "[...]" opens, and what it holds.
fn trailing_bracket(text: &str) -> Option<(usize, &str)> {
    let close = text.chars().last()?;
    let open_char = match close {
        ')' => '(',
        ']' => '[',
        _ => return None,
    };
    let open = text.rfind(open_char)?;
    let inside = &text[open + 1..text.len() - 1];
    // "(a (b) c)" has no clean trailing bracket.
    if inside.contains(['(', ')', '[', ']']) {
        return None;
    }
    Some((open, inside))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names<'a>(list: &'a [(&'a str, &'a str)]) -> Vec<SongName<'a>> {
        list.iter()
            .map(|(title, artist)| SongName { title, artist })
            .collect()
    }

    #[test]
    fn exact_names_win_over_looser_ones() {
        let list = [
            ("Havana (feat. Young Thug)", "Camila Cabello"),
            ("Havana", "Camila Cabello"),
        ];
        assert_eq!(
            best_match("Havana", "Camila Cabello", &names(&list)),
            Some(1)
        );
    }

    #[test]
    fn features_remasters_and_shared_credits_match() {
        let list = [("Havana (feat. Young Thug)", "Camila Cabello & Young Thug")];
        assert_eq!(
            best_match("Havana", "Camila Cabello", &names(&list)),
            Some(0)
        );
        let list = [("Let Down - Remastered 2017", "Radiohead")];
        assert_eq!(best_match("Let Down", "Radiohead", &names(&list)), Some(0));
    }

    #[test]
    fn other_recordings_and_artists_never_match() {
        let list = [
            ("Creep (Live)", "Radiohead"),
            ("Creep (Acoustic)", "Radiohead"),
            ("Creep", "TLC"),
        ];
        assert_eq!(best_match("Creep", "Radiohead", &names(&list)), None);
    }

    #[test]
    fn album_editions_fold_but_versions_stay() {
        assert_eq!(album_key("Coastlines (Deluxe Edition)"), "coastlines");
        assert_eq!(
            album_key("OK Computer OKNOTOK 1997 2017"),
            "ok computer oknotok 1997 2017"
        );
        assert_eq!(album_key("Lift - Single"), "lift");
        assert_eq!(
            album_key("1989 (Taylor's Version)"),
            "1989 (taylor's version)"
        );
        assert_eq!(
            album_key("Abbey Road (Remastered) [Super Deluxe]"),
            "abbey road"
        );
    }

    #[test]
    fn albums_match_exactly_then_by_edition_only() {
        let albums = ["America", "Coastlines (Deluxe Edition)", "Americana"];
        assert_eq!(match_album("Americana", &albums), Some(2));
        assert_eq!(match_album("Coastlines", &albums), Some(1));
        assert_eq!(match_album("Amerika", &albums), None);
    }

    #[test]
    fn credited_names_fall_back_to_their_lead() {
        assert_eq!(
            artist_candidates("Drake & 21 Savage"),
            vec!["Drake & 21 Savage", "Drake"]
        );
        assert_eq!(artist_candidates("Radiohead"), vec!["Radiohead"]);
    }
}
