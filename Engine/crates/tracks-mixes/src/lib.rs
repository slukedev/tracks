//! Mixes built from the listening history, the shuffle that keeps artists apart, and the
//! picker behind Tracks Radio.
//!
//! A port of the Swift app's `MixBuilder`, `FreshShuffle` and the core of `LiveMix`, so every
//! platform offers the same mixes from the same history. Pure and synchronous: the host hands
//! in plays (usually straight from the index) and the current time, and gets mixes back.
//!
//! Orders are seeded and stable for a day, but they are not bit-for-bit the Swift app's:
//! Swift's `shuffled(using:)` draws differently from the generator here.

mod builder;
mod radio;
mod random;
mod shuffle;

pub use builder::{DayPart, Mix, MixKind, MixOutput, MixPlay, MixSong, build_mixes};
pub use radio::{Listen, RadioCandidate, RadioPicker};
pub use random::SeededRandom;
pub use shuffle::{daily_seed, fresh_order};
