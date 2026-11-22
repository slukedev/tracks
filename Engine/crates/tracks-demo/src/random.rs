//! The draws the generator needs, on top of the shared `SplitMix64`.

use tracks_mixes::SeededRandom;

/// `SplitMix64` (the Swift app's `SeededGenerator`) with the handful of draws the sample
/// history makes. The raw numbers match Swift's for the same seed; how they become ranges,
/// coin flips and shuffles does not, so the histories differ in detail between platforms.
#[derive(Debug, Clone)]
pub(crate) struct Draw(SeededRandom);

impl Draw {
    pub(crate) fn new(seed: u64) -> Self {
        Self(SeededRandom::new(seed))
    }

    /// Uniform in `[0, 1)`.
    pub(crate) fn unit(&mut self) -> f64 {
        self.0.unit()
    }

    /// Uniform in `[low, high)`.
    pub(crate) fn range(&mut self, low: f64, high: f64) -> f64 {
        low + self.unit() * (high - low)
    }

    /// Uniform in `low..=high`; `low` when the range is empty.
    pub(crate) fn int(&mut self, low: usize, high: usize) -> usize {
        match high.checked_sub(low) {
            Some(span) => low + self.0.below(span.saturating_add(1)),
            None => low,
        }
    }

    pub(crate) fn coin(&mut self) -> bool {
        self.0.next_u64() >> 63 == 1
    }

    pub(crate) fn pick<'a, T>(&mut self, items: &'a [T]) -> Option<&'a T> {
        if items.is_empty() {
            None
        } else {
            items.get(self.0.below(items.len()))
        }
    }

    pub(crate) fn shuffle<T>(&mut self, items: &mut [T]) {
        self.0.shuffle(items);
    }
}

#[cfg(test)]
mod tests {
    use super::Draw;

    #[test]
    fn ints_stay_inside_their_range_even_at_the_edges() {
        let mut draw = Draw::new(9);
        for _ in 0..500 {
            assert!((4..=7).contains(&draw.int(4, 7)));
            assert_eq!(draw.int(3, 3), 3);
            assert!(draw.int(usize::MAX - 1, usize::MAX) >= usize::MAX - 1);
        }
    }
}
