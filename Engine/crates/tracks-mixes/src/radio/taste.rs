//! Which of your songs go together, learned from how you play them. A port of the Swift app's
//! `TasteGraph`.
//!
//! Songs heard in the same sitting, a song or two apart, are neighbors, and the more sittings
//! they share the closer they are. Songs played with everything count for less with each of
//! them, pairings heard on a station count for less than ones you chose, and older sittings
//! fade.

use rustc_hash::{FxHashMap, FxHashSet};

/// One play, as the graph needs it, oldest first.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TastePlay {
    pub identity: String,
    /// The folded artist, to weigh two songs by one artist less together.
    pub artist_key: String,
    pub played_at_ms: i64,
    /// Heard on a station you didn't choose.
    pub is_station: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Neighbor {
    pub identity: String,
    /// How close, from just over 0 to 1 for the song's closest.
    pub closeness: f64,
}

/// Captures further apart than this are separate sittings.
const SITTING_GAP_MS: i64 = 45 * 60_000;
/// How many songs on, within a sitting, still count as heard together.
const REACH: usize = 3;
/// Neighbors kept per song.
const MOST_NEIGHBORS: usize = 24;
const HALF_LIFE_MS: f64 = 365.0 * 86_400_000.0;
const STATION_WEIGHT: f64 = 0.35;
const SAME_ARTIST_WEIGHT: f64 = 0.5;
/// The least a pairing must add up to before it's kept: one sitting long ago isn't a habit.
const LEAST_WEIGHT: f64 = 0.6;

#[derive(Debug, Clone, Default)]
pub struct TasteGraph {
    neighbors: FxHashMap<String, Vec<Neighbor>>,
    leads_into: FxHashMap<String, FxHashMap<String, f64>>,
}

impl TasteGraph {
    pub fn is_empty(&self) -> bool {
        self.neighbors.is_empty()
    }

    /// The song's neighbors, closest first.
    pub fn neighbors(&self, identity: &str) -> &[Neighbor] {
        self.neighbors.get(identity).map_or(&[], Vec::as_slice)
    }

    /// How often `song` has come straight after `previous`, 0 to 1.
    pub fn leads(&self, previous: &str, song: &str) -> f64 {
        self.leads_into
            .get(previous)
            .and_then(|to| to.get(song))
            .copied()
            .unwrap_or(0.0)
    }

