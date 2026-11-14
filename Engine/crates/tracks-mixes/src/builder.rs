//! The Play tab's mixes, a port of the Swift app's `MixBuilder`.
//!
//! Each mix says why it holds what it holds (see [`MixKind`]), leaves out songs the person
//! asked to hear less of, and is only offered when it has enough songs to be worth playing.

use jiff::tz::TimeZone;
use jiff::{Timestamp, ToSpan, Zoned};
use rustc_hash::{FxHashMap, FxHashSet};

use crate::shuffle::{daily_seed, fresh_order};

/// Songs in a mix at most.
pub const MIX_LENGTH: usize = 40;
/// Songs a mix needs before it's offered.
pub const MINIMUM_SONGS: usize = 6;
/// The time-of-day mix leads the page, so it needs a few more.
pub const MINIMUM_RIGHT_NOW_SONGS: usize = 8;

const DAY_MS: f64 = 86_400_000.0;
const HOUR_MS: i64 = 3_600_000;

/// One play, as the mixes need it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MixPlay {
    /// The song's identity: folded title and artist.
    pub identity: String,
    pub catalog_id: String,
    pub title: String,
    pub artist_name: String,
    pub album_title: Option<String>,
    pub artwork_url: Option<String>,
    pub played_at_ms: i64,
    pub is_radio: bool,
}

/// The four parts of a day.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DayPart {
    Morning = 0,
    Afternoon = 1,
    Evening = 2,
    Night = 3,
}

impl DayPart {
    pub const ALL: [Self; 4] = [Self::Morning, Self::Afternoon, Self::Evening, Self::Night];

    pub fn from_hour(hour: i8) -> Self {
        match hour {
            5..=11 => Self::Morning,
            12..=16 => Self::Afternoon,
            17..=21 => Self::Evening,
            _ => Self::Night,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Morning => "morning",
            Self::Afternoon => "afternoon",
            Self::Evening => "evening",
            Self::Night => "night",
        }
    }
}

/// Which mix, and what it was built around.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MixKind {
    RightNow { part: DayPart, is_weekend: bool },
    OnRepeat,
    NewFinds,
    RadioFinds,
    Rediscover,
    Throwback { months_ago: u8, around_ms: i64 },
    AllTimeFavorites,
    DeepCuts,
}

impl MixKind {
    /// The Swift app's id for the mix.
    pub fn id(&self) -> String {
        match self {
            Self::RightNow { part, is_weekend } => {
                format!("rightNow.{}.{is_weekend}", *part as u8)
            }
            Self::OnRepeat => "onRepeat".into(),
            Self::NewFinds => "newFinds".into(),
            Self::RadioFinds => "radioFinds".into(),
            Self::Rediscover => "rediscover".into(),
            Self::Throwback { months_ago, .. } => format!("throwback.{months_ago}"),
            Self::AllTimeFavorites => "allTimeFavorites".into(),
            Self::DeepCuts => "deepCuts".into(),
        }
    }
}

/// One song in a mix, with the facts its row shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MixSong {
    pub identity: String,
    pub catalog_id: String,
    pub title: String,
    pub artist_name: String,
    pub album_title: Option<String>,
    pub artwork_url: Option<String>,
    pub plays: u32,
    pub last_heard_ms: i64,
}

/// A mix: its kind, its songs in play order, and the plays it was drawn from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mix {
    pub kind: MixKind,
    pub songs: Vec<MixSong>,
    pub play_count: u64,
}

/// Every mix for now.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MixOutput {
    /// What you play at this part of the day, if the history shows a habit.
    pub right_now: Option<Mix>,
    /// The rest of today's kind of day, from the next part on.
    pub other_times: Vec<Mix>,
    /// The shelf, in the order it shows.
    pub mixes: Vec<Mix>,
}

struct Aggregate {
    identity: String,
    artist_key: String,
    song: MixSong,
    /// Oldest first.
    dates: Vec<i64>,
    /// `part * 2 + weekend` for each play.
    slots: Vec<u8>,
    radio_plays: u32,
}

