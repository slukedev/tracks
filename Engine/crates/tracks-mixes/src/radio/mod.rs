//! Tracks Radio over Your Music (files on the device and Subsonic or Octo servers): an endless
//! station picked a song at a time from what you love and new finds like it, steered by what
//! you skip, let play and love. A port of the Swift app's `LiveMix` and its Your Music builder
//! (`LiveMix.tracksRadio(from tracks:…)`), so Android's radio decides as the iPhone's Your Music
//! radio does, and iOS and the Mac can adopt it for Your Music stations later. Tracks Radio over
//! Apple Music stays in Swift, with the MusicKit catalog; nothing here is Apple Music-specific.
//! Sans-IO: the host hands in its library, the history's plays, what it keeps between listens
//! (habits and skips, as JSON) and the time, and plays what comes back.

mod build;
mod habits;
mod mix;
mod probe;
mod signals;
mod taste;
mod tuning;

#[cfg(test)]
mod tests;

pub use build::{RadioPlay, RadioSignals, RadioTrack, tracks_radio};
pub use habits::{Band, RadioHabits, Tally};
pub use mix::{Candidate, Listen, LiveMix, REST_PERIOD_MS, Reason, SEED_LEAN, Steering, genre_key};
pub use signals::{SKIP_MEMORY_MS, SkipMemory, is_skip};
pub use taste::{Neighbor, TasteGraph, TastePlay};
pub use tuning::{Discovery, RadioTuning, genre_choices};
