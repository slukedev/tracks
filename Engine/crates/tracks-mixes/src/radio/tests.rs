//! The Swift app's `RadioSteeringTests`, `RadioProbeTests` and `RadioListenTests`, ported, so
//! both radios are held to the same behavior.

use rustc_hash::FxHashSet;

use super::*;

fn song(
    title: &str,
    artist: &str,
    weight: f64,
    is_new: bool,
    genre: Option<&str>,
    year: Option<i32>,
) -> Candidate {
    Candidate::new(
        format!("{}\u{1f}{}", title.to_lowercase(), artist.to_lowercase()),
        title.to_owned(),
        artist.to_owned(),
        weight,
        is_new,
        false,
        genre.map(str::to_owned),
        year,
        None,
    )
}

/// 60 songs: three genres, twenty artists, two decades, none new.
fn steering_candidates() -> Vec<Candidate> {
    (0..60)
        .map(|i| {
            let genre = ["Hip-Hop/Rap", "Country", "Alternative"][i % 3];
            song(
                &format!("S{i}"),
                &format!("Artist {}", i % 20),
                1.0,
                false,
                Some(genre),
                Some(if i < 30 { 1985 } else { 2015 }),
            )
        })
        .collect()
}

fn of_genre(candidates: &[Candidate], genre: &str, index: usize) -> String {
    candidates
        .iter()
        .filter(|c| c.genre.as_deref() == Some(genre))
        .nth(index)
        .expect("a song in the genre")
        .identity
        .clone()
}

fn genre_of(mix: &LiveMix, identity: &str) -> Option<String> {
    mix.candidate(identity).and_then(|c| c.genre.clone())
}

// MARK: - Steering after skips

#[test]
fn two_skips_in_a_row_in_one_genre_keep_it_out_of_the_next_few_picks() {
    let candidates = steering_candidates();
    for seed in 1..=30 {
        let mut mix = LiveMix::new(candidates.clone(), 0.2, seed);
        mix.note_skipped(&of_genre(&candidates, "Country", 0));
        mix.note_skipped(&of_genre(&candidates, "Country", 1));
        assert_eq!(
            mix.steering(),
            Some(&Steering::AwayFromGenre("Country".to_owned()))
        );
        for _ in 0..5 {
            let pick = mix.next().expect("a pick");
            assert_ne!(
                genre_of(&mix, &pick).as_deref(),
                Some("Country"),
                "seed {seed}"
            );
        }
    }
}

#[test]
fn a_genre_skipped_in_a_run_comes_back_once_the_run_is_behind_it() {
    let candidates = steering_candidates();
    let mut mix = LiveMix::new(candidates.clone(), 0.2, 7);
    mix.note_skipped(&of_genre(&candidates, "Country", 0));
    mix.note_skipped(&of_genre(&candidates, "Country", 1));
    let later: Vec<String> = (0..40).filter_map(|_| mix.next()).skip(5).collect();
    assert!(
        later
            .iter()
            .any(|p| genre_of(&mix, p).as_deref() == Some("Country"))
    );
}

#[test]
fn two_skips_from_one_decade_steer_away_from_it() {
    let candidates = steering_candidates();
    for seed in 1..=20 {
        let mut mix = LiveMix::new(candidates.clone(), 0.2, seed);
        // S0 is Hip-Hop, S1 Country: both 1985.
        mix.note_skipped(&candidates[0].identity);
        mix.note_skipped(&candidates[1].identity);
        assert_eq!(mix.steering(), Some(&Steering::AwayFromDecade(1980)));
        for _ in 0..5 {
            let pick = mix.next().expect("a pick");
            assert_eq!(
                mix.candidate(&pick).and_then(|c| c.release_year),
                Some(2015)
            );
        }
    }
}

#[test]
fn skips_with_nothing_in_common_try_genres_the_run_did_not_have() {
    let mixed: Vec<Candidate> = (0..40)
        .map(|i| {
            song(
                &format!("M{i}"),
                &format!("Band {i}"),
                1.0,
                false,
                Some(["Pop", "Jazz", "Metal", "Folk"][i % 4]),
                Some(1960 + i as i32 * 10),
            )
        })
        .collect();
    let mut mix = LiveMix::new(mixed.clone(), 0.2, 3);
    mix.note_skipped(&mixed[0].identity);
    mix.note_skipped(&mixed[1].identity);
    assert_eq!(
        mix.steering(),
        None,
        "two different genres and decades aren't a run"
    );
    mix.note_skipped(&mixed[2].identity);
    assert_eq!(mix.steering(), Some(&Steering::TryingSomethingElse));
    let pick = mix.next().expect("a pick");
    assert_eq!(genre_of(&mix, &pick).as_deref(), Some("Folk"));
}

