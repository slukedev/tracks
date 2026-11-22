//! A mix that picks each song as the one before it starts, so what you skip and what you let
//! play steer what comes next. A port of the Swift app's `LiveMix`, which plays Tracks Radio.
//!
//! Songs you know and new finds are drawn from separately, the new ones for a share of the
//! picks. Skipping an artist makes them rarer for the rest of the listen, and letting one play
//! makes them a little likelier; skipping new finds makes them rarer, and keeping them makes
//! them more common. No song comes up twice until every other one has, and an artist doesn't
//! follow themselves when anyone else could.
//!
//! It listens for runs of skips: two in a row that share a genre or a decade, or that are both
//! new finds, steer away from what they share for the next few picks; a run with nothing in
//! common tries other genres, and the first song let play after that is what it homes in on.
//! Each pick leans toward the genre of the song before it, and, through the [`TasteGraph`],
//! toward the songs you play with what just landed.
//!
//! Started from nothing in particular, it opens with a probe: a few songs from different
//! directions, to hear what you're in the mood for, and remembers what landed for next time
//! ([`RadioHabits`]).

use rustc_hash::{FxHashMap, FxHashSet};
use tracks_model::folded;

use super::habits::{Band, LandingOdds, RadioHabits};
use super::probe::{self, Probe, Slot};
use super::signals::is_skip;
use super::taste::TasteGraph;
use crate::random::SeededRandom;

/// Why a song came up, as far as one thing stands out. Only what the mix really weighed: a
/// reason is never made up for a song that came up by chance.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reason {
    /// A song from outside your history.
    NewFind,
    /// A song from outside your history, by an artist you play.
    NewFromYourArtist,
    /// A song from outside your history, by an artist like this one of yours.
    NewFindLike(String),
    /// One played on drives, while the radio plays for the road.
    OnTheRoad,
    /// One played near this hour far more than at others.
    AroundNow,
    /// In a genre the radio is tuned to lean into, as the tuning spells it.
    LeaningInto(String),
    /// Brought back after months unheard: last played then.
    OldFavorite { last_heard_ms: i64 },
    /// Among the songs you play most.
    MostPlayed,
    /// After a run of skips, the genre of the song let play.
    MoreOf(String),
    /// The artist of a song loved or asked for more of this listen.
    MoreLike(String),
    /// One of the first few songs, each from a different direction.
    Probing,
    /// One you play with a song that landed this listen: its title.
    GoesWith(String),
}

/// What the mix is doing about what it's heard, for the player to say.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Steering {
    /// Two or more skips in a row in this genre: it plays others for a while.
    AwayFromGenre(String),
    /// Two or more skips in a row from this decade, as a year like 1980.
    AwayFromDecade(i32),
    /// Two or more new finds skipped in a row: more songs you know for a while.
    FewerNewFinds,
    /// Two or more of your songs skipped in a row: more new finds for a while.
    MoreNewFinds,
    /// Skips with nothing in common: trying genres it hasn't played lately.
    TryingSomethingElse,
    /// A song let play, or loved, after a run of skips: more like it.
    TowardGenre(String),
    TowardArtist(String),
    /// The opening probe is over, and this genre landed best.
    SettledOn(String),
}

impl Steering {
    /// A turn away after a run of skips, which the player says at once.
    pub fn is_turning_away(&self) -> bool {
        matches!(
            self,
            Self::AwayFromGenre(_)
                | Self::AwayFromDecade(_)
                | Self::FewerNewFinds
                | Self::MoreNewFinds
                | Self::TryingSomethingElse
        )
    }
}

/// How a song went, told by when it gave way.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Listen {
    /// Left in its first moments. See [`is_skip`].
    Skipped,
    /// Heard for a while, then left well before the end.
    MovedOn,
    /// Played through, or near enough.
    Finished,
}

impl Listen {
    /// Past the skip window, a song counts as let play once three fifths of it have played or
    /// it's within its last 45 seconds. A song whose length isn't known counts as let play.
    pub fn from_played(played_ms: i64, duration_ms: Option<i64>) -> Self {
        if is_skip(played_ms, duration_ms) {
            return Self::Skipped;
        }
        let Some(duration) = duration_ms.filter(|&d| d > 0) else {
            return Self::Finished;
        };
        let near_end = duration - played_ms <= 45_000;
        if near_end || played_ms as f64 >= duration as f64 * 0.6 {
            Self::Finished
        } else {
            Self::MovedOn
        }
    }
}

/// A song the mix may pick.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub identity: String,
    pub title: String,
    pub artist_name: String,
    /// How likely it is against the others of its kind.
    pub weight: f64,
    /// A song that isn't in the history.
    pub is_new: bool,
    /// Heard in the last few hours. Comes up only once the rested songs have all been picked.
    pub is_resting: bool,
    pub genre: Option<String>,
    pub release_year: Option<i32>,
    /// Why it would come up. New finds say so when nothing else does.
    pub reason: Option<Reason>,
    artist_key: String,
    genre_key: Option<String>,
}

impl Candidate {
    #[expect(
        clippy::too_many_arguments,
        reason = "a candidate is a record of its fields"
    )]
    pub fn new(
        identity: String,
        title: String,
        artist_name: String,
        weight: f64,
        is_new: bool,
        is_resting: bool,
        genre: Option<String>,
        release_year: Option<i32>,
        reason: Option<Reason>,
    ) -> Self {
        let artist_key = folded(&artist_name);
        let genre_key = genre.as_deref().and_then(genre_key);
        let reason = reason.or(if is_new { Some(Reason::NewFind) } else { None });
        Self {
            identity,
            title,
            artist_name,
            weight,
            is_new,
            is_resting,
            genre,
            release_year,
            reason,
            artist_key,
            genre_key,
        }
    }

    pub fn artist_key(&self) -> &str {
        &self.artist_key
    }

    fn decade(&self) -> Option<i32> {
        self.release_year.map(|y| y / 10 * 10)
    }
}

/// "Hip-Hop/Rap", "Hip-Hop" and "hip hop" as one family, "hip-hop".
pub fn genre_key(genre: &str) -> Option<String> {
    let family = genre.split('/').next().unwrap_or(genre);
    let folded = folded(family).replace(' ', "-");
    (!folded.is_empty() && folded != "music").then_some(folded)
}

#[derive(Debug, Clone, Copy)]
struct Outcome {
    skipped: bool,
    moved_on: bool,
    genre: Option<usize>,
    decade: Option<i32>,
    is_new: bool,
}

#[derive(Debug, Clone, Default)]
struct Avoidance {
    genres: FxHashSet<String>,
    decades: FxHashSet<i32>,
    picks_left: u32,
}

#[derive(Debug, Clone, Copy, Default)]
struct ListenTally {
    landed: f64,
    missed: f64,
}

#[derive(Debug, Clone, Default)]
struct Lifts {
    genres: FxHashMap<String, f64>,
    decades: FxHashMap<i32, f64>,
}

impl Lifts {
    fn weight(&self, candidate: &Candidate) -> f64 {
        let genre = candidate
            .genre_key
            .as_ref()
            .and_then(|g| self.genres.get(g))
            .copied()
            .unwrap_or(1.0);
        let decade = candidate
            .decade()
            .and_then(|d| self.decades.get(&d))
            .copied()
            .unwrap_or(1.0);
        genre * decade
    }
}

