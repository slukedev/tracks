package com.luke.tracks.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.luke.tracks.engine.Engine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val _kept = MutableSharedFlow<PlayEntity>(extraBufferCapacity = 8)

    /** Each play as it's kept, for the "Kept" moment in Now Playing and the widgets. */
    val kept: SharedFlow<PlayEntity> = _kept.asSharedFlow()

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
    }

    suspend fun keepAll(plays: List<PlayEntity>) {
        if (plays.isEmpty()) return
        plays.chunked(2_000).forEach { dao.upsert(it) }
        engine.upsert(plays)
    }

    suspend fun remove(ids: List<String>) {
        dao.delete(ids)
        engine.remove(ids)
    }

    suspend fun last(): PlayEntity? = dao.last()

    /** At launch: brings the index to exactly what Room holds. */
    suspend fun syncIndex() {
        engine.sync(dao.all())
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