#[test]
fn the_song_let_play_after_a_run_is_the_one_it_homes_in_on() {
    let candidates = steering_candidates();
    let run = |mix: &mut LiveMix| {
        mix.note_skipped(&of_genre(&candidates, "Country", 0));
        mix.note_skipped(&of_genre(&candidates, "Country", 1));
        mix.note_finished(&of_genre(&candidates, "Alternative", 0));
    };
    let mut mix = LiveMix::new(candidates.clone(), 0.2, 11);
    run(&mut mix);
    assert_eq!(
        mix.steering(),
        Some(&Steering::TowardGenre("Alternative".to_owned()))
    );

    let mut draws = 0;
    for seed in 1..=60 {
        let mut fresh = LiveMix::new(candidates.clone(), 0.2, seed);
        run(&mut fresh);
        let picks: Vec<String> = (0..6).filter_map(|_| fresh.next()).collect();
        draws += picks
            .iter()
            .filter(|p| genre_of(&fresh, p).as_deref() == Some("Alternative"))
            .count();
    }
    // Even would be half of what isn't Country; homing in makes it well over half.
    assert!(draws > 60 * 6 * 55 / 100, "{draws}");
}

#[test]
fn after_a_run_a_pick_in_the_genre_let_play_says_it_is_more_of_it() {
    let candidates = steering_candidates();
    let mut mix = LiveMix::new(candidates.clone(), 0.2, 4);
    mix.note_skipped(&of_genre(&candidates, "Country", 0));
    mix.note_skipped(&of_genre(&candidates, "Country", 1));
    mix.note_finished(&of_genre(&candidates, "Alternative", 0));
    let picks: Vec<String> = (0..4).filter_map(|_| mix.next()).collect();
    let alternative = picks
        .iter()
        .find(|p| genre_of(&mix, p).as_deref() == Some("Alternative"))
        .expect("an Alternative pick");
    assert_eq!(
        mix.reason(alternative),
        Some(Reason::MoreOf("Alternative".to_owned()))
    );
}

#[test]
fn two_new_finds_skipped_in_a_row_make_new_finds_rarer() {
    let mut candidates: Vec<Candidate> = (0..20)
        .map(|i| song(&format!("Y{i}"), &format!("Y{i}"), 1.0, false, None, None))
        .collect();
    let new: Vec<Candidate> = (0..20)
        .map(|i| song(&format!("N{i}"), &format!("N{i}"), 1.0, true, None, None))
        .collect();
    candidates.extend(new.clone());
    let mut mix = LiveMix::new(candidates, 0.4, 2);
    mix.note_skipped(&new[0].identity);
    let after_one = mix.new_share();
    mix.note_skipped(&new[1].identity);
    assert_eq!(mix.steering(), Some(&Steering::FewerNewFinds));
    assert!(mix.new_share() < after_one * 0.7);
}

#[test]
fn loving_a_song_steers_toward_its_artist_and_says_so() {
    let candidates = steering_candidates();
    let mut mix = LiveMix::new(candidates.clone(), 0.2, 5);
    mix.note_loved(&candidates[4].identity);
    assert_eq!(
        mix.steering(),
        Some(&Steering::TowardArtist("Artist 4".to_owned()))
    );
    let loved = candidates[4].identity.clone();
    let theirs = mix
        .next_where(None, &|c| {
            c.artist_name == "Artist 4" && c.identity != loved
        })
        .expect("another of their songs");
    assert_eq!(
        mix.reason(&theirs),
        Some(Reason::MoreLike("Artist 4".to_owned()))
    );
}

#[test]
fn retuning_keeps_what_the_listen_has_learned() {
    let candidates = steering_candidates();
    let mut mix = LiveMix::new(candidates.clone(), 0.2, 1);
    mix.note_skipped(&of_genre(&candidates, "Country", 0));
    mix.note_skipped(&of_genre(&candidates, "Country", 1));
    let mut retuned = LiveMix::new(candidates, 0.2, 2);
    retuned.continue_listen(&mix);
    assert_eq!(
        retuned.steering(),
        Some(&Steering::AwayFromGenre("Country".to_owned()))
    );
    let pick = retuned.next().expect("a pick");
    assert_ne!(genre_of(&retuned, &pick).as_deref(), Some("Country"));
}