impl Aggregate {
    fn first_heard(&self) -> i64 {
        self.dates.first().copied().unwrap_or(self.song.last_heard_ms)
    }
}

struct Context<'a> {
    songs: &'a [Aggregate],
    first_play_ms: i64,
    now_ms: i64,
    now: Zoned,
}

fn slot(part: DayPart, is_weekend: bool) -> u8 {
    part as u8 * 2 + u8::from(is_weekend)
}

fn is_weekend(zoned: &Zoned) -> bool {
    matches!(
        zoned.weekday(),
        jiff::civil::Weekday::Saturday | jiff::civil::Weekday::Sunday
    )
}

fn decay(age_ms: i64, half_life_days: f64) -> f64 {
    0.5f64.powf((age_ms.max(0) as f64) / (half_life_days * DAY_MS))
}

fn zoned(ms: i64, zone: &TimeZone) -> Zoned {
    Timestamp::from_millisecond(ms)
        .unwrap_or(Timestamp::UNIX_EPOCH)
        .to_zoned(zone.clone())
}

/// Builds every mix from `plays` (any order), leaving out songs in `excluded`.
pub fn build_mixes(
    plays: &[MixPlay],
    excluded: &FxHashSet<String>,
    now_ms: i64,
    zone: &TimeZone,
) -> MixOutput {
    if plays.is_empty() {
        return MixOutput::default();
    }
    let mut sorted: Vec<&MixPlay> = plays.iter().collect();
    sorted.sort_by_key(|p| p.played_at_ms);
    let first_play_ms = sorted[0].played_at_ms;
    let songs = aggregate(&sorted, excluded, zone);
    if songs.is_empty() {
        return MixOutput::default();
    }
    let now = zoned(now_ms, zone);
    let context = Context {
        songs: &songs,
        first_play_ms,
        now_ms,
        now: now.clone(),
    };
    let mixes = [
        on_repeat(&context),
        new_finds(&context),
        all_time_favorites(&context),
        deep_cuts(&context),
        radio_finds(&context),
        rediscover(&context),
        throwback(&context),
    ]
    .into_iter()
    .flatten()
    .collect();

    let weekend = is_weekend(&now);
    let current = DayPart::from_hour(now.hour());
    let other_times = (1..DayPart::ALL.len())
        .map(|offset| DayPart::ALL[(current as usize + offset) % DayPart::ALL.len()])
        .filter_map(|part| time_of_day(&context, part, weekend))
        .collect();
    MixOutput {
        right_now: time_of_day(&context, current, weekend),
        other_times,
        mixes,
    }
}

fn aggregate(plays: &[&MixPlay], excluded: &FxHashSet<String>, zone: &TimeZone) -> Vec<Aggregate> {
    let mut by_identity: FxHashMap<&str, usize> = FxHashMap::default();
    let mut out: Vec<Aggregate> = Vec::new();
    for play in plays {
        if excluded.contains(&play.identity) {
            continue;
        }
        let local = zoned(play.played_at_ms, zone);
        let s = slot(DayPart::from_hour(local.hour()), is_weekend(&local));
        if let Some(&index) = by_identity.get(play.identity.as_str()) {
            let existing = &mut out[index];
            existing.dates.push(play.played_at_ms);
            existing.slots.push(s);
            if play.is_radio {
                existing.radio_plays += 1;
            }
            let song = &mut existing.song;
            if !play.catalog_id.is_empty() {
                song.catalog_id.clone_from(&play.catalog_id);
            }
            song.title.clone_from(&play.title);
            song.artist_name.clone_from(&play.artist_name);
            if play.album_title.is_some() {
                song.album_title.clone_from(&play.album_title);
            }
            if play.artwork_url.is_some() {
                song.artwork_url.clone_from(&play.artwork_url);
            }
            song.plays = existing.dates.len() as u32;
            song.last_heard_ms = play.played_at_ms;
        } else {
            by_identity.insert(&play.identity, out.len());
            out.push(Aggregate {
                identity: play.identity.clone(),
                artist_key: tracks_model::folded(&play.artist_name),
                song: MixSong {
                    identity: play.identity.clone(),
                    catalog_id: play.catalog_id.clone(),
                    title: play.title.clone(),
                    artist_name: play.artist_name.clone(),
                    album_title: play.album_title.clone(),
                    artwork_url: play.artwork_url.clone(),
                    plays: 1,
                    last_heard_ms: play.played_at_ms,
                },
                dates: vec![play.played_at_ms],
                slots: vec![s],
                radio_plays: u32::from(play.is_radio),
            });
        }
    }
    out
}

