//! The play history as a SQLite index, and everything read from it: charts, totals,
//! activity by hour and day, profiles and search.
//!
//! The host (`SwiftData` on Apple platforms today) stays the source of truth and syncs; this
//! index mirrors it and can always be thrown away and rebuilt. That keeps it safe to evolve:
//! a corrupt or stale file costs one rebuild, never a play.
//!
//! Reads never block each other or the writer (WAL, a small pool of read connections), and
//! every query walks an index or a rollup, so cost follows what is shown rather than how many
//! years of history there are.

mod directory;
mod ingest;
mod plays;
mod pool;
mod query;
mod schema;
mod time;

use std::path::{Path, PathBuf};
use std::sync::PoisonError;

use rusqlite::Connection;

pub use ingest::{IngestReport, PlayInput};
pub use plays::PlayRow;
pub use query::{
    ActivitySummary, AlbumRow, ArtistRow, Chart, ChartRow, HeatCell, Period, PeriodTotals, Scope,
    SearchResults, SongRow, SourceFilter, Streak, TimelineBucket,
};
pub use schema::DERIVATION_VERSION;

use pool::ConnectionPool;

/// What can go wrong reading or writing the index.
#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("database: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("migration: {0}")]
    Migration(#[from] rusqlite_migration::Error),
    #[error("unknown time zone `{0}`")]
    TimeZone(String),
    #[error("the index is closed")]
    Closed,
    /// The file was found damaged, so it has been emptied. [`Store::needs_rebuild`] is true
    /// until the host sends its whole history again.
    #[error("the index was damaged and has been emptied to be rebuilt: {0}")]
    Damaged(#[source] Box<StoreError>),
    /// The index was emptied after damage and hasn't had a whole history since, so it has
    /// nothing true to answer with.
    #[error("the index is waiting to be rebuilt")]
    Rebuilding,
}

pub type Result<T, E = StoreError> = std::result::Result<T, E>;

type DirectoryCache =
    std::sync::Arc<std::sync::Mutex<Option<std::sync::Arc<directory::Directory>>>>;

/// The history index. Cheap to share across threads: every method takes `&self`.
#[derive(Debug)]
pub struct Store {
    pool: ConnectionPool,
    path: Option<PathBuf>,
    zone: std::sync::RwLock<jiff::tz::TimeZone>,
    /// Lookups shared by reads until the next write, keyed by the generation counters in
    /// `meta`, which every write bumps in its own transaction. Living in the file, they also
    /// tell another process (a widget) that the app wrote. Forgotten when the file is emptied,
    /// whose counters start again.
    directory: DirectoryCache,
}

impl Store {
    /// Opens (or creates) the index at `path`, migrating it forward.
    ///
    /// A file SQLite says is damaged, or one a newer build wrote, is emptied (or, if even that
    /// fails, moved aside with its journal) and created again, since the host can always
    /// refill it; the caller learns this from [`Store::needs_rebuild`]. Damage found later,
    /// by any read or write, is handled the same way. Anything else (the file busy in another
    /// process, locked by data protection, a full disk) is returned as an error and the file
    /// is left alone, so a passing problem never costs the index.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref().to_path_buf();
        let directory = DirectoryCache::default();
        let pool = ConnectionPool::open(&path, forgetting(&directory))?;
        Ok(Self::new(pool, Some(path), directory))
    }

    /// An index that lives only in memory, for tests and previews.
    pub fn open_in_memory() -> Result<Self> {
        let directory = DirectoryCache::default();
        let pool = ConnectionPool::in_memory(forgetting(&directory))?;
        Ok(Self::new(pool, None, directory))
    }

    fn new(pool: ConnectionPool, path: Option<PathBuf>, directory: DirectoryCache) -> Self {
        Self {
            pool,
            path,
            zone: std::sync::RwLock::new(jiff::tz::TimeZone::UTC),
            directory,
        }
    }

    /// Where the index lives, if on disk.
    pub fn path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    /// Sets the time zone that hours, weekdays, days and streaks are counted in, by IANA name
    /// ("Europe/London"), or as a fixed offset from GMT as Foundation names one ("GMT+0530",
    /// "UTC-08:00", "+05:30"). The host passes its own, so the engine follows the system
    /// setting. A name it doesn't know leaves the zone as it was.
    pub fn set_time_zone(&self, name: &str) -> Result<()> {
        let zone = jiff::tz::TimeZone::get(name)
            .ok()
            .or_else(|| fixed_offset(name))
            .ok_or_else(|| StoreError::TimeZone(name.to_owned()))?;
        *self.zone.write().unwrap_or_else(PoisonError::into_inner) = zone;
        Ok(())
    }

    /// The time zone set with [`Store::set_time_zone`].
    pub fn time_zone(&self) -> jiff::tz::TimeZone {
        self.zone()
    }

    pub(crate) fn zone(&self) -> jiff::tz::TimeZone {
        self.zone
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    /// Whether the index was built by an older derivation (or never filled), so the host
    /// should send its whole history with [`Store::replace_all`].
    ///
    /// Damage found while asking empties the file, which then needs exactly that.
    pub fn needs_rebuild(&self) -> Result<bool> {
        let current = self.pool.read_any(|conn| {
            Ok(
                meta_get(conn, "derivation")?.and_then(|v| v.parse::<i64>().ok())
                    == Some(DERIVATION_VERSION),
            )
        });
        match current {
            Ok(current) => Ok(!current),
            Err(StoreError::Damaged(_)) => Ok(true),
            Err(error) => Err(error),
        }
    }

    /// An opaque value the host stores with its own sync state (a history token), so after a
    /// relaunch it knows where to pick up. Kept in the same transaction as the plays.
    pub fn sync_marker(&self) -> Result<Option<String>> {
        self.pool.read_any(|conn| meta_get(conn, "sync_marker"))
    }

    /// Compacts the file and refreshes the planner's statistics. Cheap enough to run when the
    /// app goes to the background after a big import.
    pub fn optimize(&self) -> Result<()> {
        self.pool.write(|conn| {
            conn.execute_batch("PRAGMA optimize; PRAGMA wal_checkpoint(TRUNCATE);")?;
            Ok(())
        })
    }

    /// How many plays the index holds.
    pub fn play_count(&self) -> Result<u64> {
        self.pool.read_any(|conn| {
            Ok(
                conn.query_row("SELECT COUNT(*) FROM plays", [], |row| row.get::<_, i64>(0))?
                    as u64,
            )
        })
    }
}

pub(crate) fn meta_get(conn: &Connection, key: &str) -> Result<Option<String>> {
    use rusqlite::OptionalExtension;
    Ok(conn
        .query_row("SELECT value FROM meta WHERE key = ?1", [key], |row| {
            row.get(0)
        })
        .optional()?)
}

pub(crate) fn meta_set(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        [key, value],
    )?;
    Ok(())
}