#[test]
fn retuning_keeps_how_far_skipped_new_finds_moved_the_share() {
    let mut candidates = steering_candidates();
    let finds: Vec<Candidate> = (0..10)
        .map(|i| {
            song(
                &format!("New {i}"),
                &format!("Newcomer {i}"),
                1.0,
                true,
                None,
                None,
            )
        })
        .collect();
    candidates.extend(finds.clone());
    let mut mix = LiveMix::new(candidates.clone(), 0.3, 1);
    mix.note_skipped(&finds[0].identity);
    assert!(mix.new_share() < 0.3);

    let mut same = LiveMix::new(candidates.clone(), 0.3, 2);
    same.continue_listen(&mix);
    assert!((same.new_share() - mix.new_share()).abs() < 1e-4);

    let mut bolder = LiveMix::new(candidates.clone(), 0.5, 2);
    bolder.continue_listen(&mix);
    assert!(bolder.new_share() > same.new_share());
    assert!(bolder.new_share() < 0.5);

    let untouched = LiveMix::new(candidates.clone(), 0.2, 1);
    let mut retuned = LiveMix::new(candidates, 0.45, 2);
    retuned.continue_listen(&untouched);
    assert!((retuned.new_share() - 0.45).abs() < f64::EPSILON);
}

#[test]
fn picking_only_playable_songs_goes_round_them_and_gives_nothing_when_none_can() {
    let candidates = steering_candidates();
    let playable: FxHashSet<String> = candidates
        .iter()
        .take(4)
        .map(|c| c.identity.clone())
        .collect();
    let mut mix = LiveMix::new(candidates, 0.2, 3);
    let heard: Vec<String> = (0..12)
        .map(|_| {
            mix.next_playable(&|c| playable.contains(&c.identity))
                .expect("a pick")
        })
        .collect();
    assert_eq!(heard.iter().cloned().collect::<FxHashSet<_>>(), playable);
    assert!(heard.windows(2).all(|w| w[0] != w[1]));
    assert_eq!(mix.next_playable(&|_| false), None);
}

#[test]
fn genre_spellings_fold_into_one_family() {
    assert_eq!(genre_key("Hip-Hop/Rap").as_deref(), Some("hip-hop"));
    assert_eq!(genre_key("Hip-Hop").as_deref(), Some("hip-hop"));
    assert_eq!(genre_key("R&B/Soul").as_deref(), Some("r&b"));
    assert_eq!(genre_key("Singer/Songwriter").as_deref(), Some("singer"));
    assert_eq!(genre_key("hip hop").as_deref(), Some("hip-hop"));
    assert_eq!(genre_key("Music"), None);
}

// MARK: - Listens

#[test]
fn when_a_song_gave_way_tells_a_skip_a_move_on_and_a_song_let_play() {
    let cases = [
        (10_000, Some(200_000), Listen::Skipped),
        (60_000, Some(200_000), Listen::MovedOn),
        (130_000, Some(200_000), Listen::Finished),
        (160_000, Some(200_000), Listen::Finished),
        (10_000, None, Listen::Finished),
    ];
    for (played, duration, listen) in cases {
        assert_eq!(
            Listen::from_played(played, duration),
            listen,
            "{played} of {duration:?}"
        );
    }
}

#[test]
fn songs_moved_on_from_make_a_run_beside_a_real_skip_or_once_there_are_three() {
    let candidates = steering_candidates();
    let country = |i| of_genre(&candidates, "Country", i);
    let mut mix = LiveMix::new(candidates.clone(), 0.2, 1);
    mix.note_moved_on(&country(0));
    mix.note_moved_on(&country(1));
    assert_eq!(mix.steering(), None);
    mix.note_moved_on(&country(2));
    assert_eq!(
        mix.steering(),
        Some(&Steering::AwayFromGenre("Country".to_owned()))
    );

    let mut beside = LiveMix::new(candidates.clone(), 0.2, 1);
    beside.note_moved_on(&country(0));
    beside.note_skipped(&country(1));
    assert_eq!(
        beside.steering(),
        Some(&Steering::AwayFromGenre("Country".to_owned()))
    );
}

