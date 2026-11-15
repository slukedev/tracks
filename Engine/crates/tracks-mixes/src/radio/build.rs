//! Tracks Radio from your own music: the songs you play most, the likelier, and the ones you've
//! never played as its new finds, tuned as you set it. A port of the Swift app's
//! `LiveMix.tracksRadio(from:history:…)` for your own music, playing at any hour.

use rustc_hash::{FxHashMap, FxHashSet};
use tracks_model::folded;

use super::mix::{Candidate, LiveMix, REST_PERIOD_MS, Reason};
use super::signals::{SKIP_MEMORY_MS, SkipMemory};
use super::taste::{TasteGraph, TastePlay};
use super::tuning::RadioTuning;

/// A song in your music, or one a server found for you.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RadioTrack {
    pub identity: String,
    pub title: String,
    pub artist_name: String,
    pub genre: Option<String>,
    pub release_year: Option<i32>,
    /// On a server rather than this device: the device's copy comes first.
    pub is_from_server: bool,
}

/// One play of the history, oldest first.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RadioPlay {
    pub identity: String,
    pub artist_name: String,
    pub played_at_ms: i64,
    /// Heard on a station you didn't choose.
    pub is_station: bool,
}

/// What the person has said about songs without saying it outright.
#[derive(Debug, Clone, Copy)]
pub struct RadioSignals<'a> {
    pub skips: &'a SkipMemory,
    /// Songs asked to hear less of.
    pub hidden: &'a FxHashSet<String>,
}

impl RadioSignals<'_> {
    fn excludes(&self, identity: &str, now_ms: i64) -> bool {
        self.hidden.contains(identity) || self.skips.drops(identity, now_ms)
    }
}

/// A song's plays, newest last.
#[derive(Debug, Clone)]
struct Played {
    plays: Vec<i64>,
}

impl Played {
    fn last_heard(&self) -> i64 {
        self.plays.last().copied().unwrap_or(i64::MIN)
    }
}

/// Tracks Radio over `tracks` (your music, then songs your servers found for you), weighted by
/// `plays` and tuned by `tuning`, with what it needs to follow which songs go together.
pub fn tracks_radio(
    plays: &[RadioPlay],
    tracks: &[RadioTrack],
    tuning: &RadioTuning,
    signals: RadioSignals<'_>,
    now_ms: i64,
    seed: u64,
) -> LiveMix {
    let mut played: FxHashMap<&str, Played> = FxHashMap::default();
    for play in plays {
        played
            .entry(play.identity.as_str())
            .or_insert_with(|| Played { plays: Vec::new() })
            .plays
            .push(play.played_at_ms);
    }
    for entry in played.values_mut() {
        entry.plays.sort_unstable();
    }
    played.retain(|identity, _| !signals.excludes(identity, now_ms));
    let your_artists: FxHashSet<String> = plays
        .iter()
        .filter(|p| played.contains_key(p.identity.as_str()))
        .map(|p| folded(&p.artist_name))
        .collect();
    let most_played = most_played_threshold(played.values().map(|p| p.plays.len()));
    let rested = now_ms - REST_PERIOD_MS;

    let mut ordered: Vec<&RadioTrack> = tracks.iter().collect();
    // The device's copy before a server's.
    ordered.sort_by_key(|t| t.is_from_server);
    let mut taken = FxHashSet::default();
    let mut yours = Vec::new();
    let mut finds = Vec::new();
    for track in ordered {
        if !taken.insert(track.identity.as_str()) || signals.excludes(&track.identity, now_ms) {
            continue;
        }
        let genre = track.genre.as_deref().filter(|g| !g.is_empty());
        if let Some(history) = played.get(track.identity.as_str()) {
            let last_heard = history.last_heard();
            let count = history.plays.len();
            let recent_skips = signals.skips.recent(&track.identity, now_ms);
            let recent_plays = history
                .plays
                .iter()
                .filter(|&&p| now_ms - p < SKIP_MEMORY_MS)
                .count();
            let weight = weight(count, last_heard, now_ms)
                * tuning.factor(genre, Some(last_heard), now_ms)
                * freshness(last_heard, now_ms)
                * skip_factor(recent_skips, recent_plays);
            let reason = reason(count, last_heard, genre, tuning, most_played, now_ms);
            yours.push(Candidate::new(
                track.identity.clone(),
                track.title.clone(),
                track.artist_name.clone(),
                weight,
                false,
                last_heard >= rested,
                genre.map(str::to_owned),
                track.release_year,
                reason,
            ));
        } else {
            let reason = if your_artists.contains(&folded(&track.artist_name)) {
                Reason::NewFromYourArtist
            } else {
                Reason::NewFind
            };
            finds.push(Candidate::new(
                track.identity.clone(),
                track.title.clone(),
                track.artist_name.clone(),
                tuning.factor(genre, None, now_ms),
                true,
                false,
                genre.map(str::to_owned),
                track.release_year,
                Some(reason),
            ));
        }
    }
    let share = first_listens(tuning.discovery.new_share(), yours.len(), !finds.is_empty());
    let mut candidates = balanced_by_artist(yours);
    candidates.extend(balanced_by_artist(finds));
    let keeping: FxHashSet<String> = candidates.iter().map(|c| c.identity.clone()).collect();
    let taste_plays: Vec<TastePlay> = plays
        .iter()
        .map(|p| TastePlay {
            identity: p.identity.clone(),
            artist_key: folded(&p.artist_name),
            played_at_ms: p.played_at_ms,
            is_station: p.is_station,
        })
        .collect();
    LiveMix::new(candidates, share, seed).with_taste(TasteGraph::new(
        &taste_plays,
        Some(&keeping),
        now_ms,
    ))
}

