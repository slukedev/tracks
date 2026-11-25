package com.luke.tracks.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlayEntity::class, CloudItemEntity::class, SyncStateEntity::class, ListenEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun plays(): PlayDao
    abstract fun cloud(): CloudDao
    abstract fun listens(): ListenDao

    companion object {
        fun open(context: Context, name: String): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(Migration1To2)
                .build()
    }
}

/**
 * Version 2 gets ready for Trackside Cloud: who recorded each play and whether the cloud has
 * it, the cloud's other records, sync's own state and the listen outbox. Imported scrobbles
 * take the id every device gives them ([PlayIds.lastFm]); two old ids for one scrobble become
 * one play.
 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `plays` ADD COLUMN `recordedBy` TEXT")
        db.execSQL("ALTER TABLE `plays` ADD COLUMN `cloudVersion` INTEGER")
        db.execSQL("ALTER TABLE `plays` ADD COLUMN `cloudPending` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `plays` ADD COLUMN `cloudFields` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plays_cloudPending` ON `plays` (`cloudPending`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cloud_items` (`entity` TEXT NOT NULL, `id` TEXT NOT NULL, `fields` TEXT, " +
                "`deletedAt` INTEGER, `mergedInto` TEXT, `modifiedAt` INTEGER NOT NULL, `version` INTEGER, " +
                "`pending` INTEGER NOT NULL, PRIMARY KEY(`entity`, `id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_items_pending` ON `cloud_items` (`pending`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `listen_outbox` (`serverId` TEXT NOT NULL, `id` TEXT NOT NULL, `songId` TEXT, " +
                "`startedAt` INTEGER NOT NULL, `playedMs` INTEGER, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`album` TEXT, `durationMs` INTEGER, `attempts` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `id`))",
        )
        renameImportedScrobbles(db)
    }

    private fun renameImportedScrobbles(db: SupportSQLiteDatabase) {
        val renames = mutableListOf<Pair<String, String>>()
        db.query("SELECT `id`, `title`, `artistName`, `playedAt` FROM `plays` WHERE `id` LIKE 'lastfm-%'").use { rows ->
            while (rows.moveToNext()) {
                val old = rows.getString(0)
                renames += old to PlayIds.lastFm(Math.floorDiv(rows.getLong(3), 1000L), rows.getString(1), rows.getString(2))
            }
        }
        renames.forEach { (old, new) ->
            db.execSQL("UPDATE OR IGNORE `plays` SET `id` = ? WHERE `id` = ?", arrayOf(new, old))
            // Still there: another old id already became this one, so this is the same scrobble twice.
            db.execSQL("DELETE FROM `plays` WHERE `id` = ?", arrayOf(old))
        }
    }
}