/// Plays at one part of the day, on weekdays or weekends, the last few months counting most.
/// Leaves out what was heard in the last couple of hours.
fn time_of_day(context: &Context<'_>, part: DayPart, weekend: bool) -> Option<Mix> {
    let recent = context.now_ms - 2 * HOUR_MS;
    let wanted = slot(part, weekend);
    let scored = context
        .songs
        .iter()
        .filter_map(|song| {
            if song.song.last_heard_ms >= recent {
                return None;
            }
            let mut score = 0.0;
            let mut matches = 0;
            for (date, s) in song.dates.iter().zip(&song.slots) {
                if *s == wanted {
                    matches += 1;
                    score += decay(context.now_ms - date, 60.0);
                }
            }
            (matches >= 2).then_some((song, score))
        })
        .collect();
    mix(
        MixKind::RightNow {
            part,
            is_weekend: weekend,
        },
        scored,
        context,
        MINIMUM_RIGHT_NOW_SONGS,
    )
}

/// The most played ever, favouring the ones still in rotation. Five plays or more.
fn all_time_favorites(context: &Context<'_>) -> Option<Mix> {
    let scored = context
        .songs
        .iter()
        .filter(|s| s.dates.len() >= 5)
        .map(|s| {
            let still = 0.5 + 0.5 * decay(context.now_ms - s.song.last_heard_ms, 365.0);
            (s, s.dates.len() as f64 * still)
        })
        .collect();
    mix(MixKind::AllTimeFavorites, scored, context, MINIMUM_SONGS)
}

/// Songs heard once or twice, by the ten artists played most in the last six months, and not
/// in the last month.
fn deep_cuts(context: &Context<'_>) -> Option<Mix> {
    let start = context.now_ms - (180.0 * DAY_MS) as i64;
    let mut artist_plays: FxHashMap<&str, usize> = FxHashMap::default();
    for song in context.songs {
        *artist_plays.entry(&song.artist_key).or_default() +=
            song.dates.iter().filter(|&&d| d >= start).count();
    }
    let mut top: Vec<(&str, usize)> = artist_plays.into_iter().filter(|(_, n)| *n > 0).collect();
    top.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
    top.truncate(10);
    let rank: FxHashMap<&str, usize> = top.iter().enumerate().map(|(i, (k, _))| (*k, i)).collect();
    let recent = context.now_ms - (30.0 * DAY_MS) as i64;
    let scored = context
        .songs
        .iter()
        .filter_map(|song| {
            let position = *rank.get(song.artist_key.as_str())?;
            if !(1..=2).contains(&song.dates.len()) || song.song.last_heard_ms >= recent {
                return None;
            }
            let recency = decay(context.now_ms - song.song.last_heard_ms, 180.0);
            Some((song, (10 - position) as f64 + recency))
        })
        .collect();
    mix(MixKind::DeepCuts, scored, context, MINIMUM_SONGS)
}

/// The last thirty days, the last two weeks counting most. Three plays or more.
fn on_repeat(context: &Context<'_>) -> Option<Mix> {
    let start = context.now_ms - (30.0 * DAY_MS) as i64;
    let scored = context
        .songs
        .iter()
        .filter_map(|song| {
            let dates: Vec<i64> = song.dates.iter().copied().filter(|&d| d >= start).collect();
            if dates.len() < 3 {
                return None;
            }
            let score = dates.iter().map(|d| decay(context.now_ms - d, 10.0)).sum();
            Some((song, score))
        })
        .collect();
    mix(MixKind::OnRepeat, scored, context, MINIMUM_SONGS)
}

