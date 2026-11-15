//! Mixes built from the listening history, the shuffle that keeps artists apart, Tracks Radio,
//! and what a music server finds for you.
//!
//! A port of the Swift app's `MixBuilder`, `FreshShuffle` and `LiveMix` (Tracks Radio over Your
//! Music; Apple Music's radio stays in Swift), so every platform offers the same mixes and the
//! same Your Music radio from the same history. Pure and synchronous: the
//! host hands in plays (usually straight from the index) and the current time, and gets mixes
//! back.
//!
//! Orders are seeded and stable for a day, but they are not bit-for-bit the Swift app's:
//! Swift's `shuffled(using:)` draws differently from the generator here.

mod builder;
pub mod radio;
mod random;
pub mod server;
mod shuffle;

pub use builder::{DayPart, Mix, MixKind, MixOutput, MixPlay, MixSong, build_mixes};
pub use random::SeededRandom;
pub use shuffle::{daily_seed, fresh_order};
