//! "Get More": songs and albums you don't own yet, ranked by how much you'd want them.
//!
//! What you play leads. An artist counts by their plays, a play half as telling every
//! `half_life_days` since you last heard them, so the picks follow your taste as it moves.
//! Artists like the ones you play count for less, the closer the likeness the more. An album
//! you already own part of comes before one you have nothing of. Artists are kept apart, and
//! none takes more than `per_artist` places. The same input always gives the same order.

use rustc_hash::FxHashMap;
use tracks_model::{credits, folded};

/// An artist you play.
#[derive(Debug, Clone, PartialEq)]
pub struct ArtistTaste {
    pub name: String,
    pub plays: u32,
    /// When you last heard them, in Unix milliseconds, if known.
    pub last_played_ms: Option<i64>,
}

/// An artist like one you play, as a server or Last.fm says. Listed in order of likeness
/// for each artist: the first is the closest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimilarArtist {
    /// The artist you play.
    pub of: String,
    /// The artist like them.
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CandidateKind {
    Song,
    Album,
}

/// Something you could get.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    /// The host's id for it, handed back in the picks.
    pub id: String,
    pub kind: CandidateKind,
    pub artist: String,
    /// How much of it you own, from 0 to 1: 0 for a song you don't have, a share for an
    /// album you own part of. Anything at 1 is left out, unless it's been asked for.
    pub owned_share: f64,
    /// Already asked for: kept, so its progress stays where it was.
    pub is_requested: bool,
}

/// Why something was picked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reason {
    /// By an artist you play, named as in the taste.
    YourArtist { artist: String },
    /// By an artist like one you play, named as in the taste.
    LikeYourArtist { of: String },
}

#[derive(Debug, Clone, PartialEq)]
pub struct Pick {
    pub id: String,
    pub reason: Reason,
    pub score: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Rules {
    pub limit: usize,
    /// The most places one artist takes.
    pub per_artist: usize,
    pub now_ms: i64,
    /// How many days it takes a play to count half as much. Zero or less: plays never fade.
    pub half_life_days: f64,
}

impl Default for Rules {
    fn default() -> Self {
        Self {
            limit: 24,
            per_artist: 3,
            now_ms: 0,
            half_life_days: 90.0,
        }
    }
}

/// How much a similar artist counts, against the artist they're like.
pub(crate) const SIMILAR_SHARE: f64 = 0.5;
/// Each step down a similar list counts this much of the one before.
pub(crate) const SIMILAR_STEP: f64 = 0.85;
/// An album owned in part counts up to this much more, the more of it owned.
pub(crate) const PARTIAL_LIFT: f64 = 0.5;

/// Ranks what you could get by what you play. See the module's notes for the rules.
pub fn rank(
    candidates: &[Candidate],
    taste: &[ArtistTaste],
    similar: &[SimilarArtist],
    rules: &Rules,
) -> Vec<Pick> {
    let weights = artist_weights(taste, similar, rules);
    let mut scored: Vec<Scored<'_>> = candidates
        .iter()
        .filter(|candidate| candidate.owned_share < 1.0 || candidate.is_requested)
        .filter_map(|candidate| {
            let (artist_key, weight) = weight_of(&weights, &candidate.artist)?;
            let score = weight.value * lift(candidate);
            (score > 0.0 && score.is_finite()).then(|| Scored {
                candidate,
                artist_key,
                score,
                reason: weight.reason.clone(),
            })
        })
        .collect();
    scored.sort_by(|a, b| {
        b.score
            .total_cmp(&a.score)
            .then_with(|| a.candidate.id.cmp(&b.candidate.id))
    });
    scored.dedup_by(|later, earlier| later.candidate.id == earlier.candidate.id);
    spread(scored, rules)
}

struct Weight {
    value: f64,
    reason: Reason,
}

struct Scored<'a> {
    candidate: &'a Candidate,
    artist_key: String,
    score: f64,
    reason: Reason,
}

const DAY_MS: f64 = 24.0 * 60.0 * 60.0 * 1000.0;

