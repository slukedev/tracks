package com.luke.tracks.library.trackside

import com.luke.tracks.library.SubsonicSong

/** Tracks' copy of a Trackside server's songs, and the cursor the next sync continues from. */
data class TracksideMirror(val songs: Map<String, SubsonicSong> = emptyMap(), val cursor: String? = null) {
    /** Applies one page, snapshot or delta. Within a page each id comes at most once. */
    fun applying(page: TracksideSyncPage): TracksideMirror {
        val next = songs.toMutableMap()
        (page.songs.orEmpty() + page.upserts?.songs.orEmpty()).forEach { song ->
            // A missing file can't be played, so it's left out until it comes back.
            if (song.missing == true) next.remove(song.id) else next[song.id] = song.subsonic
        }
        page.deletes?.songs.orEmpty().forEach(next::remove)
        return copy(songs = next)
    }
}

/** Where sync pages come from: a [TracksideClient], or a stand-in in tests. */
fun interface TracksideSyncSource {
    suspend fun page(cursor: String?, limit: Int): TracksideSyncPage
}

object TracksideSync {
    /** Far more than a library could need, so a server answering in circles can't keep a sync going. */
    private const val PAGE_LIMIT = 10_000

    /**
     * Brings a mirror up to date: the changes since its cursor, or a whole snapshot when it
     * has none or the server can't continue from it. The mirror passed in is never changed, so
     * when a sync fails partway it's still the one to keep.
     */
    suspend fun run(mirror: TracksideMirror, source: TracksideSyncSource, pageSize: Int = 2_000): TracksideMirror {
        val cursor = mirror.cursor ?: return snapshot(source, pageSize)
        return try {
            changes(mirror, cursor, source, pageSize)
        } catch (_: TracksideError.SyncReset) {
            snapshot(source, pageSize)
        }
    }

    private suspend fun snapshot(source: TracksideSyncSource, pageSize: Int): TracksideMirror {
        var fresh = TracksideMirror()
        var cursor: String? = null
        repeat(PAGE_LIMIT) {
            val page = source.page(cursor, pageSize)
            if (page.mode != "snapshot") throw TracksideError.NotTrackside
            fresh = fresh.applying(page)
            val next = page.nextCursor ?: throw TracksideError.NotTrackside
            if (page.complete == true) return fresh.copy(cursor = next)
            cursor = next
        }
        throw TracksideError.NotTrackside
    }

    private suspend fun changes(mirror: TracksideMirror, after: String, source: TracksideSyncSource, pageSize: Int): TracksideMirror {
        var updated = mirror
        var cursor = after
        repeat(PAGE_LIMIT) {
            val page = source.page(cursor, pageSize)
            // A server that answers a delta cursor with a snapshot has started over.
            if (page.mode != "delta") throw TracksideError.SyncReset
            updated = updated.applying(page)
            page.nextCursor?.let { cursor = it }
            updated = updated.copy(cursor = cursor)
            if (page.hasMore != true) return updated
        }
        throw TracksideError.NotTrackside
    }
}
