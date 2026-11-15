//! How Tracks Radio opens a listen started from nothing in particular: a few songs, each from
//! a different genre family you listen in, to hear which one you're in the mood for. A port of
//! the Swift app's `RadioProbe`.

use rustc_hash::{FxHashMap, FxHashSet};

#[derive(Debug, Clone)]
pub(crate) struct Slot {
    pub identity: String,
    pub family: Option<String>,
    /// None until it has played or given way.
    pub landed: Option<bool>,
}

#[derive(Debug, Clone)]
pub(crate) struct Probe {
    /// Songs it means to probe with. Grows by one for each try after a probe that's all skips.
    pub planned: usize,
    /// The slots, by position, that go to new finds.
    pub new_slots: FxHashSet<usize>,
    pub slots: Vec<Slot>,
    pub is_open: bool,
    /// Songs added after a probe that's all skips.
    pub extended: usize,
    pub decades: FxHashSet<i32>,
}

/// Songs in the probe, at most.
const LENGTH: usize = 5;
/// More tries, at most, when nothing in the probe lands.
pub(crate) const MOST_EXTRA: usize = 4;
/// A genre family needs this share of the radio to be a direction worth trying.
pub(crate) const LEAST_SHARE: f64 = 0.03;
/// A family that landed less often than this in the band isn't tried in the probe, only later
/// if nothing else lands.
pub(crate) const LEAST_LANDED: f64 = 0.4;

impl Probe {
    pub fn families(&self) -> FxHashSet<&str> {
        self.slots
            .iter()
            .filter_map(|s| s.family.as_deref())
            .collect()
    }

    /// The plan for a radio of these families, as shares of its songs.
    ///
    /// `heard` is the songs heard in this band before: the more, the shorter the probe. None
    /// when there's too little to choose between: a small library, or one whose songs are
    /// nearly all one genre.
    pub fn plan(
        shares: &FxHashMap<String, f64>,
        your_songs: usize,
        heard: f64,
        new_share: f64,
        has_new_finds: bool,
    ) -> Option<Self> {
        if your_songs < 20 {
            return None;
        }
        let eligible: Vec<f64> = shares
            .values()
            .copied()
            .filter(|&s| s >= LEAST_SHARE)
            .collect();
        // The number of genres the radio really plays: two even ones count as two, one at nine
        // tenths and a scattering of others as barely more than one.
        let effective = 1.0 / eligible.iter().map(|s| s * s).sum::<f64>().max(0.0001);
        let mut planned = LENGTH
            .min(eligible.len())
            .min(effective.ceil() as usize + 1);
        let sure = (heard / 40.0).min(1.0);
        planned = (planned as f64 * (1.0 - 0.5 * sure)).round() as usize;
        if planned < 2 {
            return None;
        }
        let new_count = if has_new_finds {
            (planned as f64 * new_share).round() as usize
        } else {
            0
        };
        let new_slots = [2, 4, 3, 1]
            .into_iter()
            .filter(|&slot| slot < planned)
            .take(new_count)
            .collect();
        Some(Self {
            planned,
            new_slots,
            slots: Vec::new(),
            is_open: true,
            extended: 0,
            decades: FxHashSet::default(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shares(values: &[(&str, f64)]) -> FxHashMap<String, f64> {
        values.iter().map(|(k, v)| ((*k).to_owned(), *v)).collect()
    }

    #[test]
    fn small_or_single_genre_libraries_skip_the_probe() {
        let even = shares(&[("rock", 0.5), ("jazz", 0.5)]);
        assert!(Probe::plan(&even, 10, 0.0, 0.2, true).is_none());
        let one = shares(&[("rock", 0.98), ("jazz", 0.02)]);
        assert!(Probe::plan(&one, 100, 0.0, 0.2, true).is_none());
    }

    #[test]
    fn many_genres_probe_longer_until_the_band_is_known() {
        let many = shares(&[("a", 0.2), ("b", 0.2), ("c", 0.2), ("d", 0.2), ("e", 0.2)]);
        let fresh = Probe::plan(&many, 100, 0.0, 0.25, true).expect("a probe");
        assert_eq!(fresh.planned, 5);
        assert_eq!(fresh.new_slots.len(), 1);
        let known = Probe::plan(&many, 100, 80.0, 0.25, false).expect("a probe");
        assert_eq!(known.planned, 3);
        assert!(known.new_slots.is_empty());
    }
}
