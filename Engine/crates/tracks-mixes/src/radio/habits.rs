//! What has landed on Tracks Radio before, so the next listen starts from it. A port of the
//! Swift app's `RadioHabits` and `LandingOdds`.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::random::SeededRandom;

/// When a listen was: a part of the day, a drive, or anytime for a radio that follows neither.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Band {
    Morning,
    Afternoon,
    Evening,
    Night,
    Drive,
    Anytime,
}

impl Band {
    fn key(self) -> &'static str {
        match self {
            Self::Morning => "morning",
            Self::Afternoon => "afternoon",
            Self::Evening => "evening",
            Self::Night => "night",
            Self::Drive => "drive",
            Self::Anytime => "anytime",
        }
    }
}

/// Songs let play and songs that didn't land, faded with time, so not whole numbers.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct Tally {
    pub landed: f64,
    pub missed: f64,
}

impl Tally {
    pub fn total(self) -> f64 {
        self.landed + self.missed
    }

    /// Kept to [`MOST_HEARD`] at most, in proportion, so a lot of old listening can still be
    /// outweighed by a few new listens.
    fn add(&mut self, landed: f64, missed: f64) {
        self.landed += landed;
        self.missed += missed;
        let total = self.total();
        if total > MOST_HEARD {
            let scale = MOST_HEARD / total;
            self.landed *= scale;
            self.missed *= scale;
        }
    }

    fn scaled(self, factor: f64) -> Self {
        Self {
            landed: self.landed * factor,
            missed: self.missed * factor,
        }
    }