/// How long a song rests after being heard before it can come up again.
pub const REST_PERIOD_MS: i64 = 6 * 3_600_000;

const ARTIST_GAP: usize = 2;
const ARTIST_MEMORY: usize = 8;
const FLOW: f64 = 1.4;
const HOMING_IN: f64 = 1.3;
const NEW_SHARE_RANGE: (f64, f64) = (0.05, 0.6);
const RUN_LENGTH: usize = 2;
const AVOID_FOR: u32 = 5;
const LANDING_LIFT: f64 = 1.0;
const SKIP_DIM: f64 = 0.6;
const SECOND_STEP: f64 = 0.35;
const INTEREST_FADE: f64 = 0.85;
const MOST_LIFT: f64 = 1.5;
const MOST_DIM: f64 = -1.2;
const LEAD_LIFT: f64 = 1.5;
const GOES_WITH_LIFT: f64 = 0.55;
const USUAL_SKIP_RATE: f64 = 0.3;
const USUAL_WEIGHT: f64 = 8.0;
const FOLLOWS_FROM: f64 = 0.3;
const FOLLOWING_PER_LIFT: f64 = 0.45;
const MOST_FOLLOWING: f64 = 0.7;
/// How far each song a station follows on from leans its start.
pub const SEED_LEAN: f64 = 0.8;
const PROBE_COUNTS: f64 = 2.0;
const MOST_EXPLORING: f64 = 0.15;
const LEAST_EXPLORING: f64 = 0.05;

/// Picks Tracks Radio's songs one at a time, learning from each.
#[derive(Debug, Clone)]
pub struct LiveMix {
    candidates: Vec<Candidate>,
    by_identity: FxHashMap<String, usize>,
    new_share: f64,
    starting_share: f64,
    band: Band,
    picks: Vec<String>,
    picked: FxHashSet<String>,
    steering: Option<Steering>,
    pick_reasons: FxHashMap<String, Option<Reason>>,
    artist_bias: FxHashMap<String, f64>,
    genre_bias: FxHashMap<String, f64>,
    outcomes: Vec<Outcome>,
    /// Genre keys of the outcomes, so an outcome stays small.
    outcome_genres: Vec<String>,
    avoiding: Option<Avoidance>,
    loved_genre: Option<String>,
    habits_base: Option<RadioHabits>,
    learned: RadioHabits,
    probe: Option<Probe>,
    shares: FxHashMap<String, f64>,
    picks_since_probe: u32,
    remembers_artists: usize,
    random: SeededRandom,
    taste: TasteGraph,
    interest: FxHashMap<String, f64>,
    artist_interest: FxHashMap<String, f64>,
    lifted_by: FxHashMap<String, (String, f64)>,
    listen_tally: ListenTally,
}

impl LiveMix {
    pub fn new(candidates: Vec<Candidate>, new_share: f64, seed: u64) -> Self {
        // In a fixed order, so a seed always draws the same songs from the same candidates.
        let mut known = FxHashSet::default();
        let mut unique: Vec<Candidate> = candidates
            .into_iter()
            .filter(|c| c.weight > 0.0 && known.insert(c.identity.clone()))
            .collect();
        unique.sort_by(|a, b| a.identity.cmp(&b.identity));
        let by_identity = unique
            .iter()
            .enumerate()
            .map(|(i, c)| (c.identity.clone(), i))
            .collect();
        let artists: FxHashSet<&str> = unique.iter().map(|c| c.artist_key.as_str()).collect();
        let remembers_artists = ARTIST_MEMORY.min(artists.len() / 4);
        Self {
            candidates: unique,
            by_identity,
            new_share,
            starting_share: new_share,
            band: Band::Anytime,
            picks: Vec::new(),
            picked: FxHashSet::default(),
            steering: None,
            pick_reasons: FxHashMap::default(),
            artist_bias: FxHashMap::default(),
            genre_bias: FxHashMap::default(),
            outcomes: Vec::new(),
            outcome_genres: Vec::new(),
            avoiding: None,
            loved_genre: None,
            habits_base: None,
            learned: RadioHabits::default(),
            probe: None,
            shares: FxHashMap::default(),
            picks_since_probe: 0,
            remembers_artists,
            random: SeededRandom::new(seed),
            taste: TasteGraph::default(),
            interest: FxHashMap::default(),
            artist_interest: FxHashMap::default(),
            lifted_by: FxHashMap::default(),
            listen_tally: ListenTally::default(),
        }
    }

    /// The mix with what it needs to know which of its songs go together.
    #[must_use]
    pub fn with_taste(mut self, taste: TasteGraph) -> Self {
        self.taste = taste;
        self
    }

    /// The moment the mix plays for, which its habits are kept by. Anytime unless set.
    pub fn set_band(&mut self, band: Band) {
        self.band = band;
    }

    pub fn is_empty(&self) -> bool {
        self.candidates.is_empty()
    }

    pub fn candidates(&self) -> &[Candidate] {
        &self.candidates
    }

    pub fn candidate(&self, identity: &str) -> Option<&Candidate> {
        self.by_identity.get(identity).map(|&i| &self.candidates[i])
    }

    pub fn has_picked(&self, identity: &str) -> bool {
        self.picked.contains(identity)
    }

    /// Whether the song is one of the mix's own, so what's said about it can steer.
    pub fn knows(&self, identity: &str) -> bool {
        self.by_identity.contains_key(identity)
    }

    pub fn new_share(&self) -> f64 {
        self.new_share
    }

    /// What the mix last changed course for.
    pub fn steering(&self) -> Option<&Steering> {
        self.steering.as_ref()
    }

    /// Songs picked this round, in order.
    pub fn picks(&self) -> &[String] {
        &self.picks
    }

    fn share_range(&self) -> (f64, f64) {
        (
            NEW_SHARE_RANGE.0.min(self.starting_share),
            NEW_SHARE_RANGE.1.max(self.starting_share),
        )
    }

    fn clamp_share(&self, share: f64) -> f64 {
        let (low, high) = self.share_range();
        share.clamp(low, high)
    }

    // MARK: Picking

