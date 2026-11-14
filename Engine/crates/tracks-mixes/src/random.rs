//! A small seeded generator: `SplitMix64`, stable across platforms and launches.

/// Deterministic pseudo-random numbers from a 64-bit seed.
#[derive(Debug, Clone)]
pub struct SeededRandom {
    state: u64,
}

impl SeededRandom {
    pub fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Uniform in `0..bound`; `bound` must be positive.
    pub fn below(&mut self, bound: usize) -> usize {
        // Lemire's multiply-shift, with rejection for exact uniformity.
        let bound = bound as u64;
        loop {
            let wide = u128::from(self.next_u64()) * u128::from(bound);
            let low = wide as u64;
            if low >= bound.wrapping_neg() % bound {
                return (wide >> 64) as usize;
            }
        }
    }

    /// Uniform in `[0, 1)`.
    pub fn unit(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }

    /// Fisher-Yates.
    pub fn shuffle<T>(&mut self, items: &mut [T]) {
        for i in (1..items.len()).rev() {
            let j = self.below(i + 1);
            items.swap(i, j);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_seed_same_numbers() {
        let mut a = SeededRandom::new(7);
        let mut b = SeededRandom::new(7);
        for _ in 0..100 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
    }

    #[test]
    fn below_stays_in_range() {
        let mut r = SeededRandom::new(1);
        for bound in 1..50 {
            for _ in 0..50 {
                assert!(r.below(bound) < bound);
            }
        }
    }
}
