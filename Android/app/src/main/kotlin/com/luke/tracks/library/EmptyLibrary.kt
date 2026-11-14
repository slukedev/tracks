package com.luke.tracks.library

import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A library with nothing in it, for previews and tests. */
class EmptyLibrary : MusicLibrary {
    override val snapshot: StateFlow<LibrarySnapshot> = MutableStateFlow(LibrarySnapshot())
    override suspend fun refresh() = Unit
    override fun find(identity: String): PlayableSong? = null
    override suspend fun search(text: String): List<PlayableSong> = emptyList()
}