    /// The next song. None only when there are no candidates at all.
    #[expect(
        clippy::should_implement_trait,
        reason = "a station never runs out, so it isn't an iterator"
    )]
    pub fn next(&mut self) -> Option<String> {
        self.next_where(None, &|_| true)
    }

    /// The next song of those that pass `include`: `new_finds` true for a new find only, false
    /// for one of yours only, None for either as the share of new finds decides. None when no
    /// song that passes is left to pick.
    pub fn next_where(
        &mut self,
        new_finds: Option<bool>,
        include: &dyn Fn(&Candidate) -> bool,
    ) -> Option<String> {
        if self.candidates.is_empty() {
            return None;
        }
        if self.picked.len() >= self.candidates.len() {
            self.start_over();
        }
        // A new find got ready behind the radio's own songs isn't one of the probe's.
        if new_finds != Some(true) {
            let only_new = if new_finds == Some(false) {
                Some(false)
            } else {
                None
            };
            if let Some(index) = self.probe_pick(only_new, false, include) {
                return Some(self.candidates[index].identity.clone());
            }
        }

        let open: Vec<usize> = (0..self.candidates.len())
            .filter(|&i| {
                let c = &self.candidates[i];
                !self.picked.contains(&c.identity) && include(c)
            })
            .collect();
        let yours: Vec<usize> = if new_finds == Some(true) {
            Vec::new()
        } else {
            open.iter()
                .copied()
                .filter(|&i| !self.candidates[i].is_new)
                .collect()
        };
        let new: Vec<usize> = if new_finds == Some(false) {
            Vec::new()
        } else {
            open.iter()
                .copied()
                .filter(|&i| self.candidates[i].is_new)
                .collect()
        };
        let pool = if yours.is_empty() || new.is_empty() {
            if yours.is_empty() { new } else { yours }
        } else if self.random.unit() < self.new_share {
            new
        } else {
            yours
        };
        if pool.is_empty() {
            return None;
        }

        let rested: Vec<usize> = pool
            .iter()
            .copied()
            .filter(|&i| !self.candidates[i].is_resting)
            .collect();
        let fresh = if rested.is_empty() { pool } else { rested };
        let recent: FxHashSet<&str> = self
            .picks
            .iter()
            .rev()
            .take(ARTIST_GAP)
            .filter_map(|id| self.by_identity.get(id))
            .map(|&i| self.candidates[i].artist_key.as_str())
            .collect();
        let spaced: Vec<usize> = fresh
            .iter()
            .copied()
            .filter(|&i| !recent.contains(self.candidates[i].artist_key.as_str()))
            .collect();
        let cleared = self.clear_of_avoided(if spaced.is_empty() { fresh } else { spaced });
        let choices = self.following_what_landed(cleared);

        let choice = self.draw(&choices, 1.0, true)?;
        let reason = self.reason_choosing(choice);
        self.settle_pick(choice, reason);
        if self.habits_base.is_some() {
            self.picks_since_probe += 1;
        }
        Some(self.candidates[choice].identity.clone())
    }

    /// The song a listen opens on: one of yours that passes `include`, leaning harder toward
    /// the strongest, since the first song is the one most often skipped when it doesn't land.
    pub fn opener(&mut self, include: &dyn Fn(&Candidate) -> bool) -> Option<String> {
        if let Some(index) = self.probe_pick(Some(false), true, include) {
            return Some(self.candidates[index].identity.clone());
        }
        let yours: Vec<usize> = (0..self.candidates.len())
            .filter(|&i| {
                let c = &self.candidates[i];
                !c.is_new && !c.is_resting && !self.picked.contains(&c.identity) && include(c)
            })
            .collect();
        let Some(choice) = self.draw(&yours, 2.0, false) else {
            return self.next_playable(include);
        };
        let reason = self.reason_choosing(choice);
        self.settle_pick(choice, reason);
        Some(self.candidates[choice].identity.clone())
    }

    /// The next song of those that pass `include`, going round them again once they've all
    /// been picked. None only when none passes at all.
    pub fn next_playable(&mut self, include: &dyn Fn(&Candidate) -> bool) -> Option<String> {
        if let Some(song) = self.next_where(None, include) {
            return Some(song);
        }
        let passing: FxHashSet<String> = self
            .candidates
            .iter()
            .filter(|c| include(c))
            .map(|c| c.identity.clone())
            .collect();
        if passing.is_empty() {
            return None;
        }
        // Those begin again, keeping the latest few of them out.
        let in_round: Vec<&String> = self.picks.iter().filter(|p| passing.contains(*p)).collect();
        let keep_count = 20.min(passing.len() / 2);
        let keep: FxHashSet<String> = in_round
            .iter()
            .rev()
            .take(keep_count)
            .map(|s| (*s).clone())
            .collect();
        self.picks
            .retain(|p| !passing.contains(p) || keep.contains(p));
        self.picked
            .retain(|p| !passing.contains(p) || keep.contains(p));
        self.next_where(None, include)
    }

    fn settle_pick(&mut self, choice: usize, reason: Option<Reason>) {
        let identity = self.candidates[choice].identity.clone();
        // As it is now, even when nothing stands out: a song come round again doesn't keep
        // what it was picked for before.
        self.pick_reasons.insert(identity.clone(), reason);
        self.note_picked(&identity);
        self.ease_back();
    }

    /// Takes back a pick that never played, so it can come up again this round.
    pub fn release(&mut self, identity: &str) {
        if !self.picked.remove(identity) {
            return;
        }
        self.picks.retain(|p| p != identity);
        self.pick_reasons.remove(identity);
        if let Some(probe) = &mut self.probe {
            probe.slots.retain(|s| s.identity != identity);
        }
    }

    /// Counts a song as picked without drawing it: the one a listen starts with.
    pub fn note_picked(&mut self, identity: &str) {
        if self.picked.insert(identity.to_owned()) {
            self.picks.push(identity.to_owned());
        }
    }

    // MARK: Hearing how songs went

    pub fn note(&mut self, listen: Listen, identity: &str) {
        match listen {
            Listen::Skipped => self.note_skipped(identity),
            Listen::MovedOn => self.note_moved_on(identity),
            Listen::Finished => self.note_finished(identity),
        }
    }

    /// The song gave way early.
    pub fn note_skipped(&mut self, identity: &str) {
        let Some(&index) = self.by_identity.get(identity) else {
            return;
        };
        let says = self.skip_weight();
        let artist = self.candidates[index].artist_key.clone();
        let bias = self.artist_bias.get(&artist).copied().unwrap_or(1.0);
        self.artist_bias
            .insert(artist, (bias * 0.55_f64.powf(says)).max(0.15));
        if let Some(genre) = self.candidates[index].genre_key.clone() {
            // A probe song skipped says a little more about its genre than one skipped later.
            let base: f64 = if self.is_probe_pick(identity) {
                0.75
            } else {
                0.85
            };
            let bias = self.genre_bias.get(&genre).copied().unwrap_or(1.0);
            self.genre_bias
                .insert(genre, (bias * base.powf(says)).max(0.4));
        }
        if self.candidates[index].is_new {
            self.new_share = self.clamp_share(self.new_share * 0.7_f64.powf(says));
        }
        self.spread_from(index, -SKIP_DIM * says);
        self.record(index, true, false);
        self.learn(identity, index, false, 1.0);
        self.steer_after_skips();
    }

    /// Heard for a while and then left: counts toward a run of skips, but weighs less.
    pub fn note_moved_on(&mut self, identity: &str) {
        let Some(&index) = self.by_identity.get(identity) else {
            return;
        };
        let says = self.skip_weight();
        let artist = self.candidates[index].artist_key.clone();
        let bias = self.artist_bias.get(&artist).copied().unwrap_or(1.0);
        self.artist_bias
            .insert(artist, (bias * 0.7_f64.powf(says)).max(0.02));
        if let Some(genre) = self.candidates[index].genre_key.clone() {
            let bias = self.genre_bias.get(&genre).copied().unwrap_or(1.0);
            self.genre_bias
                .insert(genre, (bias * 0.9_f64.powf(says)).max(0.4));
        }
        if self.candidates[index].is_new {
            self.new_share = self.clamp_share(self.new_share * 0.9_f64.powf(says));
        }
        self.spread_from(index, -SKIP_DIM * 0.5 * says);
        self.record(index, true, true);
        self.learn(identity, index, false, 0.5);
        self.steer_after_skips();
    }

    /// The song played through, or near enough.
    pub fn note_finished(&mut self, identity: &str) {
        let Some(&index) = self.by_identity.get(identity) else {
            return;
        };
        let says = self.landing_weight();
        let artist = self.candidates[index].artist_key.clone();
        let bias = self.artist_bias.get(&artist).copied().unwrap_or(1.0);
        self.artist_bias
            .insert(artist, (bias * 1.25_f64.powf(says)).min(3.0));
        let was_searching = self.is_searching()
            || self
                .probe
                .as_ref()
                .is_some_and(|p| p.is_open && p.extended > 0);
        if let Some(genre) = self.candidates[index].genre_key.clone() {
            // After a run of skips, the song that finally plays through is the one to follow,
            // and a probe song let play says more than one let play later.
            let factor = if was_searching {
                2.0
            } else {
                (if self.is_probe_pick(identity) {
                    1.6_f64
                } else {
                    1.15
                })
                .powf(says)
            };
            let bias = self.genre_bias.get(&genre).copied().unwrap_or(1.0);
            self.genre_bias.insert(genre, (bias * factor).min(4.0));
        }
        if self.candidates[index].is_new {
            self.new_share = self.clamp_share(self.new_share * 1.2_f64.powf(says) + 0.02);
        }
        self.spread_from(index, LANDING_LIFT * if was_searching { 1.5 } else { says });
        self.record(index, false, false);
        self.learn(identity, index, true, 1.0);
        if was_searching {
            self.avoiding = None;
            let candidate = &self.candidates[index];
            self.steering = Some(match &candidate.genre {
                Some(genre) => Steering::TowardGenre(genre.clone()),
                None => Steering::TowardArtist(candidate.artist_name.clone()),
            });
        }
    }

    /// Loved while it played: the strongest thing anyone can say about a song.
    pub fn note_loved(&mut self, identity: &str) {
        let Some(&index) = self.by_identity.get(identity) else {
            return;
        };
        let artist = self.candidates[index].artist_key.clone();
        let bias = self.artist_bias.get(&artist).copied().unwrap_or(1.0);
        self.artist_bias.insert(artist, (bias * 2.0).min(4.0));
        let genre = self.candidates[index].genre_key.clone();
        if let Some(genre) = &genre {
            let bias = self.genre_bias.get(genre).copied().unwrap_or(1.0);
            self.genre_bias.insert(genre.clone(), (bias * 1.6).min(4.0));
        }
        self.loved_genre = genre;
        self.avoiding = None;
        self.spread_from(index, LANDING_LIFT * 2.0);
        self.learn(identity, index, true, 2.0);
        // Nothing more to probe for: it knows what you want.
        if let Some(probe) = &mut self.probe {
            probe.is_open = false;
        }
        self.steering = Some(Steering::TowardArtist(
            self.candidates[index].artist_name.clone(),
        ));
    }

    /// Played again: says more than letting it play, less than loving it.
    pub fn note_replayed(&mut self, identity: &str) {
        let Some(&index) = self.by_identity.get(identity) else {
            return;
        };
        let artist = self.candidates[index].artist_key.clone();
        let bias = self.artist_bias.get(&artist).copied().unwrap_or(1.0);
        self.artist_bias.insert(artist, (bias * 1.5).min(4.0));
        if let Some(genre) = self.candidates[index].genre_key.clone() {
            let bias = self.genre_bias.get(&genre).copied().unwrap_or(1.0);
            self.genre_bias.insert(genre, (bias * 1.3).min(4.0));
        }
        self.avoiding = None;
        self.spread_from(index, LANDING_LIFT * 1.5);
        self.learn(identity, index, true, 1.5);
        if let Some(probe) = &mut self.probe {
            probe.is_open = false;
        }
    }

    /// Leans the listen toward the songs that go with this one, as if it had just landed: the
    /// song a station is started from. It needn't be one of the mix's own.
    pub fn lean_toward(&mut self, identity: &str, amount: f64) {
        let artist = self
            .by_identity
            .get(identity)
            .map(|&i| self.candidates[i].artist_key.clone());
        self.spread(identity, artist.as_deref(), LANDING_LIFT * amount);
    }

    /// Why a song came up, as it was when drawn, or for one it didn't draw, what it stands for.
    pub fn reason(&self, identity: &str) -> Option<Reason> {
        if let Some(drawn) = self.pick_reasons.get(identity) {
            return drawn.clone();
        }
        self.by_identity
            .get(identity)
            .and_then(|&i| self.candidates[i].reason.clone())
    }

    fn reason_choosing(&self, choice: usize) -> Option<Reason> {
        let candidate = &self.candidates[choice];
        let bias_of = |key: Option<&String>| {
            key.and_then(|k| self.genre_bias.get(k))
                .copied()
                .unwrap_or(1.0)
        };
        match &self.steering {
            Some(Steering::TowardGenre(genre))
                if candidate.genre_key.is_some()
                    && candidate.genre_key == genre_key(genre)
                    && bias_of(candidate.genre_key.as_ref()) >= HOMING_IN =>
            {
                return Some(Reason::MoreOf(genre.clone()));
            }
            Some(Steering::TowardArtist(artist)) => {
                let by_them = self
                    .candidates
                    .iter()
                    .find(|c| &c.artist_name == artist)
                    .is_some_and(|c| c.artist_key == candidate.artist_key);
                let in_loved_genre = self.loved_genre.is_some()
                    && candidate.genre_key == self.loved_genre
                    && bias_of(self.loved_genre.as_ref()) >= HOMING_IN;
                if by_them || in_loved_genre {
                    return Some(Reason::MoreLike(artist.clone()));
                }
            }
            _ => {}
        }
        // What it goes with says more than what it is, except that it's new.
        if !candidate.is_new
            && self
                .interest
                .get(&candidate.identity)
                .copied()
                .unwrap_or(0.0)
                >= GOES_WITH_LIFT
            && let Some((source, _)) = self.lifted_by.get(&candidate.identity)
            && let Some(&index) = self.by_identity.get(source)
        {
            return Some(Reason::GoesWith(self.candidates[index].title.clone()));
        }
        candidate.reason.clone()
    }

    /// Whether the mix is looking for something after a run of skips.
    fn is_searching(&self) -> bool {
        self.steering
            .as_ref()
            .is_some_and(Steering::is_turning_away)
            && self.avoiding.is_some()
    }

    fn record(&mut self, index: usize, skipped: bool, moved_on: bool) {
        let candidate = &self.candidates[index];
        let genre = candidate.genre_key.as_ref().map(|g| {
            self.outcome_genres
                .iter()
                .position(|k| k == g)
                .unwrap_or_else(|| {
                    self.outcome_genres.push(g.clone());
                    self.outcome_genres.len() - 1
                })
        });
        self.outcomes.push(Outcome {
            skipped,
            moved_on,
            genre,
            decade: candidate.decade(),
            is_new: candidate.is_new,
        });
        if self.outcomes.len() > 12 {
            let extra = self.outcomes.len() - 12;
            self.outcomes.drain(..extra);
        }
    }

    /// Looks at the skips in a row just now for what they share, and turns away from it: a
    /// genre first, then a decade, then new or known. Skips that share nothing try genres the
    /// run didn't have.
    fn steer_after_skips(&mut self) {
        // The probe is already trying a different direction with every song.
        if self.probe.as_ref().is_some_and(|p| p.is_open) {
            return;
        }
        let run: Vec<Outcome> = self
            .outcomes
            .iter()
            .rev()
            .take_while(|o| o.skipped)
            .copied()
            .collect();
        // Someone who skips freely takes a longer run to mean something.
        let run_length = if self.expected_skip_rate(run.len()) > 0.5 {
            RUN_LENGTH + 1
        } else {
            RUN_LENGTH
        };
        if run.len() < run_length {
            return;
        }
        // Songs only moved on from make a run only once there are a few of them.
        if !run[..RUN_LENGTH].iter().any(|o| !o.moved_on) && run.len() <= RUN_LENGTH {
            return;
        }
        let latest = &run[..RUN_LENGTH];
        let mut avoid = Avoidance {
            picks_left: AVOID_FOR,
            ..Avoidance::default()
        };
        if let Some(genre) = latest[0]
            .genre
            .filter(|&g| latest.iter().all(|o| o.genre == Some(g)))
        {
            let key = self.outcome_genres[genre].clone();
            let bias = self.genre_bias.get(&key).copied().unwrap_or(1.0);
            self.genre_bias.insert(key.clone(), bias.min(0.15));
            self.steering = Some(Steering::AwayFromGenre(self.display_name(&key)));
            avoid.genres.insert(key);
        } else if let Some(decade) = latest[0]
            .decade
            .filter(|&d| latest.iter().all(|o| o.decade == Some(d)))
        {
            avoid.decades.insert(decade);
            self.steering = Some(Steering::AwayFromDecade(decade));
        } else if latest.iter().all(|o| o.is_new) {
            self.new_share = self.clamp_share(self.new_share * 0.5);
            self.steering = Some(Steering::FewerNewFinds);
        } else if latest.iter().all(|o| !o.is_new)
            && run.len() >= 3
            && self.candidates.iter().any(|c| c.is_new)
        {
            self.new_share = self.clamp_share(self.new_share * 1.6 + 0.05);
            self.steering = Some(Steering::MoreNewFinds);
        } else if run.len() >= 3 {
            avoid.genres = run
                .iter()
                .filter_map(|o| o.genre.map(|g| self.outcome_genres[g].clone()))
                .collect();
            self.steering = Some(Steering::TryingSomethingElse);
        } else {
            return;
        }
        self.avoiding = Some(avoid);
    }

    // MARK: Following what lands

    /// Now and then, just after a song has landed, only the songs that go with it, as often as
    /// what landed says, fading as the lift does.
    fn following_what_landed(&mut self, choices: Vec<usize>) -> Vec<usize> {
        let lifted: Vec<usize> = choices
            .iter()
            .copied()
            .filter(|&i| self.interest_of(i) >= FOLLOWS_FROM)
            .collect();
        let Some(peak) = lifted.iter().map(|&i| self.interest_of(i)).reduce(f64::max) else {
            return choices;
        };
        let chance = MOST_FOLLOWING.min(peak * FOLLOWING_PER_LIFT);
        if self.random.unit() < chance {
            lifted
        } else {
            choices
        }
    }

    fn interest_of(&self, index: usize) -> f64 {
        self.interest
            .get(&self.candidates[index].identity)
            .copied()
            .unwrap_or(0.0)
    }

    /// How likely this person is to skip a song, 0 to 1: what's usual for anyone, then what
    /// they've done on Tracks Radio before, then this listen. `setting_aside` leaves out the
    /// latest skips, such as the run being judged, so a run doesn't excuse itself.
    fn expected_skip_rate(&self, setting_aside: usize) -> f64 {
        let before = self
            .habits_base
            .as_ref()
            .map(RadioHabits::listening)
            .unwrap_or_default();
        let missed_now = (self.listen_tally.missed - setting_aside as f64).max(0.0);
        let skipped = USUAL_SKIP_RATE * USUAL_WEIGHT + before.missed + missed_now;
        let heard = USUAL_WEIGHT + before.total() + self.listen_tally.landed + missed_now;
        skipped / heard
    }

    /// What a skip says: more from someone who seldom skips, less from someone who skips freely.
    fn skip_weight(&self) -> f64 {
        ((1.0 - self.expected_skip_rate(0)) / (1.0 - USUAL_SKIP_RATE)).clamp(0.5, 1.5)
    }

    /// What a song let play says, the other way round.
    fn landing_weight(&self) -> f64 {
        (self.expected_skip_rate(0) / USUAL_SKIP_RATE).clamp(0.7, 1.6)
    }

    /// How much likelier a song is for going with what's landed this listen.
    fn lift(&self, candidate: &Candidate) -> f64 {
        let mut power = self
            .interest
            .get(&candidate.identity)
            .copied()
            .unwrap_or(0.0);
        // The graph knows no new finds: their artists say what it can.
        if candidate.is_new {
            power += self
                .artist_interest
                .get(&candidate.artist_key)
                .copied()
                .unwrap_or(0.0);
            if let Some(Reason::NewFindLike(like)) = &candidate.reason {
                power += 0.7
                    * self
                        .artist_interest
                        .get(&folded(like))
                        .copied()
                        .unwrap_or(0.0);
            }
        }
        power.clamp(MOST_DIM, MOST_LIFT).exp()
    }

    fn spread_from(&mut self, index: usize, amount: f64) {
        let identity = self.candidates[index].identity.clone();
        let artist = self.candidates[index].artist_key.clone();
        self.spread(&identity, Some(&artist), amount);
    }

    /// Lifts the songs that go with this one, or dims them for a negative amount: its
    /// neighbors by how close they are, theirs a little, and the artists of all of them. By the
    /// square root of closeness, so the whole circle a song belongs to moves.
    fn spread(&mut self, identity: &str, artist: Option<&str>, amount: f64) {
        if amount == 0.0 {
            return;
        }
        if let Some(artist) = artist {
            *self.artist_interest.entry(artist.to_owned()).or_default() += 0.5 * amount;
        }
        let neighbors = self.taste.neighbors(identity).to_vec();
        for neighbor in neighbors {
            let change = amount * neighbor.closeness.sqrt();
            self.add_interest(change, &neighbor.identity, identity);
            if let Some(&index) = self.by_identity.get(&neighbor.identity) {
                let artist = self.candidates[index].artist_key.clone();
                *self.artist_interest.entry(artist).or_default() += 0.3 * change;
            }
            let further: Vec<(String, f64)> = self
                .taste
                .neighbors(&neighbor.identity)
                .iter()
                .take(8)
                .filter(|f| f.identity != identity)
                .map(|f| (f.identity.clone(), f.closeness))
                .collect();
            for (other, closeness) in further {
                self.add_interest(change * closeness.sqrt() * SECOND_STEP, &other, identity);
            }
        }
    }

    fn add_interest(&mut self, change: f64, identity: &str, source: &str) {
        *self.interest.entry(identity.to_owned()).or_default() += change;
        let best = self
            .lifted_by
            .get(identity)
            .map_or(0.0, |(_, amount)| *amount);
        if change > best {
            self.lifted_by
                .insert(identity.to_owned(), (source.to_owned(), change));
        }
    }

    /// The choices that stay clear of what a run of skips shared, while any do.
    fn clear_of_avoided(&self, choices: Vec<usize>) -> Vec<usize> {
        let Some(avoid) = &self.avoiding else {
            return choices;
        };
        let clear: Vec<usize> = choices
            .iter()
            .copied()
            .filter(|&i| {
                let c = &self.candidates[i];
                !c.genre_key
                    .as_ref()
                    .is_some_and(|g| avoid.genres.contains(g))
                    && !c.decade().is_some_and(|d| avoid.decades.contains(&d))
            })
            .collect();
        if clear.is_empty() { choices } else { clear }
    }

    /// After each pick, genres drift a tenth of the way back to even, skipped artists a little,
    /// lifts fade, and what's being avoided comes one pick closer to coming back.
    fn ease_back(&mut self) {
        for bias in self.genre_bias.values_mut() {
            *bias = 1.0 + (*bias - 1.0) * 0.9;
        }
        // A skipped artist comes back more slowly, but comes back.
        for bias in self.artist_bias.values_mut() {
            if *bias < 1.0 {
                *bias = 1.0 + (*bias - 1.0) * 0.96;
            }
        }
        fade(&mut self.interest);
        fade(&mut self.artist_interest);
        self.lifted_by.retain(|_, (_, amount)| {
            if *amount < 0.03 {
                return false;
            }
            *amount *= INTEREST_FADE;
            true
        });
        if let Some(avoid) = &mut self.avoiding {
            avoid.picks_left -= 1;
            if avoid.picks_left == 0 {
                self.avoiding = None;
            }
        }
    }

    /// The genre as the candidates spell it, for the player to say.
    fn display_name(&self, key: &str) -> String {
        self.candidates
            .iter()
            .find(|c| c.genre_key.as_deref() == Some(key))
            .and_then(|c| c.genre.clone())
            .unwrap_or_else(|| key.to_owned())
    }

    /// Everything has been picked: begin again, keeping the latest picks out so the end of one
    /// round doesn't meet the start of the next.
    fn start_over(&mut self) {
        let keep = 20.min(self.candidates.len() / 2);
        let start = self.picks.len().saturating_sub(keep);
        self.picks.drain(..start);
        self.picked = self.picks.iter().cloned().collect();
    }

    /// `emphasis` is a power the weights are raised to: 2 leans harder toward the strongest.
    /// `learning`: whether what Tracks Radio has learned about genres and decades weighs in.
    fn draw(&mut self, choices: &[usize], emphasis: f64, learning: bool) -> Option<usize> {
        if choices.is_empty() {
            return None;
        }
        let lifts = if learning { self.lifts(choices) } else { None };
        // Artists heard lately, each time halving their chance.
        let mut lately: FxHashMap<&str, i32> = FxHashMap::default();
        let start = self.picks.len().saturating_sub(self.remembers_artists);
        for identity in &self.picks[start..] {
            if let Some(&index) = self.by_identity.get(identity) {
                *lately
                    .entry(self.candidates[index].artist_key.as_str())
                    .or_default() += 1;
            }
        }
        let previous = self.picks.last().cloned();
        let before = previous
            .as_ref()
            .and_then(|p| self.by_identity.get(p))
            .and_then(|&i| self.candidates[i].genre_key.clone());
        let weights: Vec<f64> = choices
            .iter()
            .map(|&i| {
                let c = &self.candidates[i];
                let weight = c.weight
                    * lifts.as_ref().map_or(1.0, |l| l.weight(c))
                    * self.artist_bias.get(&c.artist_key).copied().unwrap_or(1.0)
                    * c.genre_key
                        .as_ref()
                        .and_then(|g| self.genre_bias.get(g))
                        .copied()
                        .unwrap_or(1.0)
                    * 0.5_f64.powi(lately.get(c.artist_key.as_str()).copied().unwrap_or(0))
                    * if before.is_some() && c.genre_key == before {
                        FLOW
                    } else {
                        1.0
                    }
                    * self.lift(c)
                    * previous
                        .as_ref()
                        .map_or(1.0, |p| 1.0 + LEAD_LIFT * self.taste.leads(p, &c.identity));
                if (emphasis - 1.0).abs() < f64::EPSILON {
                    weight
                } else {
                    weight.powf(emphasis)
                }
            })
            .collect();
        let total: f64 = weights.iter().sum();
        if total <= 0.0 {
            return choices.first().copied();
        }
        let mut target = self.random.unit() * total;
        for (&choice, weight) in choices.iter().zip(&weights) {
            target -= weight;
            if target < 0.0 {
                return Some(choice);
            }
        }
        choices.last().copied()
    }

    // MARK: Carrying a listen over

    /// Carries a listen over to a newly tuned mix: what's been picked and skipped, so retuning
    /// mid-listen doesn't start again from the top. How far skips and keeps have moved the
    /// share of new finds carries over too, on top of the new tuning's share.
    pub fn continue_listen(&mut self, earlier: &Self) {
        for identity in &earlier.picks {
            self.note_picked(identity);
        }
        for (identity, reason) in &earlier.pick_reasons {
            self.pick_reasons.insert(identity.clone(), reason.clone());
        }
        if earlier.starting_share > 0.0
            && (earlier.new_share - earlier.starting_share).abs() > f64::EPSILON
        {
            self.new_share =
                self.clamp_share(self.new_share * earlier.new_share / earlier.starting_share);
        }
        for (artist, bias) in &earlier.artist_bias {
            self.artist_bias.insert(artist.clone(), *bias);
        }
        self.genre_bias.clone_from(&earlier.genre_bias);
        self.interest.clone_from(&earlier.interest);
        self.artist_interest.clone_from(&earlier.artist_interest);
        self.lifted_by.clone_from(&earlier.lifted_by);
        self.listen_tally = earlier.listen_tally;
        if self.taste.is_empty() {
            self.taste = earlier.taste.clone();
        }
        self.loved_genre.clone_from(&earlier.loved_genre);
        self.outcomes.clone_from(&earlier.outcomes);
        self.outcome_genres.clone_from(&earlier.outcome_genres);
        self.avoiding.clone_from(&earlier.avoiding);
        self.steering.clone_from(&earlier.steering);
        if let Some(habits) = &earlier.habits_base {
            self.habits_base = Some(habits.clone());
            self.learned = earlier.learned.clone();
            self.probe.clone_from(&earlier.probe);
            self.picks_since_probe = earlier.picks_since_probe;
            self.shares = shares(&self.candidates);
        }
    }

    // MARK: Probing and habits

    /// Starts Tracks Radio's listen from what has landed before, and learns from it as it
    /// plays. Call it before the first pick. `probing`: whether to open with a probe, for a
    /// listen started from nothing in particular rather than from a song.
    pub fn begin(&mut self, habits: &RadioHabits, probing: bool, now_ms: i64) {
        let base = habits.decayed(now_ms);
        self.learned = RadioHabits::default();
        self.shares = shares(&self.candidates);
        let has_new_finds = self.candidates.iter().any(|c| c.is_new);
        // New finds that have landed before make a few more of them, and ones skipped a few
        // fewer, never far from the share tuned.
        let novelty = base.tally_new_finds(self.band);
        if novelty.total() > 0.0 && has_new_finds {
            let lift = LandingOdds::new(novelty).mean() / LandingOdds::PRIOR.mean();
            self.new_share = self.clamp_share(self.new_share * lift.clamp(0.75, 1.25));
        }
        self.probe = if probing {
            Probe::plan(
                &self.shares,
                self.candidates.iter().filter(|c| !c.is_new).count(),
                base.heard(self.band),
                self.new_share,
                has_new_finds,
            )
        } else {
            None
        };
        self.habits_base = Some(base);
    }

    /// Whether the opening probe is still trying directions.
    pub fn is_probing(&self) -> bool {
        self.probe.as_ref().is_some_and(|p| p.is_open)
    }

    /// What has landed, this listen included, to keep for the next. None for a mix that
    /// wasn't begun with [`Self::begin`].
    pub fn habits(&self, now_ms: i64) -> Option<RadioHabits> {
        self.habits_base
            .as_ref()
            .map(|base| base.adding(&self.learned).decayed(now_ms))
    }

    fn is_probe_pick(&self, identity: &str) -> bool {
        self.probe
            .as_ref()
            .is_some_and(|p| p.slots.iter().any(|s| s.identity == identity))
    }

    /// The probe's next song, from a direction it hasn't tried yet. None once the probe is
    /// over, or when no such direction has a song that passes.
    #[expect(
        clippy::too_many_lines,
        reason = "one probe step, as the Swift app has it"
    )]
    fn probe_pick(
        &mut self,
        only_new: Option<bool>,
        opening: bool,
        include: &dyn Fn(&Candidate) -> bool,
    ) -> Option<usize> {
        let mut probe = self.probe.clone().filter(|p| p.is_open)?;
        if probe.slots.len() >= probe.planned {
            let heard: Vec<bool> = probe.slots.iter().filter_map(|s| s.landed).collect();
            if heard.contains(&true) {
                self.end_probe(true);
                return None;
            }
            // Nothing landed: another direction, as long as there are some left to try.
            if heard.len() < 2 || probe.extended >= probe::MOST_EXTRA {
                self.end_probe(false);
                return None;
            }
            probe.planned += 1;
            probe.extended += 1;
            self.steering = Some(Steering::TryingSomethingElse);
        }

        let wants_new = only_new.unwrap_or_else(|| probe.new_slots.contains(&probe.slots.len()));
        let artists: FxHashSet<&str> = self
            .picks
            .iter()
            .filter_map(|p| self.by_identity.get(p))
            .map(|&i| self.candidates[i].artist_key.as_str())
            .collect();
        let families = probe.families();
        let open: Vec<usize> = (0..self.candidates.len())
            .filter(|&i| {
                let c = &self.candidates[i];
                let Some(family) = c.genre_key.as_deref() else {
                    return false;
                };
                !families.contains(family)
                    && !self.picked.contains(&c.identity)
                    && !artists.contains(c.artist_key.as_str())
                    && include(c)
                    && only_new.is_none_or(|n| c.is_new == n)
            })
            .collect();
        let open = self.clear_of_avoided(open);
        let mut by_family: FxHashMap<&str, Vec<usize>> = FxHashMap::default();
        for &i in &open {
            let family = self.candidates[i].genre_key.as_deref().unwrap_or_default();
            if self.shares.get(family).copied().unwrap_or(0.0) >= probe::LEAST_SHARE {
                by_family.entry(family).or_default().push(i);
            }
        }
        // Families with the kind of song this one is to be, where any have.
        let of_kind: Vec<&str> = by_family
            .iter()
            .filter(|(_, songs)| {
                songs
                    .iter()
                    .any(|&i| self.candidates[i].is_new == wants_new)
            })
            .map(|(family, _)| *family)
            .collect();
        let mut choices: Vec<&str> = if of_kind.is_empty() {
            by_family.keys().copied().collect()
        } else {
            of_kind
        };
        choices.sort_unstable();

        let habits = self
            .habits_base
            .clone()
            .unwrap_or_default()
            .adding(&self.learned);
        let prior = LandingOdds::PRIOR;
        let odds: Vec<LandingOdds> = choices
            .iter()
            .map(|f| LandingOdds::new(habits.tally_genre(f, self.band)))
            .collect();
        // A direction that seldom lands here waits until the rest have been tried.
        let likely: Vec<bool> = odds
            .iter()
            .map(|o| o.mean() >= probe::LEAST_LANDED || probe.extended > 0)
            .collect();
        let any_likely = likely.contains(&true);
        let scores: Vec<f64> = choices
            .iter()
            .zip(&odds)
            .zip(&likely)
            .map(|((family, odds), &likely)| {
                if any_likely && !likely {
                    return 0.0;
                }
                let share = self.shares.get(*family).copied().unwrap_or(0.0);
                let lift = (odds.mean() / prior.mean()).powi(2);
                // The opener: the direction likeliest to land. The rest: the ones most worth
                // hearing about, played a lot and not yet sure of.
                let score = if opening {
                    share * lift
                } else {
                    share.sqrt() * lift * odds.spread() / prior.spread()
                };
                score * score
            })
            .collect();
        let family = pick_weighted(&mut self.random, &choices, &scores).map(str::to_owned);
        let pool = family.as_deref().and_then(|f| by_family.get(f)).cloned();
        let (Some(family), Some(mut pool)) = (family, pool) else {
            let nothing_left = by_family.is_empty();
            let tried_further = probe.extended > 0;
            self.probe = Some(probe);
            // Trying further, and every direction has been tried: what's learned takes over.
            if tried_further && nothing_left {
                self.end_probe(false);
            }
            return None;
        };
        let kind: Vec<usize> = pool
            .iter()
            .copied()
            .filter(|&i| self.candidates[i].is_new == wants_new)
            .collect();
        if !kind.is_empty() {
            pool = kind;
        }
        let rested: Vec<usize> = pool
            .iter()
            .copied()
            .filter(|&i| !self.candidates[i].is_resting)
            .collect();
        if !rested.is_empty() {
            pool = rested;
        }
        // Another era than the probe has played.
        let unheard: Vec<usize> = pool
            .iter()
            .copied()
            .filter(|&i| {
                self.candidates[i]
                    .decade()
                    .is_none_or(|d| !probe.decades.contains(&d))
            })
            .collect();
        if !unheard.is_empty() {
            pool = unheard;
        }

        let Some(choice) = self.draw(&pool, if opening { 2.0 } else { 1.0 }, false) else {
            self.probe = Some(probe);
            return None;
        };
        let identity = self.candidates[choice].identity.clone();
        probe.slots.push(Slot {
            identity: identity.clone(),
            family: Some(family),
            landed: None,
        });
        if let Some(decade) = self.candidates[choice].decade() {
            probe.decades.insert(decade);
        }
        self.probe = Some(probe);
        self.pick_reasons
            .insert(identity.clone(), Some(Reason::Probing));
        self.note_picked(&identity);
        self.ease_back();
        Some(choice)
    }

    /// Ends the probe. Settling, it says which genre landed best.
    fn end_probe(&mut self, settling: bool) {
        let Some(probe) = self.probe.as_mut().filter(|p| p.is_open) else {
            return;
        };
        probe.is_open = false;
        if !settling {
            return;
        }
        let mut landed: Vec<String> = probe
            .slots
            .iter()
            .filter(|s| s.landed == Some(true))
            .filter_map(|s| s.family.clone())
            .collect();
        landed.sort_unstable();
        landed.dedup();
        let habits = self
            .habits_base
            .clone()
            .unwrap_or_default()
            .adding(&self.learned);
        let band = self.band;
        let mean = |family: &str| LandingOdds::new(habits.tally_genre(family, band)).mean();
        let mut best: Option<&String> = None;
        for family in &landed {
            // The last of equals, as Swift's `max(by:)` keeps.
            if best.is_none_or(|b| mean(b) <= mean(family)) {
                best = Some(family);
            }
        }
        let Some(best) = best else {
            return;
        };
        // Already following a song let play or loved: that says more.
        if !matches!(
            self.steering,
            Some(Steering::TowardGenre(_) | Steering::TowardArtist(_))
        ) {
            self.steering = Some(Steering::SettledOn(self.display_name(best)));
        }
    }

    /// Takes in how a song went: for the habits kept across listens, counting double for a
    /// probe song, and for the probe, which tries further when nothing lands. `counting`: less
    /// for a song moved on from than one skipped, more for one loved.
    fn learn(&mut self, identity: &str, index: usize, landed: bool, counting: f64) {
        self.listen_tally.landed += if landed { 1.0 } else { 0.0 };
        self.listen_tally.missed += if landed { 0.0 } else { 1.0 };
        if self.habits_base.is_none() {
            return;
        }
        self.learned.record_listen(landed);
        let slot = self
            .probe
            .as_ref()
            .and_then(|p| p.slots.iter().position(|s| s.identity == identity));
        let weight = counting * if slot.is_some() { PROBE_COUNTS } else { 1.0 };
        let candidate = &self.candidates[index];
        // A skip remembered for next time counts for half a song let play.
        self.learned.record(
            if landed { weight } else { 0.0 },
            if landed { 0.0 } else { weight * 0.5 },
            candidate.genre_key.as_deref(),
            candidate.decade(),
            candidate.is_new,
            self.band,
        );
        let Some(slot) = slot else {
            return;
        };
        let Some(probe) = self
            .probe
            .as_mut()
            .filter(|p| p.is_open && p.slots[slot].landed.is_none())
        else {
            return;
        };
        probe.slots[slot].landed = Some(landed);
        if landed {
            // Found after a probe that was all skips: that's the one to follow.
            if probe.extended > 0 {
                self.end_probe(false);
            }
            return;
        }
        // Every probe song heard so far skipped, and nearly all of them heard: it says now
        // that it's going to try further.
        let heard: Vec<bool> = probe.slots.iter().filter_map(|s| s.landed).collect();
        if heard.len() >= 2.max(probe.planned.saturating_sub(1)) && !heard.contains(&true) {
            self.steering = Some(Steering::TryingSomethingElse);
        }
    }

    /// How much what Tracks Radio has learned makes each choice likelier, for a pick after the
    /// probe: a guess at how likely each genre family and decade is to land, drawn from what's
    /// been heard (Thompson sampling). Now and then, less often as the listen goes on, nothing
    /// learned weighs in, so a genre that didn't land early can still come round.
    fn lifts(&mut self, choices: &[usize]) -> Option<Lifts> {
        let base = self.habits_base.as_ref()?;
        if self.is_probing() {
            return None;
        }
        let explore =
            LEAST_EXPLORING.max(MOST_EXPLORING * 0.85_f64.powi(self.picks_since_probe as i32));
        if self.random.unit() < explore {
            return None;
        }
        let habits = base.adding(&self.learned);
        let prior = LandingOdds::PRIOR.mean();
        let mut lifts = Lifts::default();
        let mut families: Vec<&str> = choices
            .iter()
            .filter_map(|&i| self.candidates[i].genre_key.as_deref())
            .collect();
        families.sort_unstable();
        families.dedup();
        for family in families {
            let guess =
                LandingOdds::new(habits.tally_genre(family, self.band)).draw(&mut self.random);
            lifts
                .genres
                .insert(family.to_owned(), (guess / prior).powi(2).clamp(0.15, 4.0));
        }
        let mut decades: Vec<i32> = choices
            .iter()
            .filter_map(|&i| self.candidates[i].decade())
            .collect();
        decades.sort_unstable();
        decades.dedup();
        for decade in decades {
            let guess =
                LandingOdds::new(habits.tally_decade(decade, self.band)).draw(&mut self.random);
            lifts
                .decades
                .insert(decade, (guess / prior).clamp(0.5, 2.0));
        }
        Some(lifts)
    }
}

