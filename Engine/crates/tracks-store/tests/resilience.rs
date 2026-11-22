//! The file going wrong underneath the index: damaged pages, a newer build's schema, stray
//! journals, a busy file. The index is a cache of the host's history, so each must cost at
//! most a rebuild, never a wrong answer, and a passing problem must cost nothing.

mod support;

use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use support::{DAY, HOUR, MINUTE, play};
use tracks_store::{Period, PlayInput, SourceFilter, Store, StoreError};

fn history(count: i64) -> Vec<PlayInput> {
    (0..count)
        .map(|i| {
            play(
                &format!("play {i}"),
                &format!("Song {}", i % 97),
                &format!("Artist {}", i % 13),
                i * 7 * MINUTE,
            )
        })
        .collect()
}

struct File {
    _dir: tempfile::TempDir,
    path: PathBuf,
}

/// A file holding `plays`, closed, so everything is checkpointed into the main file.
fn filled_file(plays: &[PlayInput]) -> File {
    let dir = tempfile::tempdir().expect("dir");
    let path = dir.path().join("history.sqlite");
    Store::open(&path)
        .expect("open")
        .replace_all(plays, None)
        .expect("fill");
    File { _dir: dir, path }
}

/// Overwrites the root page of `table` with garbage, as a failing flash page or a bad restore
/// would, leaving the header and schema readable so the file still opens.
fn damage_table(path: &Path, table: &str) {
    let raw = rusqlite::Connection::open(path).expect("raw");
    let page_size: i64 = raw
        .query_row("PRAGMA page_size", [], |row| row.get(0))
        .expect("page size");
    let root: i64 = raw
        .query_row(
            "SELECT rootpage FROM sqlite_schema WHERE name = ?1",
            [table],
            |row| row.get(0),
        )
        .expect("root page");
    drop(raw);
    assert!(root > 1, "the schema page must stay readable");
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .open(path)
        .expect("file");
    file.seek(SeekFrom::Start(
        u64::try_from((root - 1) * page_size).expect("offset"),
    ))
    .expect("seek");
    file.write_all(&vec![0xA5; usize::try_from(page_size).expect("size")])
        .expect("garbage");
}

fn integrity(path: &Path) -> String {
    rusqlite::Connection::open(path)
        .expect("raw")
        .query_row("PRAGMA integrity_check", [], |row| row.get(0))
        .expect("check")
}

fn all_time_plays(store: &Store) -> Result<u64, StoreError> {
    Ok(store.totals(Period::ALL_TIME, SourceFilter::All)?.plays)
}

#[test]
fn a_page_damaged_after_open_empties_the_index_which_refuses_to_answer_until_rebuilt() {
    let plays = history(3_000);
    let file = filled_file(&plays);
    damage_table(&file.path, "plays");

    let store = Store::open(&file.path).expect("the header is fine, so it opens");
    assert!(!store.needs_rebuild().expect("flag"));
    assert!(matches!(store.streak(0), Err(StoreError::Damaged(_))));

    assert!(store.needs_rebuild().expect("flag"));
    assert!(matches!(
        all_time_plays(&store),
        Err(StoreError::Rebuilding)
    ));
    assert_eq!(integrity(&file.path), "ok");
    assert!(!file.path.with_extension("damaged").exists());

    store.sync(&plays, None).expect("rebuild");
    assert!(!store.needs_rebuild().expect("flag"));
    assert_eq!(all_time_plays(&store).expect("totals"), 3_000);
}

#[test]
fn a_sync_that_meets_damage_fails_once_and_the_next_sync_rebuilds_everything() {
    let plays = history(3_000);
    let file = filled_file(&plays);
    damage_table(&file.path, "plays");
    let store = Store::open(&file.path).expect("open");

    assert!(matches!(
        store.sync(&plays, None),
        Err(StoreError::Damaged(_))
    ));
    let report = store.sync(&plays, Some("after")).expect("rebuild");

    assert_eq!(report.inserted, 3_000);
    assert_eq!(
        store.sync_marker().expect("marker").as_deref(),
        Some("after")
    );
    assert_eq!(all_time_plays(&store).expect("totals"), 3_000);
}

#[test]
fn damage_to_the_index_s_own_records_is_found_by_the_next_sync_which_rebuilds_at_once() {
    let plays = history(500);
    let file = filled_file(&plays);
    damage_table(&file.path, "meta");

    let store = Store::open(&file.path).expect("open");
    let report = store.sync(&plays, None).expect("rebuild");
    assert_eq!(report.inserted, 500);
    drop(store);

    let relaunched = Store::open(&file.path).expect("open");
    assert!(!relaunched.needs_rebuild().expect("flag"));
    assert_eq!(all_time_plays(&relaunched).expect("totals"), 500);
}

#[test]
fn a_schema_this_build_does_not_know_is_emptied_and_refilled() {
    for version in [99, -1] {
        let file = filled_file(&history(20));
        rusqlite::Connection::open(&file.path)
            .expect("raw")
            .pragma_update(None, "user_version", version)
            .expect("version");

        let store = Store::open(&file.path).expect("open");
        assert!(store.needs_rebuild().expect("flag"), "version {version}");
        store.sync(&history(20), None).expect("refill");
        assert_eq!(all_time_plays(&store).expect("totals"), 20);
    }
}

