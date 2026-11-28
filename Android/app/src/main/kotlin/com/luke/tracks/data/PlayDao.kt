package com.luke.tracks.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plays: List<PlayEntity>)

    @Query("DELETE FROM plays WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM plays")
    suspend fun deleteAll()

    @Query("SELECT * FROM plays ORDER BY playedAt")
    suspend fun all(): List<PlayEntity>

    @Query("UPDATE plays SET artworkUri = :artworkUri WHERE id = :id")
    suspend fun setArtwork(id: String, artworkUri: String?)

    @Query("SELECT * FROM plays WHERE id = :id")
    suspend fun byId(id: String): PlayEntity?

    @Query("SELECT * FROM plays WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<PlayEntity>

    /** Plays the cloud doesn't have as they are here, after the one at ([afterAt], [afterId]). */
    @Query(
        "SELECT * FROM plays WHERE cloudPending > 0 AND (playedAt > :afterAt OR (playedAt = :afterAt AND id > :afterId)) " +
            "ORDER BY playedAt, id LIMIT :limit"
    )
    suspend fun cloudPending(afterAt: Long, afterId: String, limit: Int): List<PlayEntity>

    @Query("SELECT COUNT(*) FROM plays WHERE cloudPending > 0")
    fun cloudPendingCount(): Flow<Int>

    /** In step with the cloud, unless it changed again since [pending] was sent. */
    @Query("UPDATE plays SET cloudPending = 0, cloudVersion = :version WHERE id = :id AND cloudPending = :pending")
    suspend fun settle(id: String, pending: Int, version: Long?)

    @Query("UPDATE plays SET cloudPending = cloudPending + 1")
    suspend fun markAllCloudPending()

    @Query("SELECT COUNT(*) FROM plays")
    fun countFlow(): Flow<Int>

    /** The history, newest first, for the History tab. */
    @Query("SELECT * FROM plays ORDER BY playedAt DESC")
    fun pagedNewestFirst(): PagingSource<Int, PlayEntity>

    @Query("SELECT * FROM plays WHERE kind = :kind ORDER BY playedAt DESC")
    fun pagedOfKind(kind: PlayKindValue): PagingSource<Int, PlayEntity>

    @Query("SELECT * FROM plays ORDER BY playedAt DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<PlayEntity>>

    @Query("SELECT * FROM plays ORDER BY playedAt DESC LIMIT 1")
    suspend fun last(): PlayEntity?

    /**
     * Plays still owed to Last.fm, oldest first: every source's, the services' too. A play
     * another device recorded is that device's to send, and one sent or turned down never
     * comes back, so no play goes twice.
     */
    @Query(
        "SELECT * FROM plays WHERE scrobbledAt IS NULL AND scrobbleError IS NULL " +
            "AND kind != 'LastFm' AND recordedBy IS NULL AND playedAt >= :since " +
            "ORDER BY playedAt LIMIT :limit"
    )
    suspend fun owedScrobbles(since: Long, limit: Int): List<PlayEntity>

    @Query(
        "SELECT COUNT(*) FROM plays WHERE scrobbledAt IS NULL AND scrobbleError IS NULL " +
            "AND kind != 'LastFm' AND recordedBy IS NULL AND playedAt >= :since"
    )
    fun owedScrobbleCount(since: Long): Flow<Int>

    @Query("UPDATE plays SET scrobbledAt = :at, scrobbleError = NULL, cloudPending = cloudPending + 1 WHERE id IN (:ids)")
    suspend fun markScrobbled(ids: List<String>, at: Long)

    @Query("UPDATE plays SET scrobbleError = :error, scrobbleAttempts = scrobbleAttempts + 1 WHERE id = :id")
    suspend fun markScrobbleFailed(id: String, error: String)

    @Query("UPDATE plays SET scrobbleAttempts = scrobbleAttempts + 1 WHERE id IN (:ids)")
    suspend fun noteScrobbleAttempt(ids: List<String>)
}