    fn plus(self, other: Self) -> Self {
        Self {
            landed: self.landed + other.landed,
            missed: self.missed + other.missed,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
struct BandHabits {
    genres: BTreeMap<String, Tally>,
    /// By the decade's first year, as text so it stores as an object.
    decades: BTreeMap<String, Tally>,
    #[serde(rename = "newFinds")]
    new_finds: Tally,
}

impl BandHabits {
    fn total(&self) -> f64 {
        self.genres.values().map(|t| t.total()).sum()
    }
}

/// How long it takes what a listen taught to count half as much.
pub const HALF_LIFE_MS: f64 = 45.0 * 86_400_000.0;
/// The most songs a tally counts, so no habit becomes certain.
const MOST_HEARD: f64 = 20.0;
/// How much the other bands count toward one with little to go by.
const ELSEWHERE: f64 = 0.25;
const FORGOTTEN: f64 = 0.05;

/// For each band, how often songs in each genre family, from each decade, and new finds were
/// let play or skipped, and how readily this person skips at all.
///
/// Only genre families, decades and counts: never a song or an artist. Kept on the device.
/// Old listens fade with [`HALF_LIFE_MS`].
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct RadioHabits {
    bands: BTreeMap<String, BandHabits>,
    /// Every song heard on Tracks Radio, let play or not.
    listening: Option<Tally>,
    /// When it was last brought up to date, for fading it.
    #[serde(rename = "updatedMs")]
    updated_ms: Option<i64>,
}

impl RadioHabits {
    pub fn is_empty(&self) -> bool {
        self.bands.is_empty()
    }

    /// Anything unreadable is a radio with nothing learned yet.
    pub fn from_json(json: &str) -> Self {
        serde_json::from_str(json).unwrap_or_default()
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    pub(crate) fn listening(&self) -> Tally {
        self.listening.unwrap_or_default()
    }

    /// A genre family's tally in a band, with the other bands' counted for a little.
    pub fn tally_genre(&self, genre: &str, band: Band) -> Tally {
        self.pooled(band, |h| h.genres.get(genre).copied())
    }

    pub(crate) fn tally_decade(&self, decade: i32, band: Band) -> Tally {
        let key = decade.to_string();
        self.pooled(band, |h| h.decades.get(&key).copied())
    }

    pub(crate) fn tally_new_finds(&self, band: Band) -> Tally {
        self.pooled(band, |h| Some(h.new_finds))
    }

    /// Everything heard in the band itself.
    pub(crate) fn heard(&self, band: Band) -> f64 {
        self.bands.get(band.key()).map_or(0.0, BandHabits::total)
    }

    fn pooled(&self, band: Band, tally: impl Fn(&BandHabits) -> Option<Tally>) -> Tally {
        let mut sum = Tally::default();
        for (key, habits) in &self.bands {
            if let Some(found) = tally(habits) {
                sum = sum.plus(found.scaled(if key == band.key() { 1.0 } else { ELSEWHERE }));
            }
        }
        sum
    }

    pub(crate) fn record(
        &mut self,
        landed: f64,
        missed: f64,
        genre: Option<&str>,
        decade: Option<i32>,
        is_new: bool,
        band: Band,
    ) {
        let habits = self.bands.entry(band.key().to_owned()).or_default();
        if let Some(genre) = genre {
            habits
                .genres
                .entry(genre.to_owned())
                .or_default()
                .add(landed, missed);
        }
        if let Some(decade) = decade {
            habits
                .decades
                .entry(decade.to_string())
                .or_default()
                .add(landed, missed);
        }
        if is_new {
            habits.new_finds.add(landed, missed);
        }
    }

    pub(crate) fn record_listen(&mut self, landed: bool) {
        let tally = self.listening.get_or_insert_with(Tally::default);
        tally.add(
            if landed { 1.0 } else { 0.0 },
            if landed { 0.0 } else { 1.0 },
        );
    }

    /// These habits with what another listen added, capped as each tally is.
    pub(crate) fn adding(&self, other: &Self) -> Self {
        let mut sum = self.clone();
        if let Some(theirs) = other.listening {
            sum.listening
                .get_or_insert_with(Tally::default)
                .add(theirs.landed, theirs.missed);
        }
        for (key, theirs) in &other.bands {
            let habits = sum.bands.entry(key.clone()).or_default();
            for (genre, tally) in &theirs.genres {
                habits
                    .genres
                    .entry(genre.clone())
                    .or_default()
                    .add(tally.landed, tally.missed);
            }
            for (decade, tally) in &theirs.decades {
                habits
                    .decades
                    .entry(decade.clone())
                    .or_default()
                    .add(tally.landed, tally.missed);
            }
            habits
                .new_finds
                .add(theirs.new_finds.landed, theirs.new_finds.missed);
        }
        sum
    }

    /// Faded to `now_ms`, halving every [`HALF_LIFE_MS`] since it was last brought up to date,
    /// and dated `now_ms`. Tallies faded to almost nothing are let go.
    #[must_use]
    pub fn decayed(&self, now_ms: i64) -> Self {
        let mut faded = self.clone();
        faded.updated_ms = Some(now_ms);
        let Some(updated) = self.updated_ms.filter(|&u| now_ms > u) else {
            return faded;
        };
        let factor = 0.5_f64.powf((now_ms - updated) as f64 / HALF_LIFE_MS);
        faded.listening = self.listening.map(|t| t.scaled(factor));
        faded.bands = self
            .bands
            .iter()
            .filter_map(|(key, habits)| {
                let fade = |tallies: &BTreeMap<String, Tally>| {
                    tallies
                        .iter()
                        .map(|(k, t)| (k.clone(), t.scaled(factor)))
                        .filter(|(_, t)| t.total() >= FORGOTTEN)
                        .collect::<BTreeMap<_, _>>()
                };
                let habits = BandHabits {
                    genres: fade(&habits.genres),
                    decades: fade(&habits.decades),
                    new_finds: habits.new_finds.scaled(factor),
                };
                let gone = habits.genres.is_empty()
                    && habits.decades.is_empty()
                    && habits.new_finds.total() < FORGOTTEN;
                (!gone).then(|| (key.clone(), habits))
            })
            .collect();
        faded
    }
}

/// A Beta distribution: how likely a song in a direction is to land, as far as it's known.
#[derive(Debug, Clone, Copy)]
pub(crate) struct LandingOdds {
    alpha: f64,
    beta: f64,
}

impl LandingOdds {
    /// Before anything is heard: most songs you know get let play, so a little over half,
    /// and as sure as four songs would make it.
    pub(crate) const PRIOR: Self = Self {
        alpha: 2.4,
        beta: 1.6,
    };

    pub(crate) fn new(tally: Tally) -> Self {
        Self {
            alpha: Self::PRIOR.alpha + tally.landed,
            beta: Self::PRIOR.beta + tally.missed,
        }
    }

    pub(crate) fn mean(self) -> f64 {
        self.alpha / (self.alpha + self.beta)
    }

    pub(crate) fn spread(self) -> f64 {
        let total = self.alpha + self.beta;
        (self.alpha * self.beta / (total * total * (total + 1.0))).sqrt()
    }

    pub(crate) fn draw(self, random: &mut SeededRandom) -> f64 {
        let x = gamma(self.alpha, random);
        let y = gamma(self.beta, random);
        if x + y > 0.0 {
            x / (x + y)
        } else {
            self.mean()
        }
    }
}

/// Uniform in `(0, 1)`, never zero, for logarithms.
fn open_unit(random: &mut SeededRandom) -> f64 {
    loop {
        let u = random.unit();
        if u > 0.0 {
            return u;
        }
    }
}

/// Marsaglia and Tsang's method.
#[expect(clippy::many_single_char_names, reason = "the method's own notation")]
fn gamma(shape: f64, random: &mut SeededRandom) -> f64 {
    if shape < 1.0 {
        let u = open_unit(random);
        return gamma(shape + 1.0, random) * u.powf(1.0 / shape);
    }
    let d = shape - 1.0 / 3.0;
    let c = 1.0 / (9.0 * d).sqrt();
    loop {
        let (x, v) = loop {
            let x = normal(random);
            let v = 1.0 + c * x;
            if v > 0.0 {
                break (x, v);
            }
        };
        let v = v * v * v;
        let u = open_unit(random);
        if u.ln() < 0.5 * x * x + d - d * v + d * v.ln() {
            return d * v;
        }
    }
}

fn normal(random: &mut SeededRandom) -> f64 {
    let u1 = open_unit(random);
    let u2 = random.unit();
    (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tallies_cap_and_fade() {
        let mut habits = RadioHabits::default();
        for _ in 0..40 {
            habits.record(1.0, 0.0, Some("rock"), Some(1990), false, Band::Anytime);
        }
        let rock = habits.tally_genre("rock", Band::Anytime);
        assert!((rock.total() - MOST_HEARD).abs() < 1e-9);

        let dated = habits.decayed(0);
        let later = dated.decayed(HALF_LIFE_MS as i64);
        let faded = later.tally_genre("rock", Band::Anytime);
        assert!((faded.landed - MOST_HEARD / 2.0).abs() < 1e-6);
    }

    #[test]
    fn other_bands_count_a_little() {
        let mut habits = RadioHabits::default();
        habits.record(4.0, 0.0, Some("jazz"), None, false, Band::Evening);
        let morning = habits.tally_genre("jazz", Band::Morning);
        assert!((morning.landed - 1.0).abs() < 1e-9);
    }

    #[test]
    fn round_trips_through_json() {
        let mut habits = RadioHabits::default();
        habits.record(1.0, 0.5, Some("pop"), Some(2010), true, Band::Anytime);
        habits.record_listen(true);
        let back = RadioHabits::from_json(&habits.to_json());
        assert_eq!(back, habits);
        assert!(RadioHabits::from_json("not json").is_empty());
    }

    #[test]
    fn draws_center_on_the_mean() {
        let mut random = SeededRandom::new(3);
        let odds = LandingOdds::new(Tally {
            landed: 15.0,
            missed: 3.0,
        });
        let mean = (0..4000).map(|_| odds.draw(&mut random)).sum::<f64>() / 4000.0;
        assert!(
            (mean - odds.mean()).abs() < 0.02,
            "{mean} against {}",
            odds.mean()
        );
    }
}
