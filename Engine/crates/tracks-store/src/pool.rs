//! One writer and a few readers over the same file. WAL lets readers run alongside the
//! writer, so a chart never waits for an import.
//!
//! The pool also keeps the file usable. A file SQLite says is damaged, whether on open or
//! halfway through a read years later, or one written by a newer build, is emptied and
//! migrated again, and the index reports itself as needing a rebuild until the host has sent
//! its whole history again. Busy, locked and full-disk errors leave the file alone.

use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, RwLock, RwLockReadGuard};

use rusqlite::config::DbConfig;
use rusqlite::{Connection, ErrorCode, OpenFlags};

use crate::{DERIVATION_VERSION, Result, StoreError, meta_get, schema};

/// More readers than this buys nothing on a phone: the UI asks for a handful of things at once.
const MAX_IDLE_READERS: usize = 3;

/// Memory-mapped reads share pages with the file cache, but an I/O error on a mapped page is
/// a SIGBUS rather than an error SQLite can report, and phones make protected files
/// unreadable while the device is locked.
#[cfg(any(target_os = "ios", target_os = "android"))]
const MMAP_SIZE: i64 = 0;
#[cfg(not(any(target_os = "ios", target_os = "android")))]
const MMAP_SIZE: i64 = 268_435_456;

type ResetHook = Box<dyn Fn() + Send + Sync>;

pub struct ConnectionPool {
    /// None after the file couldn't be opened again; the next call tries once more.
    writer: Mutex<Option<Connection>>,
    readers: Mutex<Vec<Connection>>,
    /// In memory, readers would each see their own empty database, so reads go to the writer.
    path: Option<Arc<Path>>,
    /// Shared by every read and write, and held alone while the file is emptied, so nothing
    /// runs on it meanwhile.
    gate: RwLock<()>,
    /// How many times the file has been emptied while open, so calls that all met the same
    /// damage empty it once.
    resets: AtomicU64,
    /// Set when the file is emptied while open, and cleared once a whole history is back in.
    /// Until then reads fail rather than answer from an empty index.
    rebuilding: AtomicBool,
    on_reset: ResetHook,
}

impl std::fmt::Debug for ConnectionPool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConnectionPool")
            .field("path", &self.path)
            .field("resets", &self.resets)
            .field("rebuilding", &self.rebuilding)
            .finish_non_exhaustive()
    }
}

impl ConnectionPool {
    /// Opens the file at `path`, creating, migrating or emptying it as needed. `on_reset`
    /// runs whenever the file is emptied while open, so anything cached from it is forgotten.
    pub fn open(path: &Path, on_reset: impl Fn() + Send + Sync + 'static) -> Result<Self> {
        let writer = open_writer(path)?;
        Ok(Self::new(writer, Some(Arc::from(path)), Box::new(on_reset)))
    }

    pub fn in_memory(on_reset: impl Fn() + Send + Sync + 'static) -> Result<Self> {
        Ok(Self::new(memory_writer()?, None, Box::new(on_reset)))
    }

    fn new(writer: Connection, path: Option<Arc<Path>>, on_reset: ResetHook) -> Self {
        Self {
            writer: Mutex::new(Some(writer)),
            readers: Mutex::new(Vec::new()),
            path,
            gate: RwLock::new(()),
            resets: AtomicU64::new(0),
            rebuilding: AtomicBool::new(false),
            on_reset,
        }
    }

    /// Whether the file was emptied while open and hasn't had a whole history since.
    pub fn is_rebuilding(&self) -> bool {
        self.rebuilding.load(Ordering::Acquire)
    }