/// What the pool runs when the file is emptied: the directory's counters start again with it.
fn forgetting(directory: &DirectoryCache) -> impl Fn() + Send + Sync + 'static {
    let directory = std::sync::Arc::clone(directory);
    move || *directory.lock().unwrap_or_else(PoisonError::into_inner) = None
}

/// A zone a fixed offset from GMT, as Foundation names one when the system has no IANA name
/// for it: `GMT+0530`, `UTC-08:00`, `+05:30`, `GMT+5`.
fn fixed_offset(name: &str) -> Option<jiff::tz::TimeZone> {
    let rest = name
        .strip_prefix("GMT")
        .or_else(|| name.strip_prefix("UTC"))
        .unwrap_or(name);
    let (sign, digits) = if let Some(digits) = rest.strip_prefix('+') {
        (1, digits)
    } else {
        (-1, rest.strip_prefix('-')?)
    };
    let (hours, minutes) = match digits.split_once(':') {
        Some((hours, minutes)) => (hours, minutes),
        None if digits.len() > 2 => digits.split_at_checked(digits.len() - 2)?,
        None => (digits, "0"),
    };
    let all_digits =
        |part: &str| (1..=2).contains(&part.len()) && part.bytes().all(|b| b.is_ascii_digit());
    if !all_digits(hours) || !all_digits(minutes) {
        return None;
    }
    let (hours, minutes): (i32, i32) = (hours.parse().ok()?, minutes.parse().ok()?);
    if hours > 18 || minutes > 59 {
        return None;
    }
    let offset = jiff::tz::Offset::from_seconds(sign * (hours * 3_600 + minutes * 60)).ok()?;
    Some(jiff::tz::TimeZone::fixed(offset))
}