#[test]
fn an_artist_with_many_songs_is_spaced_out() {
    let mut candidates: Vec<Candidate> = (0..40)
        .map(|i| song(&format!("Big {i}"), "Big", 1.0, false, None, None))
        .collect();
    candidates.extend((0..40).map(|i| {
        song(
            &format!("Other {i}"),
            &format!("Other {i}"),
            1.0,
            false,
            None,
            None,
        )
    }));
    let mut mix = LiveMix::new(candidates, 0.0, 9);
    let picks: Vec<String> = (0..40).filter_map(|_| mix.next()).collect();
    let artists: Vec<&str> = picks
        .iter()
        .map(|p| mix.candidate(p).map_or("", |c| c.artist_name.as_str()))
        .collect();
    assert!(
        artists
            .windows(2)
            .all(|w| !(w[0] == "Big" && w[1] == "Big"))
    );
    assert!(
        artists
            .windows(3)
            .all(|w| !(w[0] == "Big" && w[2] == "Big"))
    );
}

#[test]
fn a_listen_opens_on_one_of_your_strongest_songs_never_a_new_find() {
    let mut candidates: Vec<Candidate> = (0..10)
        .map(|i| {
            song(
                &format!("Y{i}"),
                &format!("Y{i}"),
                if i == 0 { 20.0 } else { 1.0 },
                false,
                None,
                None,
            )
        })
        .collect();
    candidates
        .extend((0..30).map(|i| song(&format!("N{i}"), &format!("N{i}"), 5.0, true, None, None)));
    let strongest = candidates[0].identity.clone();
    let mut strongest_opens = 0;
    for seed in 1..=40 {
        let mut mix = LiveMix::new(candidates.clone(), 0.9, seed);
        let opener = mix.opener(&|_| true).expect("an opener");
        assert!(mix.candidate(&opener).is_some_and(|c| !c.is_new));
        if opener == strongest {
            strongest_opens += 1;
        }
    }
    // Its share of weight is 20 / 29; squared it's far more.
    assert!(strongest_opens > 32, "{strongest_opens}");
}

#[test]
fn a_new_find_says_so_and_a_song_come_round_again_says_why_now() {
    let candidates = vec![
        song("A", "A", 1.0, false, None, None),
        song("B", "B", 1.0, true, None, None),
    ];
    let mut mix = LiveMix::new(candidates.clone(), 0.5, 1);
    assert_eq!(mix.reason(&candidates[1].identity), Some(Reason::NewFind));
    assert_eq!(mix.reason(&candidates[0].identity), None);
    mix.note_picked(&candidates[0].identity);
    mix.release(&candidates[0].identity);
    assert!(!mix.has_picked(&candidates[0].identity));
}

#[test]
fn a_share_tuned_below_the_floor_is_never_raised_by_a_skip() {
    let mut candidates: Vec<Candidate> = (0..20)
        .map(|i| song(&format!("Y{i}"), &format!("Y{i}"), 1.0, false, None, None))
        .collect();
    let new: Vec<Candidate> = (0..20)
        .map(|i| song(&format!("N{i}"), &format!("N{i}"), 1.0, true, None, None))
        .collect();
    candidates.extend(new.clone());
    let mut mix = LiveMix::new(candidates, 0.02, 1);
    mix.note_skipped(&new[0].identity);
    assert!(mix.new_share() <= 0.02);
}

#[test]
fn songs_that_go_with_one_let_play_come_up_and_say_why() {
    let candidates: Vec<Candidate> = (0..30)
        .map(|i| {
            song(
                &format!("T{i}"),
                &format!("Artist {i}"),
                1.0,
                false,
                None,
                None,
            )
        })
        .collect();
    // T0 and T1 always played together.
    let mut plays = Vec::new();
    for day in 0..8 {
        for (offset, index) in [(0, 0), (3, 1)] {
            plays.push(TastePlay {
                identity: candidates[index].identity.clone(),
                artist_key: candidates[index].artist_key().to_owned(),
                played_at_ms: day * 86_400_000 + offset * 60_000,
                is_station: false,
            });
        }
    }
    let graph = TasteGraph::new(&plays, None, 8 * 86_400_000);
    let mut together = 0;
    for seed in 1..=40 {
        let mut mix = LiveMix::new(candidates.clone(), 0.0, seed).with_taste(graph.clone());
        mix.note_picked(&candidates[0].identity);
        mix.note_finished(&candidates[0].identity);
        let next = mix.next().expect("a pick");
        if next == candidates[1].identity {
            together += 1;
            assert_eq!(mix.reason(&next), Some(Reason::GoesWith("T0".to_owned())));
        }
    }
    // Against one in 29 by chance.
    assert!(together > 15, "{together}");
}

// MARK: - The probe

