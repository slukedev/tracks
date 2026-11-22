//! The loose title runs on every song a server, Lidarr or the catalog names, so it has to
//! take any text at all: brackets that never close, dashes with nothing after them, letters
//! that change length when folded.

use proptest::prelude::*;
use tracks_library::{OwnedSongs, loose_key, loose_title};

/// Text from all of Unicode, weighted toward what the loose title cuts at and what breaks
/// byte slicing.
fn awkward_title() -> impl Strategy<Value = String> {
    let tricky = prop::sample::select(vec![
        "(",
        ")",
        "[",
        "]",
        "()",
        "[]",
        ")(",
        " - ",
        "-",
        " -",
        "- ",
        " - Remastered",
        " - remaster",
        "(feat. ",
        "(feat ",
        "(ft.",
        "(featuring ",
        "[Explicit]",
        "(Deluxe)",
        "(Remastered 2011)",
        "(Live)",
        "\u{301}",
        "\u{308}\u{301}",
        "\u{200D}",
        "👩\u{200D}👩\u{200D}👧",
        "\u{200F}",
        "\u{202E}",
        "\u{FEFF}",
        "\u{A0}",
        "\u{3000}",
        "İ",
        "ß",
        "ﬃ",
        "Ⱥ",
        "ǅ",
        "ΐ",
        "𝔄",
        "\u{0}",
        " ",
        "\n",
        "\t",
    ]);
    let piece = prop_oneof![
        3 => tricky.prop_map(str::to_owned),
        2 => any::<char>().prop_map(String::from),
        1 => "[a-zA-Z]{1,8}",
    ];
    prop_oneof![
        prop::collection::vec(piece, 0..24).prop_map(|parts| parts.concat()),
        any::<String>(),
        prop::collection::vec(any::<char>(), 0..2_000).prop_map(String::from_iter),
    ]
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(512))]

    #[test]
    fn any_title_and_artist_is_taken_without_panicking(title in awkward_title(), artist in awkward_title()) {
        let _ = loose_title(&title);
        let _ = loose_key(&title, &artist);
        let mut owned = OwnedSongs::new();
        owned.insert(&title, &artist);
        let _ = owned.owns(&artist, &title);
    }

    /// Only what trails the title goes, so two different songs never come out the same by
    /// something in the middle being rewritten.
    #[test]
    fn the_loose_title_is_the_start_of_the_folded_one(title in awkward_title()) {
        let folded = tracks_model::folded(&title);
        let loose = loose_title(&title);
        prop_assert!(folded.starts_with(&loose), "{loose:?} isn't the start of {folded:?}");
    }

    #[test]
    fn a_song_in_the_library_is_always_owned(title in awkward_title(), artist in awkward_title()) {
        let owned = OwnedSongs::from_songs([(title.as_str(), artist.as_str())]);
        prop_assert!(owned.owns(&title, &artist));
    }
}