/// One of `items`, as likely as its weight. None when none has any. A weight that isn't a
/// positive, finite number counts as none, so one bad score can't skew or break the draw.
fn pick_weighted<'a>(
    random: &mut SeededRandom,
    items: &[&'a str],
    weights: &[f64],
) -> Option<&'a str> {
    let usable = |weight: f64| {
        if weight.is_finite() && weight > 0.0 {
            weight
        } else {
            0.0
        }
    };
    let weighted = || {
        items
            .iter()
            .zip(weights)
            .map(|(&item, &w)| (item, usable(w)))
    };
    let total: f64 = weighted().map(|(_, w)| w).sum();
    if !total.is_finite() || total <= 0.0 {
        return None;
    }
    let mut target = random.unit() * total;
    for (item, weight) in weighted() {
        target -= weight;
        if target < 0.0 {
            return Some(item);
        }
    }
    weighted().rfind(|&(_, w)| w > 0.0).map(|(item, _)| item)
}

fn fade(values: &mut FxHashMap<String, f64>) {
    values.retain(|_, value| {
        if value.abs() < 0.03 {
            return false;
        }
        *value *= INTEREST_FADE;
        true
    });
}

/// Each genre family's share of the mix's weight, for the probe to choose directions by.
fn shares(candidates: &[Candidate]) -> FxHashMap<String, f64> {
    let total: f64 = candidates.iter().map(|c| c.weight).sum();
    let mut shares = FxHashMap::default();
    if total <= 0.0 {
        return shares;
    }
    for candidate in candidates {
        if let Some(key) = &candidate.genre_key {
            *shares.entry(key.clone()).or_default() += candidate.weight / total;
        }
    }
    shares
}

#[cfg(test)]
mod weighted_tests {
    use super::*;

    #[test]
    fn a_weight_that_is_not_a_positive_number_is_never_picked() {
        let items = ["nan", "inf", "negative", "real"];
        let weights = [f64::NAN, f64::INFINITY, -3.0, 1.0];
        let mut random = SeededRandom::new(11);
        for _ in 0..200 {
            assert_eq!(pick_weighted(&mut random, &items, &weights), Some("real"));
        }
    }

    #[test]
    fn more_weights_than_items_only_ever_picks_an_item() {
        let items = ["only"];
        let weights = [0.0, 5.0, 5.0];
        let mut random = SeededRandom::new(3);
        assert_eq!(pick_weighted(&mut random, &items, &weights), None);
    }

    #[test]
    fn nothing_usable_picks_nothing() {
        let mut random = SeededRandom::new(5);
        assert_eq!(
            pick_weighted(&mut random, &["a", "b"], &[f64::NAN, 0.0]),
            None
        );
        assert_eq!(pick_weighted(&mut random, &[], &[]), None);
        assert_eq!(
            pick_weighted(&mut random, &["a", "b"], &[f64::MAX, f64::MAX]),
            None
        );
    }
}
