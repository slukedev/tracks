//! What to suggest you get next: songs and albums by the artists you play, and artists like
//! them, that you don't have yet. The host gathers candidates (from the catalog, Last.fm or a
//! server) and says which are already yours; this ranks them the same way everywhere.

use std::collections::{HashMap, HashSet};

use tracks_model::folded;

/// An artist to suggest from, with how much you play them.
#[derive(Debug, Clone, PartialEq)]
pub struct Seed {
    pub artist: String,
    /// Plays, or any measure of how much they're liked. Weights are tempered (square root),
    /// so one artist you play constantly doesn't fill the whole list.
    pub weight: f64,
}

/// Something that could be suggested.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    /// What makes two candidates the same thing: a song or album identity.
    pub key: String,
    pub artist: String,
    /// Where it came in its source's list for that artist, 0 first: the catalog lists an
    /// artist's songs by popularity.
    pub rank: u32,
    /// Already in your music.
    pub owned: bool,
    /// A single or an EP, which counts for less than an album.
    pub is_minor: bool,
}

/// How much of an album you have.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Ownership {
    Owned,
    /// Some of its songs, not all.
    Partial,
    Missing,
}

impl Ownership {
    /// From how many of its songs you have, out of how many it has. An album with an unknown
    /// count is yours once you have any of it.
    pub fn of(owned: u32, total: Option<u32>) -> Self {
        match (owned, total) {
            (0, _) => Self::Missing,
            (_, Some(total)) if owned < total => Self::Partial,
            _ => Self::Owned,
        }
    }
}

/// The candidates worth suggesting, best first, as indices into `candidates`.
///
/// Owned ones go, as do repeats of a key. The rest are scored by how much you play their
/// artist and how high they came in that artist's list, then spread out so the same artist
/// doesn't come twice in a row and no artist takes more than `per_artist` places.
pub fn rank(seeds: &[Seed], candidates: &[Candidate], per_artist: usize, limit: usize) -> Vec<usize> {
    let weights = seed_weights(seeds);
    let mut seen = HashSet::new();
    let mut scored: Vec<(usize, f64, String)> = Vec::new();
    for (index, candidate) in candidates.iter().enumerate() {
        if candidate.owned {
            continue;
        }
        let artist = folded(&candidate.artist);
        let weight = weights
            .get(&artist)
            .or_else(|| weights.get(&folded(&tracks_model::credits::lead(&candidate.artist))))
            .copied()
            .unwrap_or(0.0);
        let mut score = weight / (1.0 + f64::from(candidate.rank) * 0.35);
        if candidate.is_minor {
            score *= 0.6;
        }
        scored.push((index, score, artist));
    }
    // Highest first; the source's own order breaks ties.
    scored.sort_by(|a, b| b.1.total_cmp(&a.1).then(a.0.cmp(&b.0)));
    scored.retain(|(index, _, _)| seen.insert(candidates[*index].key.clone()));
    spread(scored, per_artist.max(1), limit)
}

/// What one artist's listeners play alongside them, closest first, from any source (Deezer,
/// Last.fm).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Related {
    /// The artist of yours it was asked for.
    pub from: String,
    pub names: Vec<String>,
}

/// An artist like yours, worth suggesting from.
#[derive(Debug, Clone, PartialEq)]
pub struct SimilarArtist {
    pub name: String,
    /// Your artists they're like, the closest first.
    pub like: Vec<String>,
    /// A weight to rank their songs by, below every one of your own artists: someone new
    /// shouldn't crowd out the artists you actually play.
    pub weight: f64,
}

/// Artists like yours, best first: those near the top of several of your artists' lists come
/// before one high on a single list. Your own artists and anyone in `excluded` (artists you
/// play, ones you asked not to see) never come back.
pub fn similar_artists(seeds: &[Seed], related: &[Related], excluded: &[String], limit: usize) -> Vec<SimilarArtist> {
    let weights = seed_weights(seeds);
    let skip: HashSet<String> = seeds
        .iter()
        .map(|s| folded(&s.artist))
        .chain(excluded.iter().map(|n| folded(n)))
        .collect();
    let mut found: Vec<(String, f64, Vec<String>)> = Vec::new();
    let mut at: HashMap<String, usize> = HashMap::new();
    for list in related {
        let from = weights.get(&folded(&list.from)).copied().unwrap_or(0.0);
        if from <= 0.0 {
            continue;
        }
        let mut seen_here = HashSet::new();
        for (position, name) in list.names.iter().enumerate() {
            let key = folded(name);
            if key.is_empty() || skip.contains(&key) || !seen_here.insert(key.clone()) {
                continue;
            }
            let score = from / (1.0 + position as f64 * 0.15);
            let index = *at.entry(key).or_insert_with(|| {
                found.push((name.trim().to_owned(), 0.0, Vec::new()));
                found.len() - 1
            });
            let entry = &mut found[index];
            entry.1 += score;
            if !entry.2.contains(&list.from) {
                entry.2.push(list.from.clone());
            }
        }
    }
    // Most liked first; who was found first breaks ties, so the order holds from day to day.
    let mut order: Vec<usize> = (0..found.len()).collect();
    order.sort_by(|&a, &b| found[b].1.total_cmp(&found[a].1).then(a.cmp(&b)));
    order.truncate(limit);
    let top = order.first().map_or(1.0, |&i| found[i].1);
    let floor = seeds.iter().map(|s| s.weight.max(0.0)).fold(f64::INFINITY, f64::min);
    let floor = if floor.is_finite() { floor } else { 1.0 };
    order
        .into_iter()
        .map(|i| {
            let (name, score, like) = found[i].clone();
            SimilarArtist {
                name,
                like,
                weight: floor * 0.6 * (score / top),
            }
        })
        .collect()
}