/// The share of new finds for a history too small to carry a radio by itself: more of them,
/// so the first listens don't go round the same few songs.
fn first_listens(tuned: f64, your_songs: usize, has_new_finds: bool) -> f64 {
    if !has_new_finds {
        return tuned;
    }
    match your_songs {
        0..15 => tuned.max(0.5),
        15..40 => tuned.max(0.35),
        _ => tuned,
    }
}

/// Each artist's songs made rarer by how many there are, so an artist with fifty songs comes
/// up more than one with a single song, but not fifty times as often.
fn balanced_by_artist(mut candidates: Vec<Candidate>) -> Vec<Candidate> {
    let mut songs: FxHashMap<String, usize> = FxHashMap::default();
    for candidate in &candidates {
        *songs.entry(candidate.artist_key().to_owned()).or_default() += 1;
    }
    for candidate in &mut candidates {
        let count = songs.get(candidate.artist_key()).copied().unwrap_or(1);
        if count > 1 {
            candidate.weight /= (count as f64).sqrt();
        }
    }
    candidates
}

/// The plays it takes to be among the songs you play most: the top twentieth, and at least ten
/// plays, so a small history doesn't call a song played twice a favorite.
fn most_played_threshold(plays: impl Iterator<Item = usize>) -> usize {
    let mut sorted: Vec<usize> = plays.collect();
    if sorted.is_empty() {
        return usize::MAX;
    }
    sorted.sort_unstable_by(|a, b| b.cmp(a));
    10.max(sorted[(sorted.len() - 1).min(sorted.len() / 20)])
}

/// The one thing that stands out about why a song of yours would come up: the genres it's
/// tuned to, an old favorite brought back, or being among your most played.
fn reason(
    plays: usize,
    last_heard_ms: i64,
    genre: Option<&str>,
    tuning: &RadioTuning,
    most_played: usize,
    now_ms: i64,
) -> Option<Reason> {
    if let Some(leaning) = genre.and_then(|g| tuning.leaning(g)) {
        return Some(Reason::LeaningInto(leaning.to_owned()));
    }
    if tuning.is_bringing_back(last_heard_ms, now_ms) {
        return Some(Reason::OldFavorite { last_heard_ms });
    }
    (plays >= most_played).then_some(Reason::MostPlayed)
}

/// How much a song heard lately gives way to others: about half as likely the day after it's
/// heard, back to full over a few days.
fn freshness(last_heard_ms: i64, now_ms: i64) -> f64 {
    let hours = (now_ms - last_heard_ms).max(0) as f64 / 3_600_000.0;
    1.0 - 0.55 * (-hours / 36.0).exp()
}

/// Plays count on a log scale; recency counts for up to two thirds more.
fn weight(plays: usize, last_heard_ms: i64, now_ms: i64) -> f64 {
    let half_life = 120.0 * 86_400_000.0;
    let recency = 0.5_f64.powf((now_ms - last_heard_ms).max(0) as f64 / half_life);
    (1.0 + plays as f64).log2() * (0.6 + 0.4 * recency)
}

