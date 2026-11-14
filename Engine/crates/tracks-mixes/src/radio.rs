//! Tracks Radio's picker: an endless station chosen a song at a time from what you love and
//! new finds like it, that leans toward what lands and away from what you skip.
//!
//! The core of the Swift app's `LiveMix`, without the catalog-specific steering. The host
//! hands in the candidates it can play, each with a weight; the picker chooses, keeps artists
//! apart, and learns from how each pick went.

use rustc_hash::{FxHashMap, FxHashSet};

use crate::random::SeededRandom;

/// A song the station may play.
#[derive(Debug, Clone, PartialEq)]
pub struct RadioCandidate {
    pub identity: String,
    /// The folded artist name, to keep artists apart and learn per artist.
    pub artist_key: String,
    /// How much the person likes it, or how good a find it is; any positive scale.
    pub weight: f64,
    /// A song the person hasn't played: a new find.
    pub is_new: bool,
}

/// How a pick went.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Listen {
    /// Skipped early.
    Skipped,
    /// Skipped after it had played a fair while.
    MovedOn,
    /// Played to the end, or near it.
    Finished,
}

const ARTIST_GAP: usize = 2;
const SKIP_DIM: f64 = 0.6;
const MOVED_ON_DIM: f64 = 0.85;
const LANDING_LIFT: f64 = 1.25;
const BIAS_RANGE: (f64, f64) = (0.15, 3.0);
const SHARE_RANGE: (f64, f64) = (0.05, 0.6);
const SHARE_STEP: f64 = 0.05;
/// Picks kept from the last round when every candidate has played.
const CARRY_OVER: usize = 20;

/// Chooses Tracks Radio's next song.
#[derive(Debug, Clone)]
pub struct RadioPicker {
    candidates: Vec<RadioCandidate>,
    by_identity: FxHashMap<String, usize>,
    picked: FxHashSet<String>,
    history: Vec<String>,
    artist_bias: FxHashMap<String, f64>,
    new_share: f64,
    random: SeededRandom,
}

