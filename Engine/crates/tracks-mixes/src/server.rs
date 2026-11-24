//! What a music server finds for you: songs by the artists you play and artists like them,
//! found an artist at a time as the shelves are scrolled. A port of the Swift app's
//! `ServerMix` and `ServerDiscovery.ForYou`, so every platform decides the same way.
//!
//! Sans-IO: the host asks its server (a search for the artist, then the server's similar
//! songs, which a server like Octo gets from Last.fm) and checks covers; this decides which
//! artists to go on from, what's left out, and which shelf each song waits for. The state
//! is kept as JSON between launches, so the last picks show straight away.

use rustc_hash::FxHashSet;
use serde::{Deserialize, Serialize};
use tracks_model::folded;

use crate::random::SeededRandom;
use crate::shuffle::fresh_order;

/// How many artists the picks say they start from.
pub const SEED_COUNT: usize = 5;
/// How many artists a walk starts with in its queue; the rest join as they're met.
pub const QUEUE_START: usize = 40;
/// The most artists waiting to be gone on from.
const QUEUE_LIMIT: usize = 200;
/// How long picks stand before they're made again on their own: six hours.
pub const PICKS_LAST_MS: i64 = 6 * 60 * 60 * 1000;
/// Of an artist's own songs, how many come before the songs like them.
const OWN_SONGS: usize = 4;
/// Of your songs by an artist gone on from, how many join the picks.
const YOURS: usize = 3;

/// The artists picks start from. What you play leads, so the picks follow your taste as it
/// moves. The artists you picked fill what your listening can't yet, and once it can, keep
/// one place. Past the three you play most, which of the rest take the remaining places
/// changes with `rotation`, so the picks wander around your taste rather than sitting on its
/// top. Your library's artists fill anything left. Each artist once, however it's cased or
/// accented.
///
/// `played` is the artists you play, most first.
pub fn seeds(
    picked: &[String],
    played: &[String],
    library: &[String],
    limit: usize,
    rotation: u64,
) -> Vec<String> {
    let played = unique(played.iter());
    let picked = unique(picked.iter());
    let picked_places = if picked.is_empty() {
        0
    } else {
        limit.saturating_sub(played.len()).max(1)
    };
    let mut rest: Vec<String> = played.iter().skip(3).take(10).cloned().collect();
    SeededRandom::new(rotation).shuffle(&mut rest);
    let mut ordered = unique(
        played
            .iter()
            .take(3)
            .chain(picked.iter().take(picked_places))
            .chain(rest.iter())
            .chain(picked.iter())
            .chain(library.iter()),
    );
    ordered.truncate(limit);
    ordered
}

fn unique<'a>(names: impl Iterator<Item = &'a String>) -> Vec<String> {
    let mut seen = FxHashSet::default();
    names
        .filter(|name| !name.trim().is_empty() && seen.insert(folded(name)))
        .cloned()
        .collect()
}

/// Songs you have and new ones woven together, so a list shows both from its first rows:
/// about one of yours for every two new (`owned_share` of a third), as long as both last,
/// then whatever's left. Each list keeps its own order.
pub fn blend<T: Clone>(owned: &[T], new: &[T], owned_share: f64) -> Vec<T> {
    if owned.is_empty() || new.is_empty() {
        return owned.iter().chain(new).cloned().collect();
    }
    let share = owned_share.clamp(0.05, 0.95);
    let mut result = Vec::with_capacity(owned.len() + new.len());
    let (mut o, mut n) = (0, 0);
    while o < owned.len() || n < new.len() {
        let placed = o as f64 / (o + n).max(1) as f64;
        if o < owned.len() && (n >= new.len() || placed <= share) {
            result.push(owned[o].clone());
            o += 1;
        } else {
            result.push(new[n].clone());
            n += 1;
        }
    }
    result
}

/// A song on the server, as the shelves show it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FoundSong {
    /// The server's id for it.
    pub id: String,
    /// The identity history and the library group it on.
    pub identity: String,
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub album: Option<String>,
    /// The server's cover id.
    #[serde(default)]
    pub cover: Option<String>,
    #[serde(default)]
    pub duration_ms: Option<u64>,
}

/// A song a server found, with what the host knows of it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub song: FoundSong,
    /// You've played it: it's left out.
    pub heard: bool,
    /// By an artist you play or picked: it waits for Picked for You, and its artist isn't
    /// queued to go on from.
    pub known_artist: bool,
    /// In your music already.
    pub owned: bool,
}

