//! Which songs the person skips, kept on the device: the skip half of the Swift app's
//! `ListeningSignals`. It steers Tracks Radio, never the history or the stats.

use rustc_hash::FxHashMap;
use serde::{Deserialize, Serialize};

/// How long a skip counts against a song.
pub const SKIP_MEMORY_MS: i64 = 60 * 86_400_000;
/// Songs remembered, so the file can't grow without end. The ones skipped longest ago go.
const SONG_LIMIT: usize = 1_000;
/// Recent skips it takes to leave a song out.
const SKIPS_TO_DROP: usize = 2;

/// Whether leaving a song after `played_ms` counts as skipping it: under thirty seconds, or
/// under a third of a short song. A song that ran to the end is never a skip, and neither is
/// one whose length isn't known.
pub fn is_skip(played_ms: i64, duration_ms: Option<i64>) -> bool {
    let Some(duration) = duration_ms.filter(|&d| d > 0) else {
        return false;
    };
    if played_ms < 0 || played_ms >= duration - 1_000 {
        return false;
    }
    played_ms < (duration / 3).min(30_000)
}

/// Skips per song identity, newest last.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SkipMemory {
    skips: FxHashMap<String, Vec<i64>>,
}

impl SkipMemory {
    /// Anything unreadable remembers nothing.
    pub fn from_json(json: &str) -> Self {
        serde_json::from_str(json).unwrap_or_default()
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    pub fn record(&mut self, identity: &str, at_ms: i64) {
        let dates = self.skips.entry(identity.to_owned()).or_default();
        dates.retain(|&d| at_ms - d < SKIP_MEMORY_MS);
        dates.push(at_ms);
        self.trim(at_ms);
    }

    /// The song's skips in the last [`SKIP_MEMORY_MS`].
    pub fn recent(&self, identity: &str, now_ms: i64) -> usize {
        self.skips.get(identity).map_or(0, |dates| {
            dates
                .iter()
                .filter(|&&d| now_ms - d < SKIP_MEMORY_MS)
                .count()
        })
    }

    /// Skipped often enough lately to be left out.
    pub fn drops(&self, identity: &str, now_ms: i64) -> bool {
        self.recent(identity, now_ms) >= SKIPS_TO_DROP
    }

    fn trim(&mut self, now_ms: i64) {
        self.skips.retain(|_, dates| {
            dates.retain(|&d| now_ms - d < SKIP_MEMORY_MS);
            !dates.is_empty()
        });
        if self.skips.len() <= SONG_LIMIT {
            return;
        }
        let mut newest: Vec<(String, Vec<i64>)> = self.skips.drain().collect();
        newest.sort_by(|a, b| {
            let last = |d: &Vec<i64>| d.last().copied().unwrap_or(i64::MIN);
            last(&b.1).cmp(&last(&a.1)).then_with(|| a.0.cmp(&b.0))
        });
        newest.truncate(SONG_LIMIT);
        self.skips = newest.into_iter().collect();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skips_are_early_leaves_of_known_length() {
        assert!(is_skip(10_000, Some(200_000)));
        assert!(!is_skip(40_000, Some(200_000)));
        // A third of a short song.
        assert!(!is_skip(25_000, Some(60_000)));
        assert!(!is_skip(10_000, None));
        assert!(!is_skip(59_500, Some(60_000)));
    }

    #[test]
    fn two_recent_skips_drop_a_song_and_old_ones_are_forgotten() {
        let mut memory = SkipMemory::default();
        memory.record("a", 0);
        memory.record("a", 1_000);
        assert!(memory.drops("a", 2_000));
        assert!(!memory.drops("a", SKIP_MEMORY_MS + 500));
        let back = SkipMemory::from_json(&memory.to_json());
        assert_eq!(back, memory);
    }

    #[test]
    fn keeps_the_most_recently_skipped_songs() {
        let mut memory = SkipMemory::default();
        for i in 0..(SONG_LIMIT as i64 + 5) {
            memory.record(&format!("s{i}"), i);
        }
        assert_eq!(memory.skips.len(), SONG_LIMIT);
        assert_eq!(memory.recent("s0", 10_000), 0);
        assert_eq!(memory.recent(&format!("s{}", SONG_LIMIT + 4), 10_000), 1);
    }
}