impl RadioPicker {
    /// A station over `candidates`. `new_share` is how often a new find plays, from 0 to 1.
    pub fn new(candidates: Vec<RadioCandidate>, new_share: f64, seed: u64) -> Self {
        let mut unique = FxHashSet::default();
        let candidates: Vec<RadioCandidate> = candidates
            .into_iter()
            .filter(|c| c.weight > 0.0 && unique.insert(c.identity.clone()))
            .collect();
        let by_identity = candidates
            .iter()
            .enumerate()
            .map(|(i, c)| (c.identity.clone(), i))
            .collect();
        Self {
            candidates,
            by_identity,
            picked: FxHashSet::default(),
            history: Vec::new(),
            artist_bias: FxHashMap::default(),
            new_share: new_share.clamp(0.0, 1.0),
            random: SeededRandom::new(seed),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.candidates.is_empty()
    }

    /// The share of new finds the station is aiming for now.
    pub fn new_share(&self) -> f64 {
        self.new_share
    }

    /// The next song's identity, or `None` when there are no candidates.
    #[expect(
        clippy::should_implement_trait,
        reason = "a station never runs out, so it isn't an iterator; `next` is the FFI's name"
    )]
    pub fn next(&mut self) -> Option<String> {
        if self.candidates.is_empty() {
            return None;
        }
        if self.open().next().is_none() {
            // Every song has played: start a new round, keeping the latest picks out.
            let keep: FxHashSet<String> = self
                .history
                .iter()
                .rev()
                .take(CARRY_OVER.min(self.candidates.len() / 2))
                .cloned()
                .collect();
            self.picked = keep;
        }
        let want_new = self.random.unit() < self.new_share;
        let recent: Vec<&str> = self
            .history
            .iter()
            .rev()
            .take(ARTIST_GAP)
            .filter_map(|id| self.by_identity.get(id))
            .map(|&i| self.candidates[i].artist_key.as_str())
            .collect();

        let open: Vec<usize> = self.open().collect();
        let side: Vec<usize> = open
            .iter()
            .copied()
            .filter(|&i| self.candidates[i].is_new == want_new)
            .collect();
        let pool = if side.is_empty() { open } else { side };
        let spaced: Vec<usize> = pool
            .iter()
            .copied()
            .filter(|&i| !recent.contains(&self.candidates[i].artist_key.as_str()))
            .collect();
        let choices = if spaced.is_empty() { pool } else { spaced };

        let weights: Vec<f64> = choices
            .iter()
            .map(|&i| {
                let c = &self.candidates[i];
                c.weight * self.artist_bias.get(&c.artist_key).copied().unwrap_or(1.0)
            })
            .collect();
        let total: f64 = weights.iter().sum();
        let mut target = self.random.unit() * total;
        let mut chosen = *choices.last()?;
        for (&index, weight) in choices.iter().zip(&weights) {
            target -= weight;
            if target <= 0.0 {
                chosen = index;
                break;
            }
        }
        let identity = self.candidates[chosen].identity.clone();
        self.picked.insert(identity.clone());
        self.history.push(identity.clone());
        Some(identity)
    }

    fn open(&self) -> impl Iterator<Item = usize> + '_ {
        (0..self.candidates.len()).filter(|&i| !self.picked.contains(&self.candidates[i].identity))
    }

    /// Learns from how a pick went: skips dim the artist and, for new finds, the share of new
    /// finds; songs that land lift both.
    pub fn note(&mut self, identity: &str, listen: Listen) {
        let Some(&index) = self.by_identity.get(identity) else {
            return;
        };
        let candidate = &self.candidates[index];
        let factor = match listen {
            Listen::Skipped => SKIP_DIM,
            Listen::MovedOn => MOVED_ON_DIM,
            Listen::Finished => LANDING_LIFT,
        };
        let bias = self
            .artist_bias
            .entry(candidate.artist_key.clone())
            .or_insert(1.0);
        *bias = (*bias * factor).clamp(BIAS_RANGE.0, BIAS_RANGE.1);
        if candidate.is_new {
            let step = match listen {
                Listen::Skipped => -SHARE_STEP,
                Listen::MovedOn => -SHARE_STEP / 2.0,
                Listen::Finished => SHARE_STEP,
            };
            self.new_share = (self.new_share + step).clamp(SHARE_RANGE.0, SHARE_RANGE.1);
        }
    }

    /// Plays `identity` now (the person chose it) and remembers it as picked.
    pub fn mark_played(&mut self, identity: &str) {
        if self.by_identity.contains_key(identity) {
            self.picked.insert(identity.to_owned());
            self.history.push(identity.to_owned());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidates(n: usize, artists: usize) -> Vec<RadioCandidate> {
        (0..n)
            .map(|i| RadioCandidate {
                identity: format!("s{i}"),
                artist_key: format!("a{}", i % artists),
                weight: 1.0,
                is_new: i % 4 == 0,
            })
            .collect()
    }

    #[test]
    fn never_repeats_within_a_round_and_keeps_artists_apart() {
        let mut picker = RadioPicker::new(candidates(40, 8), 0.25, 1);
        let picks: Vec<String> = (0..40).filter_map(|_| picker.next()).collect();
        let unique: FxHashSet<&String> = picks.iter().collect();
        assert_eq!(unique.len(), 40);
        let artist = |id: &str| id[1..].parse::<usize>().expect("number") % 8;
        let back_to_back = picks.windows(2).filter(|w| artist(&w[0]) == artist(&w[1])).count();
        assert!(back_to_back <= 2, "{back_to_back} repeats");
    }

    #[test]
    fn keeps_going_after_every_song_played() {
        let mut picker = RadioPicker::new(candidates(6, 3), 0.2, 2);
        assert_eq!((0..30).filter_map(|_| picker.next()).count(), 30);
    }

    #[test]
    fn skipping_an_artist_plays_them_less() {
        let mut picker = RadioPicker::new(candidates(200, 2), 0.0, 3);
        for _ in 0..6 {
            picker.note("s0", Listen::Skipped);
        }
        let picks: Vec<String> = (0..60).filter_map(|_| picker.next()).collect();
        let skipped = picks
            .iter()
            .filter(|id| id[1..].parse::<usize>().expect("number") % 2 == 0)
            .count();
        assert!(skipped < 25, "{skipped} of 60 from the skipped artist");
    }

    #[test]
    fn new_share_follows_how_new_finds_land() {
        let mut picker = RadioPicker::new(candidates(8, 4), 0.3, 4);
        picker.note("s0", Listen::Skipped);
        assert!(picker.new_share() < 0.3);
        picker.note("s4", Listen::Finished);
        picker.note("s4", Listen::Finished);
        assert!(picker.new_share() > 0.3);
    }

    #[test]
    fn empty_station_has_nothing() {
        let mut picker = RadioPicker::new(Vec::new(), 0.2, 0);
        assert!(picker.is_empty());
        assert_eq!(picker.next(), None);
    }
}
