package com.luke.tracks.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.luke.tracks.engine.Engine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.luke.tracks.playback.QueueMemory
import java.util.UUID

/**
 * The history: Room keeps every play, the engine indexes them for charts and mixes. Every
 * write goes to both, Room first, so a crash between them costs at most a resync at launch.
 */
class HistoryRepository(
    val database: HistoryDatabase,
    val engine: Engine,
) {
    private val dao = database.plays()
    private val cloud = database.cloud()
    private val _kept = MutableSharedFlow<PlayEntity>(extraBufferCapacity = 8)
    private val _written = MutableSharedFlow<List<PlayEntity>>(extraBufferCapacity = 64)
    private val _removed = MutableSharedFlow<List<String>>(extraBufferCapacity = 64)

    /** Each play as it's kept, for the "Kept" moment in Now Playing and the widgets. */
    val kept: SharedFlow<PlayEntity> = _kept.asSharedFlow()

    /** Plays this phone kept or updated, never ones that came from the cloud. */
    val written: SharedFlow<List<PlayEntity>> = _written.asSharedFlow()

    /** Plays removed on this phone, by id. */
    val removed: SharedFlow<List<String>> = _removed.asSharedFlow()

    val count: Flow<Int> = dao.countFlow()

    fun latest(limit: Int): Flow<List<PlayEntity>> = dao.latest(limit)

    fun paged(kind: PlayKindValue? = null): Flow<PagingData<PlayEntity>> =
        Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)) {
            if (kind == null) dao.pagedNewestFirst() else dao.pagedOfKind(kind)
        }.flow

    suspend fun keep(play: PlayEntity) {
        dao.upsert(listOf(play))
        engine.upsert(listOf(play))
        _kept.tryEmit(play)
        _written.tryEmit(listOf(play))
    }

    suspend fun keepAll(plays: List<PlayEntity>) {
        if (plays.isEmpty()) return
        plays.chunked(2_000).forEach { dao.upsert(it) }
        engine.upsert(plays)
        _written.tryEmit(plays)
    }

    /** Removes plays here, and from every synced device: each leaves a tombstone for the cloud. */
    suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            ids.chunked(500).forEach { dao.delete(it) }
            cloud.put(ids.map { CloudItemEntity("plays", it, fields = null, deletedAt = now, modifiedAt = now, pending = 1) })
        }
        engine.remove(ids)
        _removed.tryEmit(ids)
    }

    /**
     * What came from the cloud: Room first, then the engine's idempotent keep and remove, so a
     * page applied twice, or cut short by a crash, costs nothing.
     */
    suspend fun applyCloud(upserts: List<PlayEntity>, deletes: List<String>) {
        if (upserts.isEmpty() && deletes.isEmpty()) return
        database.withTransaction {
            upserts.chunked(2_000).forEach { dao.upsert(it) }
            deletes.chunked(500).forEach { dao.delete(it) }
        }
        if (upserts.isNotEmpty()) engine.upsert(upserts)
        if (deletes.isNotEmpty()) engine.remove(deletes)
    }

    /** Songs forgotten on any synced device, by identity, with when they were forgotten. */
    suspend fun forgottenSongs(): Map<String, Long> = cloud.items("forgotten-songs").mapNotNull { item ->
        val fields = item.fields ?: return@mapNotNull null
        val at = runCatching { Json.parseToJsonElement(fields).jsonObject["forgottenAtMs"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: item.modifiedAt
        item.id to at
    }.toMap()

    suspend fun last(): PlayEntity? = dao.last()

    /** At launch: brings the index to exactly what Room holds. */
    suspend fun syncIndex() {
        val plays = dao.all()
        engine.sync(if (scrubSignedCovers(plays)) dao.all() else plays)
    }

    /**
     * Plays kept by older versions saved a server's signed cover address, which works as a
     * credential for as long as the password does. Each becomes its credential-free link.
     */
    private suspend fun scrubSignedCovers(plays: List<PlayEntity>): Boolean {
        var changed = false
        plays.forEach { play ->
            val artwork = play.artworkUri ?: return@forEach
            val stable = QueueMemory.stableArtwork(play.mediaId, artwork)
            if (stable != artwork) {
                dao.setArtwork(play.id, stable)
                changed = true
            }
        }
        return changed
    }

    suspend fun replaceAll(plays: List<PlayEntity>) {
        dao.deleteAll()
        keepAll(plays)
        engine.sync(plays)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