    fn shared(&self) -> RwLockReadGuard<'_, ()> {
        // The gate guards nothing but its own exclusion, so a panic elsewhere can't leave it
        // inconsistent.
        self.gate.read().unwrap_or_else(PoisonError::into_inner)
    }

    fn writer(&self) -> MutexGuard<'_, Option<Connection>> {
        // A panic while holding the lock leaves SQLite itself consistent (the transaction rolls
        // back on drop), so a poisoned lock is safe to keep using.
        self.writer.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn idle_readers(&self) -> MutexGuard<'_, Vec<Connection>> {
        self.readers.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub fn write<T>(&self, work: impl FnOnce(&mut Connection) -> Result<T>) -> Result<T> {
        let (seen, result) = {
            let _shared = self.shared();
            let seen = self.resets.load(Ordering::Acquire);
            let mut writer = self.writer();
            let result = self.reopened(&mut writer).and_then(|conn| {
                // A transaction left open by a panic would make every later one fail.
                if !conn.is_autocommit() {
                    let _ = conn.execute_batch("ROLLBACK");
                }
                work(conn).inspect(|_| self.note_rebuilt(conn))
            });
            (seen, result)
        };
        self.recovering(seen, result)
    }

    /// Runs a read in one snapshot. Fails while the index is being rebuilt, since an emptied
    /// index would answer with nothing as if that were the history.
    pub fn read<T>(&self, work: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
        if self.is_rebuilding() {
            return Err(StoreError::Rebuilding);
        }
        self.read_any(work)
    }

    /// [`Self::read`], answering even while the index is being rebuilt: for what tells the
    /// host the index's state.
    pub fn read_any<T>(&self, work: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
        let (seen, result) = {
            let _shared = self.shared();
            let seen = self.resets.load(Ordering::Acquire);
            (seen, self.read_in_snapshot(work))
        };
        self.recovering(seen, result)
    }

    fn read_in_snapshot<T>(&self, work: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
        let Some(path) = &self.path else {
            let mut writer = self.writer();
            let conn = self.reopened(&mut writer)?;
            return in_snapshot(conn, work);
        };
        let reader = self.idle_readers().pop();
        let conn = match reader {
            Some(conn) => conn,
            None => {
                let conn = Connection::open_with_flags(
                    path.as_ref(),
                    OpenFlags::SQLITE_OPEN_READ_ONLY
                        | OpenFlags::SQLITE_OPEN_NO_MUTEX
                        | OpenFlags::SQLITE_OPEN_URI,
                )?;
                configure(&conn, false)?;
                conn
            }
        };
        let result = in_snapshot(&conn, work);
        // One whose snapshot couldn't be ended would fail every read after it.
        if conn.is_autocommit() {
            let mut idle = self.idle_readers();
            if idle.len() < MAX_IDLE_READERS {
                idle.push(conn);
            }
        }
        result
    }

    /// The writer, opening the file again if an earlier attempt failed.
    fn reopened<'a>(
        &self,
        writer: &'a mut MutexGuard<'_, Option<Connection>>,
    ) -> Result<&'a mut Connection> {
        if writer.is_none() {
            **writer = Some(match &self.path {
                Some(path) => open_writer(path)?,
                None => memory_writer()?,
            });
        }
        writer.as_mut().ok_or(StoreError::Closed)
    }

    /// Clears the rebuilding flag once a write has left a whole, current history.
    fn note_rebuilt(&self, conn: &Connection) {
        if self.is_rebuilding()
            && meta_get(conn, "derivation")
                .ok()
                .flatten()
                .and_then(|v| v.parse::<i64>().ok())
                == Some(DERIVATION_VERSION)
        {
            self.rebuilding.store(false, Ordering::Release);
        }
    }

    /// Passes `result` on, first emptying the file if it says the file is damaged.
    fn recovering<T>(&self, seen: u64, result: Result<T>) -> Result<T> {
        match result {
            Err(error) if is_damage(&error) => {
                self.reset(seen);
                Err(StoreError::Damaged(Box::new(error)))
            }
            other => other,
        }
    }

    /// Empties the file and migrates it again, unless another call already did since `seen`.
    fn reset(&self, seen: u64) {
        let _alone = self.gate.write().unwrap_or_else(PoisonError::into_inner);
        if self.resets.load(Ordering::Acquire) != seen {
            return;
        }
        let mut writer = self.writer();
        self.idle_readers().clear();
        let emptied = writer.as_mut().map_or(Err(StoreError::Closed), empty);
        if emptied.is_err() {
            // Emptying in place keeps every process on the one file. When even that fails,
            // the file moves aside and a new one takes its place.
            *writer = None;
            *writer = match &self.path {
                Some(path) => {
                    move_aside(path);
                    open_writer(path).ok()
                }
                None => memory_writer().ok(),
            };
        }
        self.rebuilding.store(true, Ordering::Release);
        self.resets.fetch_add(1, Ordering::AcqRel);
        (self.on_reset)();
    }
}

/// Whether SQLite reported the file itself as damaged, as opposed to busy or unreachable.
pub(crate) fn is_damage(error: &StoreError) -> bool {
    matches!(
        sqlite_code(error),
        Some(ErrorCode::NotADatabase | ErrorCode::DatabaseCorrupt)
    )
}