/// First heard in the last thirty days, and played at least twice since.
fn new_finds(context: &Context<'_>) -> Option<Mix> {
    let start = context.now_ms - (30.0 * DAY_MS) as i64;
    let scored = context
        .songs
        .iter()
        .filter(|s| s.first_heard() >= start && s.dates.len() >= 2)
        .map(|s| (s, s.dates.len() as f64))
        .collect();
    mix(MixKind::NewFinds, scored, context, MINIMUM_SONGS)
}

/// Heard on a station and never chosen.
fn radio_finds(context: &Context<'_>) -> Option<Mix> {
    let scored = context
        .songs
        .iter()
        .filter(|s| s.radio_plays > 0 && s.radio_plays as usize == s.dates.len())
        .map(|s| {
            let recency = decay(context.now_ms - s.song.last_heard_ms, 30.0);
            (s, f64::from(s.radio_plays) + recency)
        })
        .collect();
    mix(MixKind::RadioFinds, scored, context, MINIMUM_SONGS)
}

/// Four plays or more, none in the last sixty days. Needs ninety days of history.
fn rediscover(context: &Context<'_>) -> Option<Mix> {
    if (context.now_ms - context.first_play_ms) < (90.0 * DAY_MS) as i64 {
        return None;
    }
    let cutoff = context.now_ms - (60.0 * DAY_MS) as i64;
    let scored = context
        .songs
        .iter()
        .filter(|s| s.dates.len() >= 4 && s.song.last_heard_ms < cutoff)
        .map(|s| {
            let absence =
                ((context.now_ms - s.song.last_heard_ms) as f64 / (365.0 * DAY_MS)).min(1.0);
            (s, s.dates.len() as f64 * (0.5 + absence))
        })
        .collect();
    mix(MixKind::Rediscover, scored, context, MINIMUM_SONGS)
}

/// A week either side of this date a year ago, or six or three months ago when the history
/// doesn't reach back a year or that week was quiet.
fn throwback(context: &Context<'_>) -> Option<Mix> {
    let week = (7.0 * DAY_MS) as i64;
    for months in [12u8, 6, 3] {
        let Ok(around) = context.now.checked_sub(i64::from(months).months()) else {
            continue;
        };
        let around_ms = around.timestamp().as_millisecond();
        if around_ms - week < context.first_play_ms {
            continue;
        }
        let (start, end) = (around_ms - week, around_ms + week);
        let scored = context
            .songs
            .iter()
            .filter_map(|s| {
                let n = s.dates.iter().filter(|&&d| d >= start && d < end).count();
                (n > 0).then_some((s, n as f64))
            })
            .collect();
        if let Some(mix) = mix(
            MixKind::Throwback {
                months_ago: months,
                around_ms,
            },
            scored,
            context,
            MINIMUM_SONGS,
        ) {
            return Some(mix);
        }
    }
    None
}