#[test]
fn opening_a_healthy_file_again_and_again_changes_nothing() {
    let plays = history(200);
    let file = filled_file(&plays);
    for _ in 0..3 {
        let store = Store::open(&file.path).expect("open");
        assert!(!store.needs_rebuild().expect("flag"));
        let report = store.sync(&plays, None).expect("sync");
        assert_eq!(
            (report.inserted, report.removed, report.unchanged),
            (0, 0, 200)
        );
    }
}

#[test]
fn a_journal_left_without_its_database_is_not_replayed_into_a_new_one() {
    let dir = tempfile::tempdir().expect("dir");
    let path = dir.path().join("history.sqlite");
    let wal = dir.path().join("history.sqlite-wal");
    let kept = dir.path().join("kept-wal");
    {
        let store = Store::open(&path).expect("open");
        store.replace_all(&history(300), None).expect("fill");
        store.upsert(&history(310)[300..], None).expect("more");
        std::fs::copy(&wal, &kept).expect("keep the journal");
    }
    std::fs::remove_file(&path).expect("lose the database");
    std::fs::copy(&kept, &wal).expect("journal back");

    let store = Store::open(&path).expect("open");
    assert!(store.needs_rebuild().expect("flag"));
    assert_eq!(store.play_count().expect("count"), 0);
    assert_eq!(integrity(&path), "ok");
}

#[test]
fn a_garbage_shared_memory_file_is_rebuilt_without_losing_anything() {
    let plays = history(200);
    let file = filled_file(&plays);
    let shm = PathBuf::from(format!("{}-shm", file.path.display()));
    std::fs::write(&shm, vec![0x5A; 32 * 1024]).expect("garbage");

    let store = Store::open(&file.path).expect("open");
    assert!(!store.needs_rebuild().expect("flag"));
    assert_eq!(all_time_plays(&store).expect("totals"), 200);
}

#[test]
fn a_file_busy_in_another_process_fails_the_write_and_costs_nothing() {
    let plays = history(50);
    let file = filled_file(&plays);
    let store = Store::open(&file.path).expect("open");
    let other = rusqlite::Connection::open(&file.path).expect("other process");
    other
        .execute_batch("BEGIN IMMEDIATE")
        .expect("hold the lock");

    let error = store
        .upsert(&[play("late", "Late", "X", 400 * DAY)], None)
        .expect_err("busy");
    assert!(!matches!(error, StoreError::Damaged(_)), "{error}");

    other.execute_batch("ROLLBACK").expect("release");
    assert!(!store.needs_rebuild().expect("flag"));
    store
        .upsert(&[play("late", "Late", "X", 400 * DAY)], None)
        .expect("works once free");
    assert_eq!(all_time_plays(&store).expect("totals"), 51);
}

#[test]
fn an_unknown_zone_keeps_the_one_before() {
    let store = Store::open_in_memory().expect("store");
    store.set_time_zone("Asia/Tokyo").expect("zone");
    assert!(matches!(
        store.set_time_zone("Nowhere/Special"),
        Err(StoreError::TimeZone(name)) if name == "Nowhere/Special"
    ));
    assert_eq!(store.time_zone().iana_name(), Some("Asia/Tokyo"));
}

#[test]
fn a_fixed_offset_named_as_foundation_names_it_counts_local_hours() {
    let store = Store::open_in_memory().expect("store");
    // 18:30 UTC is midnight at +05:30, so the play falls on the next local day.
    store
        .replace_all(&[play("1", "A", "X", 18 * HOUR + 30 * MINUTE)], None)
        .expect("fill");
    for name in ["GMT+0530", "UTC+05:30", "+05:30", "GMT+530"] {
        store.set_time_zone(name).expect(name);
        let streak = store.streak(DAY + HOUR).expect("streak");
        assert_eq!(streak.longest_end_day, Some(1), "{name}");
    }
    for name in ["GMT+", "GMT+99", "GMT+05:75", "GMT+٠٥", "05:30"] {
        assert!(store.set_time_zone(name).is_err(), "{name}");
    }
}

#[test]
fn a_streak_counts_local_days_across_a_daylight_saving_change() {
    let store = Store::open_in_memory().expect("store");
    store.set_time_zone("America/New_York").expect("zone");
    // 2026-03-08 is the spring change in New York. Plays at 23:30 local on the 7th, 8th and
    // 9th: one an hour later in UTC than the other two, but each on its own local day.
    let march_7_2330_est = 1_772_944_200_000;
    let plays = [
        play("1", "A", "X", march_7_2330_est),
        play("2", "A", "X", march_7_2330_est + DAY - HOUR),
        play("3", "A", "X", march_7_2330_est + 2 * DAY - HOUR),
    ];
    store.replace_all(&plays, None).expect("fill");

    let streak = store.streak(march_7_2330_est + 2 * DAY).expect("streak");
    assert_eq!((streak.current, streak.longest), (3, 3));
}

#[test]
fn a_streak_asked_for_before_the_first_play_has_no_current_run() {
    let store = Store::open_in_memory().expect("store");
    store
        .replace_all(&[play("1", "A", "X", 10 * DAY)], None)
        .expect("fill");
    let streak = store.streak(2 * DAY).expect("streak");
    assert_eq!((streak.current, streak.longest), (0, 1));
}