fn seed_weights(seeds: &[Seed]) -> HashMap<String, f64> {
    let mut weights: HashMap<String, f64> = HashMap::new();
    for seed in seeds {
        let entry = weights.entry(folded(&seed.artist)).or_default();
        *entry = entry.max(seed.weight.max(0.0).sqrt());
    }
    let top = weights.values().copied().fold(0.0_f64, f64::max);
    if top > 0.0 {
        for w in weights.values_mut() {
            *w /= top;
        }
    }
    weights
}

/// Takes the best remaining each time, skipping one by the artist just taken while another
/// artist is left.
fn spread(mut scored: Vec<(usize, f64, String)>, per_artist: usize, limit: usize) -> Vec<usize> {
    let mut taken: HashMap<String, usize> = HashMap::new();
    scored.retain(|(_, _, artist)| {
        let count = taken.entry(artist.clone()).or_default();
        *count += 1;
        *count <= per_artist
    });
    let mut out = Vec::with_capacity(limit.min(scored.len()));
    let mut last: Option<String> = None;
    while out.len() < limit && !scored.is_empty() {
        let pick = scored
            .iter()
            .position(|(_, _, artist)| last.as_ref() != Some(artist))
            .unwrap_or(0);
        let (index, _, artist) = scored.remove(pick);
        out.push(index);
        last = Some(artist);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(key: &str, artist: &str, rank: u32) -> Candidate {
        Candidate {
            key: key.to_owned(),
            artist: artist.to_owned(),
            rank,
            owned: false,
            is_minor: false,
        }
    }

    #[test]
    fn owned_and_repeats_never_come_back() {
        let seeds = [Seed { artist: "Radiohead".into(), weight: 10.0 }];
        let mut owned = candidate("a", "Radiohead", 0);
        owned.owned = true;
        let list = [owned, candidate("b", "Radiohead", 1), candidate("b", "Radiohead", 2)];
        assert_eq!(rank(&seeds, &list, 5, 10), vec![1]);
    }

    #[test]
    fn artists_are_spread_and_capped() {
        let seeds = [
            Seed { artist: "Radiohead".into(), weight: 400.0 },
            Seed { artist: "Björk".into(), weight: 25.0 },
        ];
        let list = [
            candidate("r1", "Radiohead", 0),
            candidate("r2", "Radiohead", 1),
            candidate("r3", "Radiohead", 2),
            candidate("b1", "Bjork", 0),
        ];
        let order = rank(&seeds, &list, 2, 10);
        assert_eq!(order, vec![0, 3, 1]);
    }

    #[test]
    fn a_collaboration_counts_for_its_lead() {
        let seeds = [Seed { artist: "Drake".into(), weight: 9.0 }];
        let list = [candidate("x", "Nobody", 0), candidate("d", "Drake & 21 Savage", 3)];
        assert_eq!(rank(&seeds, &list, 3, 1), vec![1]);
    }

    #[test]
    fn similar_artists_shared_by_several_of_yours_lead() {
        let seeds = [
            Seed { artist: "Radiohead".into(), weight: 100.0 },
            Seed { artist: "Portishead".into(), weight: 25.0 },
        ];
        let related = [
            Related {
                from: "Radiohead".into(),
                names: vec!["Thom Yorke".into(), "Massive Attack".into(), "Portishead".into(), "Muse".into()],
            },
            Related { from: "Portishead".into(), names: vec!["Massive Attack".into(), "Tricky".into(), "Bjork".into()] },
        ];
        let found = similar_artists(&seeds, &related, &["Björk".into()], 3);
        let names: Vec<&str> = found.iter().map(|a| a.name.as_str()).collect();
        assert_eq!(names, ["Massive Attack", "Thom Yorke", "Muse"], "yours and excluded ones never come back");
        assert_eq!(found[0].like, ["Radiohead", "Portishead"]);
        assert!(found.iter().all(|a| a.weight <= 25.0 * 0.6 + 1e-9), "below every artist of yours");
        assert!(found[0].weight > found[2].weight);
    }

    #[test]
    fn related_lists_for_strangers_count_for_nothing() {
        let seeds = [Seed { artist: "Radiohead".into(), weight: 4.0 }];
        let related = [Related { from: "Nobody".into(), names: vec!["Muse".into()] }];
        assert_eq!(similar_artists(&seeds, &related, &[], 5), []);
        assert_eq!(similar_artists(&[], &related, &[], 5), []);
    }

    #[test]
    fn ownership_counts_songs() {
        assert_eq!(Ownership::of(0, Some(12)), Ownership::Missing);
        assert_eq!(Ownership::of(4, Some(12)), Ownership::Partial);
        assert_eq!(Ownership::of(12, Some(12)), Ownership::Owned);
        assert_eq!(Ownership::of(3, None), Ownership::Owned);
    }
}
