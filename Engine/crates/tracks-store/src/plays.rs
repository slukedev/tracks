//! Reading plays back out, one row each, for hosts that build from the history (mixes, a
//! station) or show it as a feed.

use crate::{Result, Store};

/// One play as the index holds it, with its song's display fields as that play spelled them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlayRow {
    pub played_at: i64,
    pub identity: String,
    pub title: String,
    pub artist_name: String,
    pub album_title: Option<String>,
    pub catalog_id: String,
    pub song_key: String,
    pub artwork_url: Option<String>,
    /// The raw [`tracks_model::PlayKind`].
    pub kind: u8,
    /// The raw [`tracks_model::PlaySource`].
    pub source: u8,
    pub listen_ms: i64,
}

fn blank_to_none(value: String) -> Option<String> {
    (!value.is_empty()).then_some(value)
}

const PLAY_SELECT: &str = "SELECT p.played_at, s.identity, sp.title, sp.artist, sp.album, sp.catalog_id, \
     sp.song_key, sp.artwork_url, p.kind, p.source, p.listen_ms \
     FROM plays p JOIN songs s ON s.id = p.song_id JOIN spelled sp ON sp.id = p.spelled_id";

fn play_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<PlayRow> {
    Ok(PlayRow {
        played_at: row.get(0)?,
        identity: row.get(1)?,
        title: row.get(2)?,
        artist_name: row.get(3)?,
        album_title: blank_to_none(row.get(4)?),
        catalog_id: row.get(5)?,
        song_key: row.get(6)?,
        artwork_url: blank_to_none(row.get(7)?),
        kind: row.get(8)?,
        source: row.get(9)?,
        listen_ms: row.get(10)?,
    })
}

impl Store {
    /// Every play since `since_ms` (all of them for `None`), oldest first. One walk of the
    /// clustered table.
    pub fn plays_since(&self, since_ms: Option<i64>) -> Result<Vec<PlayRow>> {
        self.pool.read(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "{PLAY_SELECT} WHERE p.played_at >= ?1 ORDER BY p.played_at"
            ))?;
            let rows = stmt.query_map([since_ms.unwrap_or(i64::MIN)], play_row)?;
            Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
        })
    }

    /// The `limit` plays before `before_ms` (or the newest), newest first, for a feed.
    pub fn recent_plays(&self, before_ms: Option<i64>, limit: u32) -> Result<Vec<PlayRow>> {
        self.pool.read(|conn| {
            let mut stmt = conn.prepare_cached(&format!(
                "{PLAY_SELECT} WHERE p.played_at < ?1 ORDER BY p.played_at DESC LIMIT ?2"
            ))?;
            let rows = stmt.query_map(
                rusqlite::params![before_ms.unwrap_or(i64::MAX), i64::from(limit)],
                play_row,
            )?;
            Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
        })
    }
}

#[cfg(test)]
mod tests {
    use crate::{PlayInput, Store};
    use tracks_model::{PlayKind, PlaySource};

    fn play(id: &str, title: &str, at: i64) -> PlayInput {
        PlayInput {
            ext_id: id.into(),
            catalog_id: String::new(),
            song_key: String::new(),
            title: title.into(),
            artist_name: "Artist".into(),
            album_title: Some("Album".into()),
            artwork_url: None,
            played_at: at,
            kind: PlayKind::OnDemand,
            source: PlaySource::YourMusic,
            station_name: None,
        }
    }

    #[test]
    fn plays_read_back_in_order() {
        let store = Store::open_in_memory().expect("store");
        store
            .upsert(&[play("1", "One", 1_000), play("2", "Two", 2_000), play("3", "Three", 3_000)], None)
            .expect("write");
        let all = store.plays_since(None).expect("read");
        assert_eq!(all.iter().map(|p| p.title.as_str()).collect::<Vec<_>>(), ["One", "Two", "Three"]);
        assert_eq!(all[0].album_title.as_deref(), Some("Album"));
        assert_eq!(all[0].artwork_url, None);

        let recent = store.recent_plays(None, 2).expect("read");
        assert_eq!(recent.iter().map(|p| p.title.as_str()).collect::<Vec<_>>(), ["Three", "Two"]);
        let older = store.recent_plays(Some(2_000), 10).expect("read");
        assert_eq!(older.len(), 1);
        assert_eq!(store.plays_since(Some(2_000)).expect("read").len(), 2);
    }
}
