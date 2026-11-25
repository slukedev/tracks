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
    @Synchronized
    fun of(song: PlayableSong): List<PlayableSong> {
        if (song.identity.isEmpty()) return listOf(song)
        val current = lists()
        if (current.size != builtFrom.size || current.indices.any { current[it] !== builtFrom[it] }) {
            byIdentity = current.asSequence().flatten().map { it.song }.filter { it.identity.isNotEmpty() }.groupBy { it.identity }
            builtFrom = current
        }
        return (listOf(song) + byIdentity[song.identity].orEmpty()).distinctBy { it.mediaId }
    }
}
