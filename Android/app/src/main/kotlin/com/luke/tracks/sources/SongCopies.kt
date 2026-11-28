package com.luke.tracks.sources

import com.luke.tracks.library.LibraryTrack
import com.luke.tracks.playback.PlayableSong

/**
 * Every copy of a song across the phone's files and the servers, by its identity. The library
 * keeps one copy of each song; this keeps them all, rebuilt only when a list changes.
 */
class SongCopies(private val lists: () -> List<List<LibraryTrack>>) {
    private var builtFrom: List<List<LibraryTrack>> = emptyList()
    private var byIdentity: Map<String, List<PlayableSong>> = emptyMap()

    /** The song and its other copies, the song first. */
    fun of(song: PlayableSong): List<PlayableSong> {
        if (song.identity.isEmpty()) return listOf(song)
        return (listOf(song) + of(song.identity)).distinctBy { it.mediaId }
    }

    /** Every copy of the song with this identity. */
    @Synchronized
    fun of(identity: String): List<PlayableSong> {
        if (identity.isEmpty()) return emptyList()
        rebuildIfChanged()
        return byIdentity[identity].orEmpty()
    }

    /** Builds the lookup now, off the main thread, so the first song asked for doesn't wait on it. */
    @Synchronized
    fun warm() = rebuildIfChanged()

    private fun rebuildIfChanged() {
        val current = lists()
        if (current.size == builtFrom.size && current.indices.all { current[it] === builtFrom[it] }) return
        byIdentity = current.asSequence().flatten().map { it.song }.filter { it.identity.isNotEmpty() }.groupBy { it.identity }
        builtFrom = current
    }
}