/// Seven families worth trying, and Polka, too small to be one.
const FAMILIES: [(&str, usize); 8] = [
    ("Alternative", 40),
    ("Hip-Hop/Rap", 30),
    ("Pop", 25),
    ("Jazz", 20),
    ("Country", 15),
    ("Electronic", 12),
    ("R&B/Soul", 10),
    ("Polka", 2),
];

fn probe_candidates() -> Vec<Candidate> {
    let mut all: Vec<Candidate> = FAMILIES
        .iter()
        .flat_map(|(genre, count)| {
            (0..*count).map(move |i| {
                song(
                    &format!("{genre} {i}"),
                    &format!("{genre} Artist {}", i % 6),
                    1.0,
                    false,
                    Some(genre),
                    Some(1975 + (i as i32 % 5) * 10),
                )
            })
        })
        .collect();
    all.extend((0..30).map(|i| {
        song(
            &format!("New {i}"),
            &format!("Newcomer {i}"),
            1.0,
            true,
            Some(FAMILIES[i % 7].0),
            Some(2020),
        )
    }));
    all
}

fn radio(seed: u64, new_share: f64, habits: &RadioHabits) -> LiveMix {
    let mut mix = LiveMix::new(probe_candidates(), new_share, seed);
    mix.begin(habits, true, 0);
    mix
}

fn probe(mix: &mut LiveMix, count: usize) -> Vec<String> {
    let mut picks: Vec<String> = mix.opener(&|_| true).into_iter().collect();
    while picks.len() < count {
        let Some(next) = mix.next() else { break };
        picks.push(next);
    }
    picks
}

#[test]
fn the_opening_picks_each_come_from_a_different_family_by_different_artists() {
    for seed in 1..=40 {
        let mut mix = radio(seed, 0.0, &RadioHabits::default());
        assert!(mix.is_probing());
        let picks = probe(&mut mix, 5);
        assert_eq!(picks.len(), 5);
        let families: FxHashSet<String> = picks.iter().filter_map(|p| genre_of(&mix, p)).collect();
        assert_eq!(families.len(), 5, "seed {seed}");
        let artists: FxHashSet<&str> = picks
            .iter()
            .filter_map(|p| mix.candidate(p).map(|c| c.artist_name.as_str()))
            .collect();
        assert_eq!(artists.len(), 5);
        assert!(picks.iter().all(|p| mix.reason(p) == Some(Reason::Probing)));
    }
}

#[test]
fn the_probe_favors_big_families_and_never_one_too_small() {
    let (mut alternative, mut polka) = (0, 0);
    for seed in 1..=200 {
        let mut mix = radio(seed, 0.0, &RadioHabits::default());
        let families: FxHashSet<String> = probe(&mut mix, 5)
            .iter()
            .filter_map(|p| genre_of(&mix, p))
            .collect();
        alternative += usize::from(families.contains("Alternative"));
        polka += usize::from(families.contains("Polka"));
    }
    assert!(alternative > 170, "{alternative}");
    assert_eq!(polka, 0);
}

#[test]
fn new_finds_take_their_share_of_the_probe_but_never_open_it() {
    for (share, expected) in [(0.08, 0), (0.25, 1), (0.5, 2)] {
        for seed in 1..=20 {
            let mut mix = radio(seed, share, &RadioHabits::default());
            let picks = probe(&mut mix, 5);
            let new = picks
                .iter()
                .filter(|p| mix.candidate(p).is_some_and(|c| c.is_new))
                .count();
            assert!(new >= expected, "{share}: {new}");
            assert!(mix.candidate(&picks[0]).is_some_and(|c| !c.is_new));
        }
    }
}

#[test]
fn a_small_library_or_a_listen_from_a_song_is_not_probed() {
    let mut small = LiveMix::new(probe_candidates().into_iter().take(12).collect(), 0.2, 1);
    small.begin(&RadioHabits::default(), true, 0);
    assert!(!small.is_probing());
    let mut from_song = LiveMix::new(probe_candidates(), 0.2, 1);
    from_song.begin(&RadioHabits::default(), false, 0);
    assert!(!from_song.is_probing());
}

#[test]
fn once_something_lands_the_probe_ends_and_says_what_it_settled_on() {
    let mut mix = radio(9, 0.0, &RadioHabits::default());
    let picks = probe(&mut mix, 5);
    mix.note(Listen::Finished, &picks[0]);
    for pick in &picks[1..] {
        mix.note(Listen::Skipped, pick);
    }
    let next = mix.next().expect("a pick");
    assert!(!mix.is_probing());
    assert_ne!(mix.reason(&next), Some(Reason::Probing));
    assert_eq!(
        mix.steering(),
        Some(&Steering::SettledOn(
            genre_of(&mix, &picks[0]).expect("a genre")
        ))
    );
}