/// The top [`MIX_LENGTH`] by score, sequenced for the day.
fn mix(
    kind: MixKind,
    mut scored: Vec<(&Aggregate, f64)>,
    context: &Context<'_>,
    minimum: usize,
) -> Option<Mix> {
    if scored.len() < minimum {
        return None;
    }
    scored.sort_by(|a, b| {
        b.1.partial_cmp(&a.1)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.0.identity.cmp(&b.0.identity))
    });
    scored.truncate(MIX_LENGTH);
    let play_count = scored.iter().map(|(s, _)| s.dates.len() as u64).sum();
    let entries: Vec<(&MixSong, &str)> = scored
        .iter()
        .map(|(s, _)| (&s.song, s.artist_key.as_str()))
        .collect();
    let seed = daily_seed(
        context.now.year(),
        context.now.month(),
        context.now.day(),
        &kind.id(),
    );
    let ordered = fresh_order(&entries, |(_, key)| (*key).to_owned(), |_| false, seed);
    Some(Mix {
        kind,
        songs: ordered.into_iter().map(|(song, _)| song.clone()).collect(),
        play_count,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_794_000_000_000; // 2026-11-11

    fn play(title: &str, artist: &str, days_ago: f64) -> MixPlay {
        MixPlay {
            identity: tracks_model::song_key(title, artist),
            catalog_id: String::new(),
            title: title.into(),
            artist_name: artist.into(),
            album_title: None,
            artwork_url: None,
            played_at_ms: NOW - (days_ago * DAY_MS) as i64,
            is_radio: false,
        }
    }

    fn build(plays: &[MixPlay]) -> MixOutput {
        build_mixes(plays, &FxHashSet::default(), NOW, &TimeZone::UTC)
    }

    #[test]
    fn empty_history_has_no_mixes() {
        assert_eq!(build(&[]), MixOutput::default());
    }

    #[test]
    fn on_repeat_needs_three_recent_plays() {
        let mut plays = Vec::new();
        for i in 0..8 {
            for d in 0..3 {
                plays.push(play(&format!("Song {i}"), &format!("Artist {}", i % 4), 1.0 + f64::from(d)));
            }
        }
        let out = build(&plays);
        let mix = out.mixes.iter().find(|m| m.kind == MixKind::OnRepeat).expect("on repeat");
        assert_eq!(mix.songs.len(), 8);
        assert_eq!(mix.play_count, 24);

        let few: Vec<MixPlay> = plays.into_iter().take(10).collect();
        assert!(!build(&few).mixes.iter().any(|m| m.kind == MixKind::OnRepeat));
    }

    #[test]
    fn rediscover_needs_ninety_days_and_absence() {
        let mut plays = Vec::new();
        for i in 0..7 {
            for d in 0..4 {
                plays.push(play(&format!("Old {i}"), &format!("A{i}"), 200.0 + f64::from(d)));
            }
        }
        let out = build(&plays);
        assert!(out.mixes.iter().any(|m| m.kind == MixKind::Rediscover));
        let short: Vec<MixPlay> = plays
            .iter()
            .cloned()
            .map(|mut p| {
                p.played_at_ms = NOW - (70.0 * DAY_MS) as i64;
                p
            })
            .collect();
        assert!(!build(&short).mixes.iter().any(|m| m.kind == MixKind::Rediscover));
    }

    #[test]
    fn excluded_songs_leave_every_mix() {
        let mut plays = Vec::new();
        for i in 0..8 {
            for d in 0..3 {
                plays.push(play(&format!("Song {i}"), "Artist", 1.0 + f64::from(d)));
            }
        }
        let excluded: FxHashSet<String> = [tracks_model::song_key("Song 0", "Artist")].into_iter().collect();
        let out = build_mixes(&plays, &excluded, NOW, &TimeZone::UTC);
        assert!(out.mixes.iter().all(|m| m.songs.iter().all(|s| s.title != "Song 0")));
    }

    #[test]
    fn radio_finds_are_only_radio() {
        let mut plays = Vec::new();
        for i in 0..6 {
            let mut p = play(&format!("Radio {i}"), &format!("R{i}"), 3.0);
            p.is_radio = true;
            plays.push(p);
        }
        plays.push(play("Radio 0", "R0", 2.0));
        let out = build(&plays);
        assert!(!out.mixes.iter().any(|m| m.kind == MixKind::RadioFinds), "one was chosen, five left");
    }

    #[test]
    fn order_is_stable_for_the_day() {
        let mut plays = Vec::new();
        for i in 0..12 {
            for d in 0..5 {
                plays.push(play(&format!("Fav {i}"), &format!("A{}", i % 3), 10.0 + f64::from(d)));
            }
        }
        assert_eq!(build(&plays), build(&plays));
    }
}
