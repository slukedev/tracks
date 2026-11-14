//! What you own of an album, song by song.
//!
//! A song is yours when your library has it under the same identity (folded title and
//! artist, as the history keys it), or under the same loose key: Apple Music, Last.fm and
//! servers name the same recording differently ("Havana (feat. Young Thug)" and "Havana",
//! "Song - Remastered 2011" and "Song"). A live or remixed recording stays its own song.

use rustc_hash::FxHashSet;
use tracks_model::{KEY_SEPARATOR, credits, folded, song_key};

/// The songs in your library, for asking whether you own one.
#[derive(Debug, Clone, Default)]
pub struct OwnedSongs {
    exact: FxHashSet<String>,
    loose: FxHashSet<String>,
}

impl OwnedSongs {
    pub fn new() -> Self {
        Self::default()
    }

    /// Every song in a library, each as `(title, artist)`.
    pub fn from_songs<'a>(songs: impl IntoIterator<Item = (&'a str, &'a str)>) -> Self {
        let mut owned = Self::new();
        for (title, artist) in songs {
            owned.insert(title, artist);
        }
        owned
    }

    pub fn insert(&mut self, title: &str, artist: &str) {
        self.exact.insert(song_key(title, artist));
        self.loose.insert(loose_key(title, artist));
    }

    pub fn owns(&self, title: &str, artist: &str) -> bool {
        self.exact.contains(&song_key(title, artist))
            || self.loose.contains(&loose_key(title, artist))
    }

    /// How many different songs, by identity.
    pub fn len(&self) -> usize {
        self.exact.len()
    }

    pub fn is_empty(&self) -> bool {
        self.exact.is_empty()
    }
}

/// A title without what doesn't change the recording, folded: a trailing "(feat. …)",
/// "(Remastered 2011)" or "[Explicit]", and a " - Remastered" suffix.
pub fn loose_title(title: &str) -> String {
    let mut key = folded(title);
    if let Some(dash) = key.rfind(" - ")
        && dash > 0
        && key[dash + 3..].contains("remaster")
    {
        key.truncate(dash);
    }
    while let Some(open) = trailing_group(&key) {
        let inside = &key[open + 1..key.len() - 1];
        if open == 0 || !is_set_aside(inside) {
            break;
        }
        key.truncate(open);
        key.truncate(key.trim_end().len());
    }
    key
}

/// Where the bracketed group the key ends with opens, if it ends with one.
fn trailing_group(key: &str) -> Option<usize> {
    let opener = match key.chars().last()? {
        ')' => '(',
        ']' => '[',
        _ => return None,
    };
    key.rfind(opener)
}

/// What a title can carry in brackets without being another recording.
fn is_set_aside(inside: &str) -> bool {
    const FEATURES: [&str; 4] = ["feat.", "feat ", "ft.", "featuring "];
    const EDITIONS: [&str; 7] = [
        "remaster",
        "deluxe",
        "expanded",
        "anniversary",
        "bonus track",
        "explicit",
        "clean",
    ];
    let inside = inside.trim_start();
    FEATURES.iter().any(|lead| inside.starts_with(lead))
        || EDITIONS.iter().any(|word| inside.contains(word))
}

/// The loose title and the lead artist: "Havana (feat. Young Thug)" by "Camila Cabello &
/// Young Thug" and "Havana" by "Camila Cabello" share one.
pub fn loose_key(title: &str, artist: &str) -> String {
    let mut key = loose_title(title);
    key.push(KEY_SEPARATOR);
    key.push_str(&folded(&credits::lead(artist)));
    key
}

/// How much of an album is yours.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Ownership {
    /// Every song.
    Owned,
    /// Some songs, not all.
    Partial,
    /// None, or the album has no songs to judge by.
    Missing,
}

/// An album's songs judged against your library.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlbumOwnership {
    pub ownership: Ownership,
    /// The positions of the songs you don't own, in album order.
    pub missing: Vec<usize>,
    pub owned_count: usize,
}

impl AlbumOwnership {
    /// The share of its songs you own, from 0 to 1; 0 for an album with no songs.
    pub fn owned_share(&self) -> f64 {
        let total = self.owned_count + self.missing.len();
        if total == 0 {
            0.0
        } else {
            self.owned_count as f64 / total as f64
        }
    }
}