fn sqlite_code(error: &StoreError) -> Option<ErrorCode> {
    match error {
        StoreError::Sqlite(rusqlite::Error::SqliteFailure(e, _))
        | StoreError::Migration(rusqlite_migration::Error::RusqliteError {
            err: rusqlite::Error::SqliteFailure(e, _),
            ..
        }) => Some(e.code),
        _ => None,
    }
}

/// Whether the file's schema version is one this build doesn't know: a newer build's, or
/// one another program set.
fn is_unknown_version(error: &StoreError) -> bool {
    use rusqlite_migration::{Error, MigrationDefinitionError, SchemaVersionError};
    matches!(
        error,
        StoreError::Migration(
            Error::MigrationDefinition(MigrationDefinitionError::DatabaseTooFarAhead)
                | Error::InvalidUserVersion
                | Error::SpecifiedSchemaVersion(SchemaVersionError::TooHigh)
        )
    )
}

/// The writer for the file at `path`, ready to use.
///
/// A file written by a newer build is emptied, since this build can't read it and the host
/// refills it; a damaged one is emptied, or moved aside with its journal if SQLite can't even
/// do that. Anything else is returned and the file left alone, so a passing problem (the file
/// busy in another process, locked by data protection, a full disk) never costs the index.
fn open_writer(path: &Path) -> Result<Connection> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    match prepared_writer(path) {
        Err(error) if is_damage(&error) => {
            move_aside(path);
            prepared_writer(path).map_err(|_| error)
        }
        other => other,
    }
}

fn prepared_writer(path: &Path) -> Result<Connection> {
    let mut conn = Connection::open(path)?;
    configure(&conn, true)?;
    match schema::migrations().to_latest(&mut conn) {
        Ok(()) => Ok(conn),
        Err(error) => {
            let error = StoreError::from(error);
            if is_unknown_version(&error) || is_damage(&error) {
                empty(&mut conn)?;
                Ok(conn)
            } else {
                Err(error)
            }
        }
    }
}

fn memory_writer() -> Result<Connection> {
    let mut conn = Connection::open_in_memory()?;
    configure(&conn, true)?;
    schema::migrations().to_latest(&mut conn)?;
    Ok(conn)
}

/// Resets the database to nothing, in place, then builds the schema again. SQLite's own
/// way to do this works even on a file with corrupt pages.
fn empty(conn: &mut Connection) -> Result<()> {
    conn.set_db_config(DbConfig::SQLITE_DBCONFIG_RESET_DATABASE, true)?;
    let vacuumed = conn.execute_batch("VACUUM");
    conn.set_db_config(DbConfig::SQLITE_DBCONFIG_RESET_DATABASE, false)?;
    vacuumed?;
    configure(conn, true)?;
    schema::migrations().to_latest(conn)?;
    Ok(())
}

/// Moves the file and its journal to `.damaged`, replacing any earlier one.
fn move_aside(path: &Path) {
    let aside = path.with_extension("damaged");
    for suffix in ["", "-wal", "-shm"] {
        let from = with_suffix(path, suffix);
        let to = with_suffix(&aside, suffix);
        let _ = std::fs::remove_file(&to);
        let _ = std::fs::rename(&from, &to);
    }
}

fn with_suffix(path: &Path, suffix: &str) -> std::path::PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(suffix);
    name.into()
}

fn configure(conn: &Connection, writer: bool) -> Result<()> {
    // Tuned for phones as much as Macs: a modest page cache, memory-mapped reads where a
    // failed page read can't crash the app, and NORMAL sync, which in WAL mode is durable
    // across app crashes and only risks the last commit on power loss, a play the host still
    // has.
    conn.busy_timeout(std::time::Duration::from_secs(5))?;
    conn.set_prepared_statement_cache_capacity(64);
    conn.execute_batch(
        "PRAGMA foreign_keys = ON;
         PRAGMA cache_size = -8000;
         PRAGMA temp_store = MEMORY;",
    )?;
    conn.execute_batch(&format!("PRAGMA mmap_size = {MMAP_SIZE};"))?;
    if writer {
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
             PRAGMA journal_size_limit = 8388608;",
        )?;
    }
    Ok(())
}

/// Runs a read in one transaction, so every statement in it sees the same version of the
/// history even while a write commits.
fn in_snapshot<T>(conn: &Connection, work: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
    conn.execute_batch("BEGIN DEFERRED")?;
    let result = work(conn);
    // Nothing was written, so ending it can't lose anything; a failure here only means the
    // transaction had already ended.
    let _ = conn.execute_batch(if result.is_ok() { "COMMIT" } else { "ROLLBACK" });
    result
}