/// Counts every committed write (and, separately, writes that change names or first plays),
/// inside the write's own transaction.
pub(crate) fn bump(conn: &Connection, key: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, '1')
         ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
        [key],
    )?;
    Ok(())
}

pub(crate) fn counter(conn: &Connection, key: &str) -> Result<u64> {
    Ok(meta_get(conn, key)?
        .and_then(|v| v.parse().ok())
        .unwrap_or(0))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tracks_model::{PlayKind, PlaySource};

    fn play(id: u32) -> PlayInput {
        PlayInput {
            ext_id: id.to_string(),
            catalog_id: String::new(),
            song_key: String::new(),
            title: format!("A song with a long enough title to fill pages {id}"),
            artist_name: format!("Artist {}", id % 50),
            album_title: Some(format!("Album {}", id % 80)),
            artwork_url: None,
            played_at: i64::from(id) * 60_000,
            kind: PlayKind::OnDemand,
            source: PlaySource::AppleMusic,
            station_name: None,
        }
    }

    #[test]
    fn a_full_disk_fails_the_write_and_keeps_what_was_there() {
        let dir = tempfile::tempdir().expect("dir");
        let store = Store::open(dir.path().join("history.sqlite")).expect("open");
        let first: Vec<_> = (0..100).map(play).collect();
        store.replace_all(&first, None).expect("fill");
        store
            .pool
            .write(|conn| {
                let used: i64 = conn.query_row("PRAGMA page_count", [], |row| row.get(0))?;
                conn.execute_batch(&format!("PRAGMA max_page_count = {used}"))?;
                Ok(())
            })
            .expect("limit");

        let more: Vec<_> = (100..5_000).map(play).collect();
        let error = store.upsert(&more, None).expect_err("full");

        assert!(
            matches!(&error, StoreError::Sqlite(rusqlite::Error::SqliteFailure(e, _)) if e.code == rusqlite::ErrorCode::DiskFull),
            "{error}"
        );
        assert!(!store.needs_rebuild().expect("flag"));
        assert_eq!(store.play_count().expect("count"), 100);
    }

    #[test]
    fn a_write_that_panics_leaves_the_index_writable() {
        let store = Store::open_in_memory().expect("store");
        let panicked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            store.pool.write(|conn| -> Result<()> {
                let tx = conn.transaction()?;
                tx.execute("DELETE FROM meta", [])?;
                panic!("halfway through a write");
            })
        }));
        assert!(panicked.is_err());

        store.replace_all(&[play(1)], None).expect("write");
        assert_eq!(store.play_count().expect("count"), 1);
    }

    #[test]
    fn a_read_that_panics_mid_snapshot_leaves_the_index_writable() {
        let store = Store::open_in_memory().expect("store");
        let panicked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            store
                .pool
                .read(|_| -> Result<()> { panic!("halfway through a read") })
        }));
        assert!(panicked.is_err());

        store.replace_all(&[play(1)], None).expect("write");
        assert_eq!(store.play_count().expect("count"), 1);
    }

    #[test]
    fn a_time_zone_set_after_a_panic_elsewhere_still_takes() {
        let store = Store::open_in_memory().expect("store");
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _held = store.zone.write();
            panic!("while holding the zone");
        }));
        store.set_time_zone("Europe/London").expect("zone");
        assert_eq!(store.time_zone().iana_name(), Some("Europe/London"));
    }
}