/// What each artist counts for, by folded name: the artists you play, then the ones like
/// them, each at the most any of their links gives.
fn artist_weights(
    taste: &[ArtistTaste],
    similar: &[SimilarArtist],
    rules: &Rules,
) -> FxHashMap<String, Weight> {
    let mut weights: FxHashMap<String, Weight> = FxHashMap::default();
    for artist in taste {
        let value = f64::from(artist.plays).ln_1p() * fade(artist.last_played_ms, rules);
        if value <= 0.0 {
            continue;
        }
        offer(&mut weights, folded(&artist.name), value, || {
            Reason::YourArtist {
                artist: artist.name.clone(),
            }
        });
    }
    // Their own weights only: a likeness of a likeness counts for nothing.
    let played: FxHashMap<String, (f64, String)> = weights
        .iter()
        .filter_map(|(key, weight)| match &weight.reason {
            Reason::YourArtist { artist } => Some((key.clone(), (weight.value, artist.clone()))),
            Reason::LikeYourArtist { .. } => None,
        })
        .collect();
    let mut positions: FxHashMap<String, i32> = FxHashMap::default();
    for link in similar {
        let of = folded(&link.of);
        let name = folded(&link.name);
        let Some((value, of_name)) = played.get(&of) else {
            continue;
        };
        let position = positions.entry(of.clone()).or_insert(0);
        let step = SIMILAR_STEP.powi(*position);
        *position += 1;
        if name.is_empty() || name == of {
            continue;
        }
        offer(&mut weights, name, value * SIMILAR_SHARE * step, || {
            Reason::LikeYourArtist {
                of: of_name.clone(),
            }
        });
    }
    weights
}

/// Keeps the higher of an artist's weights; the first offered wins a tie.
fn offer(
    weights: &mut FxHashMap<String, Weight>,
    key: String,
    value: f64,
    reason: impl FnOnce() -> Reason,
) {
    match weights.get_mut(&key) {
        Some(existing) if existing.value >= value => {}
        Some(existing) => {
            *existing = Weight {
                value,
                reason: reason(),
            }
        }
        None => {
            weights.insert(
                key,
                Weight {
                    value,
                    reason: reason(),
                },
            );
        }
    }
}

/// How much a play still counts, from when the artist was last heard.
fn fade(last_played_ms: Option<i64>, rules: &Rules) -> f64 {
    match last_played_ms {
        Some(last) if rules.half_life_days > 0.0 => {
            let days = (rules.now_ms.saturating_sub(last)).max(0) as f64 / DAY_MS;
            0.5_f64.powf(days / rules.half_life_days)
        }
        _ => 1.0,
    }
}

/// An artist's weight, by their whole name, or else the lead artist of a shared credit.
fn weight_of<'a>(
    weights: &'a FxHashMap<String, Weight>,
    artist: &str,
) -> Option<(String, &'a Weight)> {
    let whole = folded(artist);
    if let Some(weight) = weights.get(&whole) {
        return Some((whole, weight));
    }
    let lead = folded(&credits::lead(artist));
    weights.get(&lead).map(|weight| (lead, weight))
}

/// An album owned in part counts for more, the more of it owned.
fn lift(candidate: &Candidate) -> f64 {
    let share = candidate.owned_share;
    if candidate.kind == CandidateKind::Album && share > 0.0 && share < 1.0 {
        1.0 + PARTIAL_LIFT * share
    } else {
        1.0
    }
}