/// Which list a song goes to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Shelf {
    /// Picked for You: songs by artists you play or picked, yours and new ones together.
    Picks,
    /// Suggested Songs: songs by artists new to you.
    Suggested,
    /// Keep Exploring's: more by artists new to you, none of them on the shelves.
    Further,
}

/// Where to start from an artist's search results: up to four of the artist's own songs, and
/// the song to ask for songs like (their first, or the first found).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArtistStart {
    pub theirs: Vec<usize>,
    pub start: Option<usize>,
}

/// From a search for `artist`, which songs to keep and which to go on from.
pub fn artist_start(artist: &str, found: &[FoundSong]) -> ArtistStart {
    let key = folded(artist);
    let all: Vec<usize> = (0..found.len())
        .filter(|&i| folded(&found[i].artist) == key)
        .collect();
    let start = all
        .first()
        .copied()
        .or(if found.is_empty() { None } else { Some(0) });
    ArtistStart {
        theirs: all.into_iter().take(OWN_SONGS).collect(),
        start,
    }
}

/// What one server finds for you, endlessly.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct ForYou {
    pub songs: Vec<FoundSong>,
    pub suggested: Vec<FoundSong>,
    pub further: Vec<FoundSong>,
    /// The artists it started from.
    pub from: Vec<String>,
    /// Artists still to go on from: the ones it started from, then new ones met on the way.
    pub queue: Vec<String>,
    /// Artists gone on from already, folded.
    pub asked: Vec<String>,
    /// Found for Picked for You, not shown yet: their covers are checked only as the shelf
    /// needs more, so a song never scrolled to costs the server nothing more.
    pub waiting_picks: Vec<FoundSong>,
    /// The same for Suggested Songs and Keep Exploring, which share them.
    pub waiting_others: Vec<FoundSong>,
    pub loaded_at_ms: i64,
    /// The artists you'd picked when it was made: changing them makes it again.
    pub picked: Option<Vec<String>>,
}

impl ForYou {
    /// A walk starting from `seeds`, nothing found yet.
    pub fn start(seeds: &[String], picked: &[String], now_ms: i64) -> Self {
        Self {
            from: seeds.iter().take(SEED_COUNT).cloned().collect(),
            queue: seeds.to_vec(),
            loaded_at_ms: now_ms,
            picked: Some(picked.to_vec()),
            ..Self::default()
        }
    }

    /// Whether there's more to find: songs waiting to be shown, or artists to go on from.
    pub fn can_go_on(&self) -> bool {
        !self.queue.is_empty() || !self.waiting_picks.is_empty() || !self.waiting_others.is_empty()
    }

    /// Whether these picks still stand: they have songs, were made from the same picked
    /// artists, and are less than six hours old. Not remade for every song played, which would
    /// ask the server for dozens of songs and covers every few minutes.
    pub fn stands(&self, picked: &[String], now_ms: i64) -> bool {
        (!self.songs.is_empty() || !self.suggested.is_empty())
            && self.picked.as_deref().unwrap_or(&[]) == picked
            && now_ms - self.loaded_at_ms < PICKS_LAST_MS
    }

    /// The next artist not yet gone on from, marked as gone on from.
    pub fn next_artist(&mut self) -> Option<String> {
        while !self.queue.is_empty() {
            let next = self.queue.remove(0);
            let key = folded(&next);
            if !self.asked.contains(&key) {
                self.asked.push(key);
                return Some(next);
            }
        }
        None
    }

    /// Adds what was found going on from `artist` to the waiting lists, leaving out songs
    /// you've heard and ones already here, and queues the new artists among them. A few of
    /// your own unheard songs by the artist (`yours`) join the picks, so what you have comes
    /// up with what's new. Songs by artists you know wait for Picked for You, yours and new
    /// woven together; the rest wait for Suggested Songs and Keep Exploring, spread so no
    /// artist comes twice running, in an order that holds for `order`.
    pub fn add(&mut self, found: &[Candidate], yours: &[FoundSong], order: u64) {
        let mut seen: FxHashSet<String> = self
            .songs
            .iter()
            .chain(&self.suggested)
            .chain(&self.further)
            .chain(&self.waiting_picks)
            .chain(&self.waiting_others)
            .map(|s| s.identity.clone())
            .collect();
        let candidates: Vec<&Candidate> = found
            .iter()
            .filter(|c| !c.heard && seen.insert(c.song.identity.clone()))
            .collect();
        let yours: Vec<FoundSong> = yours
            .iter()
            .filter(|s| seen.insert(s.identity.clone()))
            .take(YOURS)
            .cloned()
            .collect();

        for candidate in candidates.iter().filter(|c| !c.known_artist) {
            let key = folded(&candidate.song.artist);
            if self.queue.len() < QUEUE_LIMIT
                && !self.asked.contains(&key)
                && !self.queue.iter().any(|queued| folded(queued) == key)
            {
                self.queue.push(candidate.song.artist.clone());
            }
        }

        let mut owned = yours;
        let mut new = Vec::new();
        let mut discovered = Vec::new();
        for candidate in candidates {
            match (candidate.known_artist, candidate.owned) {
                (true, true) => owned.push(candidate.song.clone()),
                (true, false) => new.push(candidate.song.clone()),
                (false, _) => discovered.push(candidate.song.clone()),
            }
        }
        self.waiting_picks.extend(blend(&owned, &new, 1.0 / 3.0));
        self.waiting_others.extend(fresh_order(
            &discovered,
            |s| folded(&s.artist),
            |_| false,
            order,
        ));
    }