/// Judges an album's songs, each `(title, artist)` in album order, against your library.
pub fn classify_album<'a>(
    tracks: impl IntoIterator<Item = (&'a str, &'a str)>,
    owned: &OwnedSongs,
) -> AlbumOwnership {
    let mut missing = Vec::new();
    let mut owned_count = 0;
    for (position, (title, artist)) in tracks.into_iter().enumerate() {
        if owned.owns(title, artist) {
            owned_count += 1;
        } else {
            missing.push(position);
        }
    }
    let ownership = match (owned_count, missing.is_empty()) {
        (0, _) => Ownership::Missing,
        (_, true) => Ownership::Owned,
        _ => Ownership::Partial,
    };
    AlbumOwnership {
        ownership,
        missing,
        owned_count,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn library(songs: &[(&str, &str)]) -> OwnedSongs {
        OwnedSongs::from_songs(songs.iter().copied())
    }

    #[test]
    fn owns_a_song_under_its_exact_identity() {
        let owned = library(&[("Halo", "Beyoncé")]);
        assert!(owned.owns("Halo", "Beyoncé"));
    }

    #[test]
    fn owns_a_song_whatever_its_case_accents_or_spacing() {
        let owned = library(&[("Halo", "Beyoncé")]);
        assert!(owned.owns("  HALO ", "beyonce"));
    }

    #[test]
    fn does_not_own_the_same_title_by_someone_else() {
        let owned = library(&[("Halo", "Beyoncé")]);
        assert!(!owned.owns("Halo", "Depeche Mode"));
    }

    #[test]
    fn owns_a_song_named_with_its_featured_artist() {
        let owned = library(&[("Havana", "Camila Cabello")]);
        assert!(owned.owns("Havana (feat. Young Thug)", "Camila Cabello & Young Thug"));
    }

    #[test]
    fn owns_a_featured_credit_the_other_way_round() {
        let owned = library(&[(
            "Havana (feat. Young Thug)",
            "Camila Cabello feat. Young Thug",
        )]);
        assert!(owned.owns("Havana", "Camila Cabello"));
    }

    #[test]
    fn owns_a_remaster_of_a_song_it_has() {
        let owned = library(&[("Heroes", "David Bowie")]);
        assert!(owned.owns("Heroes - 2017 Remaster", "David Bowie"));
        assert!(owned.owns("Heroes (Remastered 2017)", "David Bowie"));
    }

    #[test]
    fn a_live_recording_is_its_own_song() {
        let owned = library(&[("Heroes", "David Bowie")]);
        assert!(!owned.owns("Heroes (Live)", "David Bowie"));
        assert!(!owned.owns("Heroes - Live at Wembley", "David Bowie"));
    }

    #[test]
    fn a_remix_is_its_own_song() {
        let owned = library(&[("Midnight City", "M83")]);
        assert!(!owned.owns("Midnight City (Eric Prydz Remix)", "M83"));
    }

    #[test]
    fn an_empty_library_owns_nothing() {
        let owned = OwnedSongs::new();
        assert!(owned.is_empty());
        assert!(!owned.owns("Halo", "Beyoncé"));
    }

    #[test]
    fn counts_each_song_once() {
        let owned = library(&[
            ("Halo", "Beyoncé"),
            ("halo", "BEYONCE"),
            ("Sweet Dreams", "Beyoncé"),
        ]);
        assert_eq!(owned.len(), 2);
    }

    #[test]
    fn loose_title_drops_editions_and_features_only() {
        assert_eq!(loose_title("Song (feat. Someone)"), "song");
        assert_eq!(loose_title("Song [Explicit]"), "song");
        assert_eq!(loose_title("Song (Deluxe Edition) (feat. A)"), "song");
        assert_eq!(loose_title("Song - Remastered 2011"), "song");
        assert_eq!(loose_title("Song (Live)"), "song (live)");
        assert_eq!(loose_title("Song - Live"), "song - live");
        assert_eq!(
            loose_title("Song (Taylor's Version)"),
            "song (taylor's version)"
        );
    }

    #[test]
    fn loose_title_keeps_a_title_that_is_only_brackets() {
        assert_eq!(loose_title("(Remastered)"), "(remastered)");
    }

    #[test]
    fn loose_key_uses_the_lead_artist() {
        assert_eq!(
            loose_key("Havana (feat. Young Thug)", "Camila Cabello & Young Thug"),
            loose_key("Havana", "Camila Cabello")
        );
    }

    fn album<'a>(titles: &[&'a str]) -> Vec<(&'a str, &'a str)> {
        titles.iter().map(|title| (*title, "Mara Solis")).collect()
    }

    #[test]
    fn an_album_with_every_song_is_owned() {
        let owned = library(&[("One", "Mara Solis"), ("Two", "Mara Solis")]);
        let result = classify_album(album(&["One", "Two"]), &owned);
        assert_eq!(
            result,
            AlbumOwnership {
                ownership: Ownership::Owned,
                missing: vec![],
                owned_count: 2
            }
        );
    }

    #[test]
    fn an_album_with_some_songs_is_partly_owned_and_lists_the_rest_in_order() {
        let owned = library(&[("Two", "Mara Solis")]);
        let result = classify_album(album(&["One", "Two", "Three"]), &owned);
        assert_eq!(
            result,
            AlbumOwnership {
                ownership: Ownership::Partial,
                missing: vec![0, 2],
                owned_count: 1
            }
        );
    }

    #[test]
    fn an_album_with_none_of_its_songs_is_missing() {
        let owned = library(&[("Elsewhere", "Mara Solis")]);
        let result = classify_album(album(&["One", "Two"]), &owned);
        assert_eq!(result.ownership, Ownership::Missing);
        assert_eq!(result.missing, vec![0, 1]);
    }

    #[test]
    fn an_album_with_no_songs_is_missing_with_nothing_to_get() {
        let result = classify_album(
            Vec::<(&str, &str)>::new(),
            &library(&[("One", "Mara Solis")]),
        );
        assert_eq!(
            result,
            AlbumOwnership {
                ownership: Ownership::Missing,
                missing: vec![],
                owned_count: 0
            }
        );
        assert!((result.owned_share() - 0.0).abs() < f64::EPSILON);
    }

    #[test]
    fn a_song_listed_twice_is_judged_at_each_place() {
        let owned = library(&[("One", "Mara Solis")]);
        let result = classify_album(album(&["One", "Two", "One"]), &owned);
        assert_eq!(result.missing, vec![1]);
        assert_eq!(result.owned_count, 2);
    }

    #[test]
    fn owned_share_is_the_fraction_owned() {
        let owned = library(&[("One", "Mara Solis")]);
        let result = classify_album(album(&["One", "Two", "Three", "Four"]), &owned);
        assert!((result.owned_share() - 0.25).abs() < f64::EPSILON);
    }

    #[test]
    fn a_compilation_is_judged_by_each_songs_own_artist() {
        let owned = library(&[("Halo", "Beyoncé")]);
        let result = classify_album([("Halo", "Beyoncé"), ("Halo", "Depeche Mode")], &owned);
        assert_eq!(result.ownership, Ownership::Partial);
        assert_eq!(result.missing, vec![1]);
    }
}
