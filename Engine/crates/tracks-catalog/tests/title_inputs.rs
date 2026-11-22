//! The matcher reads names from the catalog, Lidarr and every music server, so it has to take
//! any text at all. Every short arrangement of the pieces it cuts at is tried, then long
//! strings drawn from a fixed seed, so a failure always comes back the same.

use tracks_catalog::matching::{
    SongName, album_key, artists, best_match, match_album, normalize, song_title,
};

const PIECES: &[&str] = &[
    "(",
    ")",
    "[",
    "]",
    " - ",
    "-",
    " ",
    "remaster",
    "Remastered 2011",
    "feat. ",
    "Live",
    "Deluxe",
    " - single",
    "\u{301}",
    "👩\u{200D}👩\u{200D}👧",
    "\u{200F}",
    "İ",
    "ß",
    "ﬃ",
    "\u{0}",
    "a",
];

fn check(text: &str) {
    let _ = song_title(text);
    let _ = normalize(text);
    let _ = album_key(text);
    let _ = artists(text);
    let candidates = [SongName {
        title: text,
        artist: text,
    }];
    assert_eq!(
        best_match(text, text, &candidates),
        Some(0),
        "{text:?} didn't match itself"
    );
    assert_eq!(
        match_album(text, &[text]),
        Some(0),
        "{text:?} didn't match its own album"
    );
}

#[test]
fn every_short_arrangement_of_the_pieces_is_taken() {
    for a in PIECES {
        check(a);
        for b in PIECES {
            check(&[*a, *b].concat());
            for c in PIECES {
                check(&[*a, *b, *c].concat());
            }
        }
    }
}

/// xorshift64, so the run needs no dependency and repeats exactly.
struct Draws(u64);

impl Draws {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, bound: usize) -> usize {
        usize::try_from(self.next() % bound as u64).unwrap_or(0)
    }

    fn any_char(&mut self) -> char {
        loop {
            let value = u32::try_from(self.next() % 0x11_0000).unwrap_or(0);
            if let Some(found) = char::from_u32(value) {
                return found;
            }
        }
    }
}

#[test]
fn long_mixed_names_are_taken() {
    let mut draws = Draws(0x9E37_79B9_7F4A_7C15);
    for _ in 0..2_000 {
        let mut text = String::new();
        for _ in 0..draws.below(60) {
            if draws.below(3) == 0 {
                text.push(draws.any_char());
            } else {
                text.push_str(PIECES[draws.below(PIECES.len())]);
            }
        }
        check(&text);
    }
}