    /// `keeping`, when given, keeps only those songs, so a graph for a mix carries nothing the
    /// mix can't pick.
    #[expect(
        clippy::too_many_lines,
        reason = "one pass over the history, as the Swift app has it"
    )]
    pub fn new(plays: &[TastePlay], keeping: Option<&FxHashSet<String>>, now_ms: i64) -> Self {
        if plays.len() < 2 {
            return Self::default();
        }
        let mut index: FxHashMap<&str, u32> = FxHashMap::default();
        let mut identities: Vec<&str> = Vec::new();
        let mut artists: Vec<&str> = Vec::new();
        let mut pairs: FxHashMap<u64, f64> = FxHashMap::default();
        let mut leads: FxHashMap<u64, f64> = FxHashMap::default();
        let mut window: Vec<(u32, i64, f64)> = Vec::new();

        for play in plays {
            if keeping.is_some_and(|k| !k.contains(&play.identity)) {
                continue;
            }
            let song = *index.entry(play.identity.as_str()).or_insert_with(|| {
                identities.push(play.identity.as_str());
                artists.push(play.artist_key.as_str());
                (identities.len() - 1) as u32
            });
            if window
                .last()
                .is_some_and(|&(_, at, _)| play.played_at_ms - at > SITTING_GAP_MS)
            {
                window.clear();
            }
            let age = (now_ms - play.played_at_ms).max(0) as f64;
            let weight = 0.5_f64.powf(age / HALF_LIFE_MS)
                * if play.is_station { STATION_WEIGHT } else { 1.0 };
            for (distance, &(earlier, _, earlier_weight)) in window.iter().rev().enumerate() {
                if earlier == song {
                    continue;
                }
                let same_artist = artists[earlier as usize] == artists[song as usize];
                let together = weight.min(earlier_weight) / (distance + 1) as f64
                    * if same_artist { SAME_ARTIST_WEIGHT } else { 1.0 };
                *pairs.entry(key(earlier, song)).or_default() += together;
                if distance == 0 {
                    *leads.entry(directed(earlier, song)).or_default() += together;
                }
            }
            window.push((song, play.played_at_ms, weight));
            if window.len() > REACH {
                window.remove(0);
            }
        }

        // How much each song pairs in all, for weighing a pairing against how sociable both are.
        let mut reach = vec![0.0; identities.len()];
        for (&key, &weight) in &pairs {
            if weight >= LEAST_WEIGHT {
                reach[(key >> 32) as usize] += weight;
                reach[(key & 0xFFFF_FFFF) as usize] += weight;
            }
        }
        let mut found: Vec<Vec<(u32, f64)>> = vec![Vec::new(); identities.len()];
        for (&key, &weight) in &pairs {
            if weight < LEAST_WEIGHT {
                continue;
            }
            let (a, b) = ((key >> 32) as u32, (key & 0xFFFF_FFFF) as u32);
            // Cosine: a pairing that's a big part of both songs' company is a close one.
            let score = weight / (reach[a as usize] * reach[b as usize]).sqrt();
            found[a as usize].push((b, score));
            found[b as usize].push((a, score));
        }
        let mut neighbors = FxHashMap::default();
        for (song, mut list) in found.into_iter().enumerate() {
            if list.is_empty() {
                continue;
            }
            list.sort_by(|x, y| {
                y.1.total_cmp(&x.1)
                    .then_with(|| identities[x.0 as usize].cmp(identities[y.0 as usize]))
            });
            list.truncate(MOST_NEIGHBORS);
            let best = list[0].1;
            if best <= 0.0 {
                continue;
            }
            neighbors.insert(
                identities[song].to_owned(),
                list.into_iter()
                    .map(|(other, score)| Neighbor {
                        identity: identities[other as usize].to_owned(),
                        closeness: score / best,
                    })
                    .collect(),
            );
        }

        let mut leaving = vec![0.0; identities.len()];
        for (&key, &weight) in &leads {
            leaving[(key >> 32) as usize] += weight;
        }
        let mut leads_into: FxHashMap<String, FxHashMap<String, f64>> = FxHashMap::default();
        for (&key, &weight) in &leads {
            if weight < LEAST_WEIGHT {
                continue;
            }
            let (from, to) = ((key >> 32) as usize, (key & 0xFFFF_FFFF) as usize);
            leads_into
                .entry(identities[from].to_owned())
                .or_default()
                .insert(identities[to].to_owned(), weight / leaving[from]);
        }
        Self {
            neighbors,
            leads_into,
        }
    }
}

fn key(a: u32, b: u32) -> u64 {
    let (low, high) = if a < b { (a, b) } else { (b, a) };
    u64::from(low) << 32 | u64::from(high)
}

fn directed(from: u32, to: u32) -> u64 {
    u64::from(from) << 32 | u64::from(to)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn play(identity: &str, artist: &str, at_minutes: i64) -> TastePlay {
        TastePlay {
            identity: identity.to_owned(),
            artist_key: artist.to_owned(),
            played_at_ms: at_minutes * 60_000,
            is_station: false,
        }
    }

    #[test]
    fn songs_played_together_become_neighbors() {
        let mut plays = Vec::new();
        // Five evenings of a then b, with c alone on other days.
        for day in 0..5 {
            let start = day * 24 * 60;
            plays.push(play("a", "x", start));
            plays.push(play("b", "y", start + 4));
            plays.push(play("c", "z", start + 600));
        }
        let graph = TasteGraph::new(&plays, None, 5 * 24 * 60 * 60_000);
        assert_eq!(
            graph.neighbors("a").first().map(|n| n.identity.as_str()),
            Some("b")
        );
        assert_eq!(graph.neighbors("c"), &[]);
        assert!(graph.leads("a", "b") > 0.9);
        assert!(graph.leads("b", "a") < 1e-9);
    }

    #[test]
    fn keeps_only_what_it_is_told_to() {
        let plays = vec![
            play("a", "x", 0),
            play("b", "y", 2),
            play("a", "x", 60 * 24),
            play("b", "y", 60 * 24 + 2),
        ];
        let keeping: FxHashSet<String> = std::iter::once("a".to_owned()).collect();
        assert!(TasteGraph::new(&plays, Some(&keeping), 0).is_empty());
    }
}