    /// Takes up to `want` songs waiting for `shelf`, for the host to check their covers and
    /// [`place`](Self::place) the ones that have them.
    pub fn take_waiting(&mut self, list: Shelf, want: usize) -> Vec<FoundSong> {
        let waiting = match list {
            Shelf::Picks => &mut self.waiting_picks,
            Shelf::Suggested | Shelf::Further => &mut self.waiting_others,
        };
        waiting.drain(..want.min(waiting.len())).collect()
    }

    /// Shows songs on a shelf.
    pub fn place(&mut self, list: Shelf, songs: Vec<FoundSong>) {
        match list {
            Shelf::Picks => self.songs.extend(songs),
            Shelf::Suggested => self.suggested.extend(songs),
            Shelf::Further => self.further.extend(songs),
        }
    }

    pub fn waiting(&self, list: Shelf) -> usize {
        match list {
            Shelf::Picks => self.waiting_picks.len(),
            Shelf::Suggested | Shelf::Further => self.waiting_others.len(),
        }
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    /// Picks kept from last time; None if they can't be read.
    pub fn from_json(json: &str) -> Option<Self> {
        serde_json::from_str(json).ok()
    }
}

/// How many songs a shelf going on asks for: enough to fill the column it's on, or the next
/// one whole, so it only ever ends on whole columns while it goes on.
pub fn wanted(showing: usize, per_column: usize) -> usize {
    let per_column = per_column.max(1);
    per_column - showing % per_column
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    fn song(title: &str, artist: &str) -> FoundSong {
        FoundSong {
            id: format!("{title}-{artist}"),
            identity: format!("{}\u{1F}{}", title.to_lowercase(), artist.to_lowercase()),
            title: title.into(),
            artist: artist.into(),
            album: None,
            cover: None,
            duration_ms: None,
        }
    }

    fn found(title: &str, artist: &str, known: bool) -> Candidate {
        Candidate {
            song: song(title, artist),
            heard: false,
            known_artist: known,
            owned: false,
        }
    }

    #[test]
    fn seeds_lead_with_what_you_play_and_keep_a_place_for_your_picks() {
        let played = names(&["Radiohead", "Björk", "Portishead", "Muse", "Blur"]);
        let picked = names(&["Massive Attack", "Bjork"]);
        let library = names(&["Air", "radiohead"]);
        let seeds = seeds(&picked, &played, &library, 5, 0);
        assert_eq!(
            seeds[..3],
            names(&["Radiohead", "Björk", "Portishead"]),
            "your top three lead"
        );
        assert_eq!(
            seeds[3], "Massive Attack",
            "a picked artist keeps one place"
        );
        assert_eq!(seeds.len(), 5);

        let empty = self::seeds(&picked, &[], &library, 5, 0);
        assert_eq!(
            empty,
            names(&["Massive Attack", "Bjork", "Air", "radiohead"]),
            "picks, then the library, each once"
        );
        assert_eq!(self::seeds(&[], &[], &[], 5, 0), Vec::<String>::new());
    }

    #[test]
    fn rotation_moves_only_the_artists_past_your_top_three() {
        let played: Vec<String> = (0..13).map(|i| format!("Artist {i}")).collect();
        let a = seeds(&[], &played, &[], 6, 1);
        let b = seeds(&[], &played, &[], 6, 2);
        assert_eq!(a[..3], b[..3]);
        assert_ne!(a[3..], b[3..]);
    }

    #[test]
    fn blending_puts_one_of_yours_among_every_two_new() {
        let owned = [1, 2, 3];
        let new = [10, 20, 30, 40, 50, 60];
        assert_eq!(
            blend(&owned, &new, 1.0 / 3.0),
            [1, 10, 20, 2, 30, 40, 3, 50, 60]
        );
        assert_eq!(blend(&[] as &[i32], &new, 0.3), new);
    }

    #[test]
    fn going_on_skips_artists_already_gone_on_from() {
        let mut feed = ForYou::start(&names(&["Radiohead", "radiohead", "Blur"]), &[], 0);
        assert_eq!(feed.from, names(&["Radiohead", "radiohead", "Blur"]));
        assert_eq!(feed.next_artist().as_deref(), Some("Radiohead"));
        assert_eq!(
            feed.next_artist().as_deref(),
            Some("Blur"),
            "the same artist cased differently isn't asked twice"
        );
        assert_eq!(feed.next_artist(), None);
        assert!(!feed.can_go_on());
    }

    #[test]
    fn songs_go_to_the_shelf_their_artist_belongs_on() {
        let mut feed = ForYou::start(&names(&["Radiohead"]), &[], 0);
        feed.next_artist();
        let mut heard = found("Creep", "Radiohead", true);
        heard.heard = true;
        let mut owned = found("Airbag", "Radiohead", true);
        owned.owned = true;
        let list = [
            heard,
            found("Let Down", "Radiohead", true),
            owned,
            found("Teardrop", "Massive Attack", false),
            found("Teardrop", "Massive Attack", false),
            found("Glory Box", "Portishead", false),
        ];
        feed.add(
            &list,
            &[song("Lucky", "Radiohead"), song("Let Down", "Radiohead")],
            7,
        );
        let titles = |list: &[FoundSong]| list.iter().map(|s| s.title.clone()).collect::<Vec<_>>();
        assert_eq!(
            titles(&feed.waiting_picks),
            ["Lucky", "Let Down", "Airbag"],
            "yours first, woven with new"
        );
        assert_eq!(
            feed.waiting_others.len(),
            2,
            "heard songs and repeats are left out"
        );
        assert_eq!(
            feed.queue,
            names(&["Massive Attack", "Portishead"]),
            "new artists are queued, once"
        );

        let shown = feed.take_waiting(Shelf::Suggested, 1);
        feed.place(Shelf::Suggested, shown);
        assert_eq!(feed.suggested.len(), 1);
        assert_eq!(feed.waiting(Shelf::Suggested), 1);
        feed.add(&[found("Glory Box", "Portishead", false)], &[], 7);
        assert_eq!(
            feed.waiting(Shelf::Suggested) + feed.suggested.len(),
            2,
            "a song shown already isn't found again"
        );
    }

    #[test]
    fn picks_stand_for_six_hours_unless_your_picked_artists_change() {
        let mut feed = ForYou::start(&names(&["Radiohead"]), &names(&["Air"]), 1_000);
        assert!(!feed.stands(&names(&["Air"]), 1_000), "nothing found yet");
        feed.place(Shelf::Picks, vec![song("Let Down", "Radiohead")]);
        assert!(feed.stands(&names(&["Air"]), 1_000 + PICKS_LAST_MS - 1));
        assert!(!feed.stands(&names(&["Air"]), 1_000 + PICKS_LAST_MS));
        assert!(!feed.stands(&[], 1_000));
        let kept = ForYou::from_json(&feed.to_json()).expect("readable");
        assert_eq!(kept, feed);
        assert_eq!(ForYou::from_json("not json"), None);
    }

    #[test]
    fn an_artists_own_songs_come_first_and_the_first_leads_on() {
        let list = [
            song("Teardrop", "Massive Attack"),
            song("Creep", "Radiohead"),
            song("Lucky", "radiohead"),
        ];
        assert_eq!(
            artist_start("Radiohead", &list),
            ArtistStart {
                theirs: vec![1, 2],
                start: Some(1)
            }
        );
        assert_eq!(
            artist_start("Blur", &list),
            ArtistStart {
                theirs: vec![],
                start: Some(0)
            }
        );
        assert_eq!(
            artist_start("Blur", &[]),
            ArtistStart {
                theirs: vec![],
                start: None
            }
        );
    }

    #[test]
    fn a_shelf_asks_for_whole_columns() {
        assert_eq!(wanted(0, 4), 4);
        assert_eq!(wanted(6, 4), 2);
        assert_eq!(wanted(8, 4), 4);
    }
}