/// A skip weighed against the plays around it: one skip of a song played a dozen times lately
/// was the moment, and barely counts. From 0.35 to just under 1.
fn skip_factor(skips: usize, plays: usize) -> f64 {
    if skips == 0 {
        return 1.0;
    }
    let plays = plays as f64 + 1.0;
    (plays / (plays + 1.5 * skips as f64)).max(0.35)
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: i64 = 86_400_000;

    fn track(identity: &str, artist: &str, genre: &str) -> RadioTrack {
        RadioTrack {
            identity: identity.to_owned(),
            title: identity.to_uppercase(),
            artist_name: artist.to_owned(),
            genre: Some(genre.to_owned()),
            release_year: Some(2000),
            is_from_server: false,
        }
    }

    fn play(identity: &str, artist: &str, at: i64) -> RadioPlay {
        RadioPlay {
            identity: identity.to_owned(),
            artist_name: artist.to_owned(),
            played_at_ms: at,
            is_station: false,
        }
    }

    #[test]
    fn played_songs_are_yours_and_the_rest_new_finds() {
        let now = 400 * DAY;
        let tracks = [
            track("a", "Ana", "Rock"),
            track("b", "Ana", "Rock"),
            track("c", "Cy", "Jazz"),
        ];
        let plays: Vec<RadioPlay> = (0..12)
            .map(|i| play("a", "Ana", now - (20 + i) * DAY))
            .collect();
        let skips = SkipMemory::default();
        let hidden = FxHashSet::default();
        let mix = tracks_radio(
            &plays,
            &tracks,
            &RadioTuning::default(),
            RadioSignals {
                skips: &skips,
                hidden: &hidden,
            },
            now,
            1,
        );
        let a = mix.candidate("a").expect("a");
        assert!(!a.is_new);
        assert_eq!(a.reason, Some(Reason::MostPlayed));
        assert_eq!(
            mix.candidate("b").and_then(|c| c.reason.clone()),
            Some(Reason::NewFromYourArtist)
        );
        assert_eq!(
            mix.candidate("c").and_then(|c| c.reason.clone()),
            Some(Reason::NewFind)
        );
        // So few songs of your own: half new finds.
        assert!((mix.new_share() - 0.5).abs() < 1e-9);
    }

    #[test]
    fn hidden_and_often_skipped_songs_stay_out() {
        let tracks = [
            track("a", "A", "Rock"),
            track("b", "B", "Rock"),
            track("c", "C", "Rock"),
        ];
        let mut skips = SkipMemory::default();
        skips.record("b", 0);
        skips.record("b", 1);
        let hidden: FxHashSet<String> = std::iter::once("a".to_owned()).collect();
        let mix = tracks_radio(
            &[],
            &tracks,
            &RadioTuning::default(),
            RadioSignals {
                skips: &skips,
                hidden: &hidden,
            },
            2,
            1,
        );
        let ids: Vec<&str> = mix
            .candidates()
            .iter()
            .map(|c| c.identity.as_str())
            .collect();
        assert_eq!(ids, ["c"]);
    }

    #[test]
    fn the_device_copy_wins_and_leaning_says_so() {
        let mut server = track("a", "A", "Hip-Hop/Rap");
        server.is_from_server = true;
        server.title = "Server".to_owned();
        let tracks = [server, track("a", "A", "Hip-Hop/Rap")];
        let plays = [play("a", "A", 0)];
        let tuning = RadioTuning {
            genres: ["Hip-Hop".to_owned()].into_iter().collect(),
            ..RadioTuning::default()
        };
        let skips = SkipMemory::default();
        let hidden = FxHashSet::default();
        let mix = tracks_radio(
            &plays,
            &tracks,
            &tuning,
            RadioSignals {
                skips: &skips,
                hidden: &hidden,
            },
            DAY,
            1,
        );
        let a = mix.candidate("a").expect("a");
        assert_eq!(a.title, "A");
        assert_eq!(a.reason, Some(Reason::LeaningInto("Hip-Hop".to_owned())));
    }

    #[test]
    fn weights_follow_plays_recency_and_skips() {
        assert!(weight(50, 0, 0) > weight(2, 0, 0));
        assert!(weight(10, 0, 0) > weight(10, 0, 365 * DAY));
        assert!(freshness(0, 3_600_000) < freshness(0, 10 * DAY));
        assert!((skip_factor(0, 3) - 1.0).abs() < 1e-9);
        assert!(skip_factor(1, 12) > skip_factor(1, 0));
        assert!((skip_factor(5, 0) - 0.35).abs() < 1e-9);
    }
}
