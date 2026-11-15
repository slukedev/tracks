//! How Tracks Radio plays: how far it strays from what you know, the genres it leans into, and
//! whether it brings back songs you haven't heard in a while. A port of the Swift app's
//! `RadioTuning`.

use std::collections::BTreeSet;

use rustc_hash::FxHashMap;
use tracks_model::folded;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Discovery {
    /// Songs you love, with a new find now and then.
    Familiar,
    /// About one new find in four.
    #[default]
    Balanced,
    /// Half new finds.
    Adventurous,
}

impl Discovery {
    /// The share of picks that start out as new finds. What you skip moves it from there.
    pub fn new_share(self) -> f64 {
        match self {
            Self::Familiar => 0.08,
            Self::Balanced => 0.25,
            Self::Adventurous => 0.5,
        }
    }
}

/// How long unheard a song must be to count as an old favorite.
const OLD_FAVORITE_AGE_MS: i64 = 90 * 86_400_000;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RadioTuning {
    pub discovery: Discovery,
    /// Genre names to lean into, as the library spells them. Empty leans into nothing.
    pub genres: BTreeSet<String>,
    /// Songs not heard in three months come up more, recent ones a little less.
    pub brings_back_old_favorites: bool,
}

impl RadioTuning {
    /// How much likelier a song is under this tuning. Leaning into genres makes the rest rarer
    /// rather than leaving them out, so the radio never runs dry.
    pub fn factor(&self, genre: Option<&str>, last_heard_ms: Option<i64>, now_ms: i64) -> f64 {
        let mut factor = 1.0;
        if !self.genres.is_empty() {
            factor *= if genre.is_some_and(|g| self.leaning(g).is_some()) {
                4.0
            } else {
                0.2
            };
        }
        if self.brings_back_old_favorites
            && let Some(last_heard) = last_heard_ms
        {
            factor *= if self.is_bringing_back(last_heard, now_ms) {
                2.5
            } else {
                0.7
            };
        }
        factor
    }

    /// Whether a song last heard then is an old favorite this tuning brings back.
    pub fn is_bringing_back(&self, last_heard_ms: i64, now_ms: i64) -> bool {
        self.brings_back_old_favorites && now_ms - last_heard_ms > OLD_FAVORITE_AGE_MS
    }

    /// The genre picked that takes this one, as it was picked: "Hip-Hop" for "Hip-Hop/Rap".
    /// Matched by part and without case.
    pub fn leaning(&self, genre: &str) -> Option<&str> {
        let wanted = folded(genre);
        self.genres
            .iter()
            .find(|picked| {
                let picked = folded(picked);
                wanted.contains(&picked) || picked.contains(&wanted)
            })
            .map(String::as_str)
    }
}

/// The genres to offer, the ones played most first: for each play, the genre of its song, as
/// the library knows it.
pub fn genre_choices<'a>(
    genres_of_plays: impl IntoIterator<Item = Option<&'a str>>,
    limit: usize,
) -> Vec<String> {
    let mut plays: FxHashMap<String, (u32, &str)> = FxHashMap::default();
    let mut folds: FxHashMap<&str, String> = FxHashMap::default();
    for genre in genres_of_plays.into_iter().flatten() {
        if genre.is_empty() || genre == "Music" {
            continue;
        }
        let key = folds.entry(genre).or_insert_with(|| folded(genre)).clone();
        let entry = plays.entry(key).or_insert((0, genre));
        entry.0 += 1;
        entry.1 = genre;
    }
    let mut ranked: Vec<(String, (u32, &str))> = plays.into_iter().collect();
    ranked.sort_by(|a, b| b.1.0.cmp(&a.1.0).then_with(|| a.0.cmp(&b.0)));
    ranked
        .into_iter()
        .take(limit)
        .map(|(_, (_, spelling))| spelling.to_owned())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn leaning_matches_by_part_without_case() {
        let tuning = RadioTuning {
            genres: ["Hip-Hop".to_owned()].into_iter().collect(),
            ..RadioTuning::default()
        };
        assert_eq!(tuning.leaning("Hip-Hop/Rap"), Some("Hip-Hop"));
        assert_eq!(tuning.leaning("hip-hop"), Some("Hip-Hop"));
        assert_eq!(tuning.leaning("Jazz"), None);
        assert!((tuning.factor(Some("Hip-Hop/Rap"), None, 0) - 4.0).abs() < 1e-9);
        assert!((tuning.factor(None, None, 0) - 0.2).abs() < 1e-9);
    }

    #[test]
    fn old_favorites_come_round_more() {
        let tuning = RadioTuning {
            brings_back_old_favorites: true,
            ..RadioTuning::default()
        };
        let now = 200 * 86_400_000;
        assert!((tuning.factor(None, Some(0), now) - 2.5).abs() < 1e-9);
        assert!((tuning.factor(None, Some(now - 86_400_000), now) - 0.7).abs() < 1e-9);
        assert!((tuning.factor(None, None, now) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn genre_choices_rank_by_plays() {
        let plays = [
            Some("Rock"),
            Some("Jazz"),
            Some("rock"),
            None,
            Some("Music"),
            Some(""),
        ];
        assert_eq!(genre_choices(plays, 12), ["rock", "Jazz"]);
        assert_eq!(genre_choices(plays, 1), ["rock"]);
    }
}