/// Takes the best first, keeping each artist to their share of places and away from the
/// artist just before them where anything else can go there.
fn spread(mut scored: Vec<Scored<'_>>, rules: &Rules) -> Vec<Pick> {
    let mut picks = Vec::with_capacity(rules.limit.min(scored.len()));
    let mut taken: FxHashMap<String, usize> = FxHashMap::default();
    let mut last: Option<String> = None;
    while picks.len() < rules.limit {
        scored.retain(|item| taken.get(&item.artist_key).copied().unwrap_or(0) < rules.per_artist);
        let apart = scored
            .iter()
            .position(|item| last.as_ref() != Some(&item.artist_key));
        let Some(index) = apart.or(if scored.is_empty() { None } else { Some(0) }) else {
            break;
        };
        let item = scored.remove(index);
        *taken.entry(item.artist_key.clone()).or_insert(0) += 1;
        last = Some(item.artist_key);
        picks.push(Pick {
            id: item.candidate.id.clone(),
            reason: item.reason,
            score: item.score,
        });
    }
    picks
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY_MS: i64 = 24 * 60 * 60 * 1000;
    const NOW: i64 = 1_800_000_000_000;

    fn taste(name: &str, plays: u32, days_ago: i64) -> ArtistTaste {
        ArtistTaste {
            name: name.into(),
            plays,
            last_played_ms: Some(NOW - days_ago * DAY_MS),
        }
    }

    fn song(id: &str, artist: &str) -> Candidate {
        Candidate {
            id: id.into(),
            kind: CandidateKind::Song,
            artist: artist.into(),
            owned_share: 0.0,
            is_requested: false,
        }
    }

    fn album(id: &str, artist: &str, owned_share: f64) -> Candidate {
        Candidate {
            id: id.into(),
            kind: CandidateKind::Album,
            artist: artist.into(),
            owned_share,
            is_requested: false,
        }
    }

    fn like(of: &str, name: &str) -> SimilarArtist {
        SimilarArtist {
            of: of.into(),
            name: name.into(),
        }
    }

    fn rules() -> Rules {
        Rules {
            now_ms: NOW,
            ..Rules::default()
        }
    }

    fn ids(picks: &[Pick]) -> Vec<&str> {
        picks.iter().map(|p| p.id.as_str()).collect()
    }

    #[test]
    fn nothing_to_rank_gives_nothing() {
        assert_eq!(
            rank(&[], &[taste("A", 10, 0)], &[], &rules()),
            Vec::<Pick>::new()
        );
    }

    #[test]
    fn no_listening_gives_nothing() {
        assert_eq!(
            rank(&[song("1", "A")], &[], &[], &rules()),
            Vec::<Pick>::new()
        );
    }

    #[test]
    fn leaves_out_artists_unrelated_to_your_listening() {
        let picks = rank(
            &[song("1", "A"), song("2", "Stranger")],
            &[taste("A", 5, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["1"]);
    }

    #[test]
    fn the_artist_you_play_more_comes_first() {
        let picks = rank(
            &[song("few", "B"), song("many", "A")],
            &[taste("A", 50, 0), taste("B", 5, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["many", "few"]);
    }

    #[test]
    fn plays_fade_with_time_since_you_last_heard_them() {
        // Same plays, a year apart: the one heard lately leads.
        let picks = rank(
            &[song("old", "A"), song("new", "B")],
            &[taste("A", 20, 365), taste("B", 20, 1)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["new", "old"]);
    }

    #[test]
    fn plays_never_fade_without_a_half_life() {
        let rules = Rules {
            half_life_days: 0.0,
            ..rules()
        };
        let picks = rank(
            &[song("old", "A"), song("new", "B")],
            &[taste("A", 21, 365), taste("B", 20, 1)],
            &[],
            &rules,
        );
        assert_eq!(ids(&picks), ["old", "new"]);
    }

    #[test]
    fn your_own_artist_comes_before_one_like_them() {
        let picks = rank(
            &[song("like", "C"), song("yours", "A")],
            &[taste("A", 10, 0)],
            &[like("A", "C")],
            &rules(),
        );
        assert_eq!(ids(&picks), ["yours", "like"]);
        assert_eq!(picks[1].reason, Reason::LikeYourArtist { of: "A".into() });
        assert_eq!(picks[0].reason, Reason::YourArtist { artist: "A".into() });
    }

    #[test]
    fn a_closer_likeness_counts_more() {
        let picks = rank(
            &[song("far", "Far"), song("near", "Near")],
            &[taste("A", 10, 0)],
            &[like("A", "Near"), like("A", "Far")],
            &rules(),
        );
        assert_eq!(ids(&picks), ["near", "far"]);
    }

    #[test]
    fn a_similar_artist_counts_through_the_artist_you_play_most() {
        let picks = rank(
            &[song("1", "C")],
            &[taste("A", 2, 0), taste("B", 40, 0)],
            &[like("A", "C"), like("B", "C")],
            &rules(),
        );
        assert_eq!(picks[0].reason, Reason::LikeYourArtist { of: "B".into() });
    }

    #[test]
    fn likeness_to_an_artist_you_dont_play_counts_for_nothing() {
        let picks = rank(
            &[song("1", "C")],
            &[taste("A", 10, 0)],
            &[like("Stranger", "C")],
            &rules(),
        );
        assert_eq!(picks, Vec::<Pick>::new());
    }

    #[test]
    fn artists_match_whatever_their_case_or_accents() {
        let picks = rank(
            &[song("1", "BEYONCE")],
            &[taste("Beyoncé", 10, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["1"]);
    }

    #[test]
    fn a_shared_credit_counts_for_its_lead_artist() {
        let picks = rank(
            &[song("1", "Beyoncé & JAY-Z")],
            &[taste("Beyoncé", 10, 0)],
            &[],
            &rules(),
        );
        assert_eq!(
            picks[0].reason,
            Reason::YourArtist {
                artist: "Beyoncé".into()
            }
        );
    }

    #[test]
    fn leaves_out_what_you_own_entirely() {
        let picks = rank(
            &[album("owned", "A", 1.0), album("half", "A", 0.5)],
            &[taste("A", 10, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["half"]);
    }

    #[test]
    fn keeps_what_youve_asked_for_even_once_it_arrives() {
        let mut arrived = album("arrived", "A", 1.0);
        arrived.is_requested = true;
        let picks = rank(&[arrived], &[taste("A", 10, 0)], &[], &rules());
        assert_eq!(ids(&picks), ["arrived"]);
    }

    #[test]
    fn an_album_you_own_part_of_comes_before_one_you_have_none_of() {
        let picks = rank(
            &[album("none", "A", 0.0), album("some", "A", 0.4)],
            &[taste("A", 10, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["some", "none"]);
    }

    #[test]
    fn no_artist_takes_more_than_their_share_of_places() {
        let candidates: Vec<Candidate> = (0..6).map(|i| song(&format!("a{i}"), "A")).collect();
        let picks = rank(
            &candidates,
            &[taste("A", 10, 0)],
            &[],
            &Rules {
                per_artist: 2,
                ..rules()
            },
        );
        assert_eq!(picks.len(), 2);
    }

    #[test]
    fn keeps_artists_apart_where_it_can() {
        let candidates = [song("a1", "A"), song("a2", "A"), song("b1", "B")];
        let picks = rank(
            &candidates,
            &[taste("A", 100, 0), taste("B", 1, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["a1", "b1", "a2"]);
    }

    #[test]
    fn stops_at_the_limit() {
        let candidates: Vec<Candidate> = (0..10)
            .map(|i| song(&i.to_string(), &format!("Artist {i}")))
            .collect();
        let tastes: Vec<ArtistTaste> = (0..10)
            .map(|i| taste(&format!("Artist {i}"), 10, 0))
            .collect();
        assert_eq!(
            rank(
                &candidates,
                &tastes,
                &[],
                &Rules {
                    limit: 4,
                    ..rules()
                }
            )
            .len(),
            4
        );
    }

    #[test]
    fn equal_scores_are_ordered_by_id() {
        let picks = rank(
            &[song("b", "A"), song("a", "B")],
            &[taste("A", 10, 0), taste("B", 10, 0)],
            &[],
            &rules(),
        );
        assert_eq!(ids(&picks), ["a", "b"]);
    }

    #[test]
    fn scores_go_down_the_list() {
        let candidates = [song("1", "A"), song("2", "B"), song("3", "C")];
        let picks = rank(
            &candidates,
            &[taste("A", 30, 0), taste("B", 20, 0), taste("C", 10, 0)],
            &[],
            &rules(),
        );
        assert!(picks.windows(2).all(|pair| pair[0].score >= pair[1].score));
    }
}
