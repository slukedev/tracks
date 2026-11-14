//! A shuffle that sounds shuffled: each artist spread out as far as the songs allow, and
//! anything just heard moved to the end.

use rustc_hash::FxHashMap;

use crate::random::SeededRandom;

/// Orders `items` so the same artist rarely plays twice in a row.
///
/// `artist` gives the key to spread apart (usually the folded artist name) and
/// `recently_heard` marks items that go last, still spread out. The same seed gives the same
/// order.
pub fn fresh_order<T: Clone>(
    items: &[T],
    artist: impl Fn(&T) -> String,
    recently_heard: impl Fn(&T) -> bool,
    seed: u64,
) -> Vec<T> {
    let mut random = SeededRandom::new(seed);
    let mut shuffled: Vec<&T> = items.iter().collect();
    random.shuffle(&mut shuffled);
    let mut fresh = Vec::new();
    let mut stale = Vec::new();
    for item in shuffled {
        let keyed = (item, artist(item));
        if recently_heard(item) {
            stale.push(keyed);
        } else {
            fresh.push(keyed);
        }
    }
    let fresh_gap = gap(&fresh);
    let fresh_order = spread(fresh, &[]);
    let tail: Vec<String> = fresh_order
        .iter()
        .rev()
        .take(fresh_gap)
        .rev()
        .map(|(_, key)| key.clone())
        .collect();
    let stale_order = spread(stale, &tail);
    fresh_order
        .into_iter()
        .chain(stale_order)
        .map(|(item, _)| item.clone())
        .collect()
}

fn counts<T>(items: &[(T, String)]) -> FxHashMap<&str, usize> {
    let mut counts: FxHashMap<&str, usize> = FxHashMap::default();
    for (_, key) in items {
        *counts.entry(key.as_str()).or_default() += 1;
    }
    counts
}

/// How many places apart an artist is kept: two, or fewer when there aren't enough artists.
fn gap<T>(items: &[(T, String)]) -> usize {
    let counts = counts(items);
    let Some(&most) = counts.values().max() else {
        return 0;
    };
    if counts.len() <= 1 {
        return 0;
    }
    let feasible = if most > 1 {
        (items.len() - most) / (most - 1)
    } else {
        2
    };
    2.min(counts.len() - 1).min(feasible)
}

/// Greedy: each place takes the first item whose artist isn't among the last few placed,
/// unless an artist with too many left has to go now to avoid a clump at the end.
fn spread<T>(items: Vec<(T, String)>, previous: &[String]) -> Vec<(T, String)> {
    let window = gap(&items);
    let mut left: FxHashMap<String, usize> = FxHashMap::default();
    for (_, key) in &items {
        *left.entry(key.clone()).or_default() += 1;
    }
    let mut remaining = items;
    let mut placed = Vec::with_capacity(remaining.len());
    let mut recent: Vec<String> = previous
        .iter()
        .rev()
        .take(window)
        .rev()
        .cloned()
        .collect();
    while !remaining.is_empty() {
        let most_left = left.values().copied().max().unwrap_or(0);
        let may_crowd = most_left * (window + 1) > remaining.len();
        let mut pick: Option<usize> = None;
        let mut crowded: Option<(usize, usize)> = None;
        for (index, (_, key)) in remaining.iter().enumerate() {
            if recent.contains(key) {
                continue;
            }
            if pick.is_none() {
                pick = Some(index);
                if !may_crowd {
                    break;
                }
            }
            let count = left.get(key).copied().unwrap_or(0);
            if count > crowded.map_or(0, |(_, c)| c) {
                crowded = Some((index, count));
            }
        }
        if let Some((index, count)) = crowded
            && count * (window + 1) > remaining.len()
        {
            pick = Some(index);
        }
        let item = remaining.remove(pick.unwrap_or(0));
        if let Some(count) = left.get_mut(&item.1) {
            *count = count.saturating_sub(1);
        }
        if window > 0 {
            recent.push(item.1.clone());
            if recent.len() > window {
                recent.remove(0);
            }
        }
        placed.push(item);
    }
    placed
}

/// A seed that holds all day, so a mix keeps its order until tomorrow. FNV-1a over the local
/// date and a salt, as the Swift app makes it.
pub fn daily_seed(year: i16, month: i8, day: i8, salt: &str) -> u64 {
    let text = format!("{year}-{month}-{day}|{salt}");
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01B3);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runs(order: &[(u32, &str)]) -> usize {
        order.windows(2).filter(|w| w[0].1 == w[1].1).count()
    }

    #[test]
    fn keeps_artists_apart_when_possible() {
        let items: Vec<(u32, &str)> = (0..30)
            .map(|i| (i, ["a", "b", "c", "d", "e"][i as usize % 5]))
            .collect();
        for seed in 0..20 {
            let order = fresh_order(&items, |i| i.1.to_owned(), |_| false, seed);
            assert_eq!(order.len(), items.len());
            assert_eq!(runs(&order), 0, "seed {seed}");
        }
    }

    #[test]
    fn one_artist_crowding_still_places_everything() {
        let mut items: Vec<(u32, &str)> = (0..10).map(|i| (i, "a")).collect();
        items.extend((10..14).map(|i| (i, "b")));
        let order = fresh_order(&items, |i| i.1.to_owned(), |_| false, 3);
        assert_eq!(order.len(), 14);
    }

    #[test]
    fn recently_heard_go_last() {
        let items: Vec<(u32, &str)> = (0..12).map(|i| (i, ["a", "b", "c"][i as usize % 3])).collect();
        let order = fresh_order(&items, |i| i.1.to_owned(), |i| i.0 < 3, 9);
        assert!(order[9..].iter().all(|i| i.0 < 3));
    }

    #[test]
    fn daily_seed_is_stable() {
        assert_eq!(daily_seed(2026, 11, 14, "onRepeat"), daily_seed(2026, 11, 14, "onRepeat"));
        assert_ne!(daily_seed(2026, 11, 14, "onRepeat"), daily_seed(2026, 11, 15, "onRepeat"));
    }
}
