package com.luke.tracks.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One record Trackside Cloud keeps besides plays: a setting, a like, a forgotten song, a
 * blocked artist or an excluded station, or a play's deletion waiting to be sent.
 */
@Entity(tableName = "cloud_items", primaryKeys = ["entity", "id"], indices = [Index("pending")])
data class CloudItemEntity(
    /** The cloud's path segment: "settings", "likes", "plays" (for deletions) and so on. */
    val entity: String,
    val id: String,
    /** The record's fields as JSON, or null when it's deleted. */
    val fields: String?,
    val deletedAt: Long? = null,
    val mergedInto: String? = null,
    /** When it last changed, as the device that changed it said; 0 for a seed. */
    val modifiedAt: Long,
    val version: Long? = null,
    /** Counts changes not yet in the cloud; 0 once it's in step. */
    val pending: Int = 0,
)

/** Small values sync keeps: which server, cursors, when it last synced, this device's id. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val key: String, val value: String)

/** A play to tell a Trackside server about, kept until the server has it. */
@Entity(tableName = "listen_outbox", primaryKeys = ["serverId", "id"])
data class ListenEntity(
    /** Tracks' id for the server. */
    val serverId: String,
    /** The play's id, which the server dedupes by. */
    val id: String,
    val songId: String?,
    val startedAt: Long,
    val playedMs: Long?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val attempts: Int = 0,
)

@Dao
interface CloudDao {
    @Query("SELECT * FROM cloud_items WHERE entity = :entity AND id = :id")
    suspend fun item(entity: String, id: String): CloudItemEntity?

    @Query("SELECT * FROM cloud_items WHERE entity = :entity")
    suspend fun items(entity: String): List<CloudItemEntity>

    @Query("SELECT * FROM cloud_items WHERE entity = :entity")
    fun itemsFlow(entity: String): Flow<List<CloudItemEntity>>

    @Query("SELECT * FROM cloud_items WHERE entity = :entity AND pending > 0 ORDER BY modifiedAt LIMIT :limit")
    suspend fun pending(entity: String, limit: Int): List<CloudItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(items: List<CloudItemEntity>)

    /** In step with the cloud, unless it changed again since [pending] was sent. */
    @Query("UPDATE cloud_items SET pending = 0, version = :version WHERE entity = :entity AND id = :id AND pending = :pending")
    suspend fun settle(entity: String, id: String, pending: Int, version: Long?)

    @Query("DELETE FROM cloud_items WHERE entity = :entity AND id = :id AND pending = :pending")
    suspend fun dropIfUnchanged(entity: String, id: String, pending: Int)

    @Query("UPDATE cloud_items SET pending = pending + 1")
    suspend fun markAllPending()

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun state(key: String): String?

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    fun stateFlow(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun clearState(key: String)

    @Query("DELETE FROM sync_state WHERE `key` LIKE :prefix || '%'")
    suspend fun clearStates(prefix: String)

    @Transaction
    suspend fun setState(key: String, value: String?) {
        if (value == null) clearState(key) else putState(SyncStateEntity(key, value))
    }
}

@Dao
interface ListenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(listens: List<ListenEntity>)

    @Query("SELECT * FROM listen_outbox WHERE serverId = :serverId ORDER BY startedAt LIMIT :limit")
    suspend fun waiting(serverId: String, limit: Int): List<ListenEntity>

    @Query("SELECT DISTINCT serverId FROM listen_outbox")
    suspend fun servers(): List<String>

    @Query("SELECT COUNT(*) FROM listen_outbox")
    suspend fun count(): Int

    @Query("DELETE FROM listen_outbox WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun remove(serverId: String, ids: List<String>)

    @Query("DELETE FROM listen_outbox WHERE serverId = :serverId")
    suspend fun removeServer(serverId: String)

    @Query("UPDATE listen_outbox SET attempts = attempts + 1 WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun noteAttempt(serverId: String, ids: List<String>)
}
