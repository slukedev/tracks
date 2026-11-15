package com.luke.tracks.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.tracks_engine.Listen

/** A station with nothing to play, for previews and tests, showing [initial]. */
open class NoRadio(initial: RadioState = RadioState()) : RadioSource {
    override val state: StateFlow<RadioState> = MutableStateFlow(initial)
    override suspend fun start(startingWith: PlayableSong?): List<PlayableSong> = emptyList()
    override suspend fun next(): PlayableSong? = null
    override fun knows(song: PlayableSong) = false
    override fun note(song: PlayableSong, listen: Listen) = Unit
    override fun loved(song: PlayableSong) = Unit
    override fun moreLikeThis(song: PlayableSong) = Unit
    override fun replayed(song: PlayableSong) = Unit
    override fun removed(song: PlayableSong) = Unit
    override fun heardLess(identity: String) = Unit
    override fun release(song: PlayableSong) = Unit
    override fun recordSkip(song: PlayableSong) = Unit
}