#[test]
fn a_probe_of_all_skips_tries_new_directions_until_there_are_none() {
    for seed in 1..=30 {
        let mut mix = radio(seed, 0.0, &RadioHabits::default());
        let mut picks = probe(&mut mix, 1);
        let directions = FAMILIES.len() - 1;
        while picks.len() < directions {
            let Some(next) = mix.next() else { break };
            if let Some(last) = picks.last() {
                mix.note(Listen::Skipped, last);
            }
            picks.push(next);
        }
        assert!(mix.is_probing(), "seed {seed}");
        assert_eq!(mix.steering(), Some(&Steering::TryingSomethingElse));
        let families: FxHashSet<String> = picks.iter().filter_map(|p| genre_of(&mix, p)).collect();
        assert_eq!(families.len(), directions);
        let last = picks.last().expect("a pick").clone();
        mix.note(Listen::Skipped, &last);
        assert!(mix.next().is_some());
        assert!(!mix.is_probing());
    }
}

#[test]
fn loving_a_song_ends_the_probe_and_follows_it() {
    let mut mix = radio(4, 0.0, &RadioHabits::default());
    let picks = probe(&mut mix, 2);
    mix.note_loved(&picks[0]);
    assert!(!mix.is_probing());
    let artist = mix
        .candidate(&picks[0])
        .expect("a song")
        .artist_name
        .clone();
    assert_eq!(mix.steering(), Some(&Steering::TowardArtist(artist)));
    let next = mix.next().expect("a pick");
    assert_ne!(mix.reason(&next), Some(Reason::Probing));
}

#[test]
fn what_lands_is_kept_counts_double_in_the_probe_and_steers_the_next_listen() {
    let mut mix = radio(2, 0.0, &RadioHabits::default());
    let picks = probe(&mut mix, 5);
    let key = mix
        .candidate(&picks[0])
        .and_then(|c| c.genre.as_deref().and_then(genre_key))
        .expect("a family");
    mix.note(Listen::Finished, &picks[0]);
    let habits = mix.habits(0).expect("habits");
    assert!(habits.tally_genre(&key, Band::Anytime).landed >= 2.0);

    // A listen after jazz has landed again and again leans toward it from the start.
    let mut learned = RadioHabits::default();
    for _ in 0..12 {
        let mut listen = radio(1, 0.0, &learned);
        let jazz = listen
            .candidates()
            .iter()
            .find(|c| c.genre.as_deref() == Some("Jazz"))
            .expect("jazz")
            .identity
            .clone();
        listen.note_picked(&jazz);
        listen.note(Listen::Finished, &jazz);
        let other = listen
            .candidates()
            .iter()
            .find(|c| c.genre.as_deref() == Some("Country"))
            .expect("country")
            .identity
            .clone();
        listen.note_picked(&other);
        listen.note(Listen::Skipped, &other);
        learned = listen.habits(0).expect("habits");
    }
    let (mut jazz, mut country) = (0, 0);
    for seed in 1..=60 {
        let mut mix = radio(seed, 0.0, &learned);
        let opener = mix.opener(&|_| true).expect("an opener");
        match genre_of(&mix, &opener).as_deref() {
            Some("Jazz") => jazz += 1,
            Some("Country") => country += 1,
            _ => {}
        }
    }
    assert!(jazz > country * 2, "jazz {jazz}, country {country}");
    let back = RadioHabits::from_json(&learned.to_json());
    assert_eq!(back, learned);
}

#[test]
fn retuning_carries_the_probe() {
    let mut mix = radio(3, 0.0, &RadioHabits::default());
    let _ = probe(&mut mix, 2);
    let mut retuned = LiveMix::new(probe_candidates(), 0.0, 8);
    retuned.continue_listen(&mix);
    assert!(retuned.is_probing());
    assert!(retuned.habits(0).is_some());
}

#[test]
fn the_same_seed_and_listening_make_the_same_radio() {
    let run = |seed| {
        let mut mix = radio(seed, 0.25, &RadioHabits::default());
        let mut picks = probe(&mut mix, 3);
        mix.note(Listen::Skipped, &picks[0]);
        mix.note(Listen::Finished, &picks[1]);
        picks.extend((0..10).filter_map(|_| mix.next()));
        picks
    };
    assert_eq!(run(5), run(5));
    assert_ne!(run(5), run(6));
}
