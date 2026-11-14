//! Properties every input must keep: the picks never depend on the order things came in,
//! never hold anything twice, never pass the limits, and ownership always adds up.

use proptest::prelude::*;
use tracks_library::{
    ArtistTaste, Candidate, CandidateKind, OwnedSongs, Ownership, Rules, SimilarArtist,
    classify_album, rank,
};

const NOW: i64 = 1_800_000_000_000;
const ARTISTS: [&str; 6] = [
    "Mara Solis",
    "Beyoncé",
    "BEYONCE",
    "Nils Frahm",
    "Rosalía",
    "M83",
];

fn candidate() -> impl Strategy<Value = Candidate> {
    (
        0u32..400,
        0usize..ARTISTS.len(),
        any::<bool>(),
        0u8..=4,
        any::<bool>(),
    )
        .prop_map(|(id, artist, is_album, owned, is_requested)| Candidate {
            id: format!("c{id}"),
            kind: if is_album {
                CandidateKind::Album
            } else {
                CandidateKind::Song
            },
            artist: ARTISTS[artist].to_owned(),
            owned_share: f64::from(owned) / 4.0,
            is_requested,
        })
}

fn candidates() -> impl Strategy<Value = Vec<Candidate>> {
    // Ids are the host's and unique; duplicates are dropped here as the host would.
    prop::collection::vec(candidate(), 0..40).prop_map(|mut list| {
        let mut seen = std::collections::HashSet::new();
        list.retain(|c| seen.insert(c.id.clone()));
        list
    })
}

fn tastes() -> impl Strategy<Value = Vec<ArtistTaste>> {
    prop::collection::vec((0usize..ARTISTS.len(), 0u32..200, 0i64..800), 0..6).prop_map(|list| {
        list.into_iter()
            .map(|(artist, plays, days)| ArtistTaste {
                name: ARTISTS[artist].to_owned(),
                plays,
                last_played_ms: Some(NOW - days * 86_400_000),
            })
            .collect()
    })
}

fn similar() -> impl Strategy<Value = Vec<SimilarArtist>> {
    prop::collection::vec((0usize..ARTISTS.len(), 0usize..ARTISTS.len()), 0..8).prop_map(|list| {
        list.into_iter()
            .map(|(of, name)| SimilarArtist {
                of: ARTISTS[of].to_owned(),
                name: ARTISTS[name].to_owned(),
            })
            .collect()
    })
}

fn rules() -> impl Strategy<Value = Rules> {
    (1usize..30, 1usize..5).prop_map(|(limit, per_artist)| Rules {
        limit,
        per_artist,
        now_ms: NOW,
        half_life_days: 90.0,
    })
}

proptest! {
    #[test]
    fn the_order_candidates_come_in_changes_nothing(candidates in candidates(), taste in tastes(), similar in similar(), rules in rules(), seed in any::<u64>()) {
        let mut shuffled = candidates.clone();
        // A cheap, seeded reorder: rotate, then reverse every other run.
        if !shuffled.is_empty() {
            let len = shuffled.len();
            shuffled.rotate_left((seed as usize) % len);
            if seed % 2 == 0 { shuffled.reverse(); }
        }
        let first: Vec<String> = rank(&candidates, &taste, &similar, &rules).into_iter().map(|p| p.id).collect();
        let second: Vec<String> = rank(&shuffled, &taste, &similar, &rules).into_iter().map(|p| p.id).collect();
        prop_assert_eq!(first, second);
    }

    #[test]
    fn picks_keep_to_the_rules(candidates in candidates(), taste in tastes(), similar in similar(), rules in rules()) {
        let picks = rank(&candidates, &taste, &similar, &rules);
        prop_assert!(picks.len() <= rules.limit);
        let mut seen = std::collections::HashSet::new();
        for pick in &picks {
            prop_assert!(seen.insert(pick.id.clone()), "picked twice: {}", pick.id);
            let source = candidates
                .iter()
                .find(|c| c.id == pick.id)
                .ok_or_else(|| TestCaseError::fail(format!("picked something it wasn't given: {}", pick.id)))?;
            prop_assert!(source.owned_share < 1.0 || source.is_requested);
            prop_assert!(pick.score > 0.0 && pick.score.is_finite());
        }
        let mut per_artist = std::collections::HashMap::new();
        for pick in &picks {
            let artist = candidates.iter().find(|c| c.id == pick.id).map(|c| tracks_model::folded(&c.artist)).unwrap_or_default();
            *per_artist.entry(artist).or_insert(0usize) += 1;
        }
        prop_assert!(per_artist.values().all(|&count| count <= rules.per_artist));
    }

    #[test]
    fn ownership_adds_up(owned in prop::collection::vec(0usize..12, 0..12), album in prop::collection::vec(0usize..12, 0..16)) {
        let library = OwnedSongs::from_songs(owned.iter().map(|n| (["One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve"][*n], "Mara Solis")));
        let names = ["One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve"];
        let result = classify_album(album.iter().map(|n| (names[*n], "Mara Solis")), &library);
        prop_assert_eq!(result.owned_count + result.missing.len(), album.len());
        prop_assert!(result.missing.windows(2).all(|pair| pair[0] < pair[1]));
        let expected = if album.is_empty() || result.owned_count == 0 {
            Ownership::Missing
        } else if result.missing.is_empty() {
            Ownership::Owned
        } else {
            Ownership::Partial
        };
        prop_assert_eq!(result.ownership, expected);
        for index in &result.missing {
            prop_assert!(!library.owns(names[album[*index]], "Mara Solis"));
        }
    }
}
