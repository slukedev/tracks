package com.luke.tracks.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A player that plays nothing, for previews and tests. */
class IdlePlayer(initial: PlayerState = PlayerState()) : PlayerController {
    override val state: StateFlow<PlayerState> = MutableStateFlow(initial)
    override val position: StateFlow<Long> = MutableStateFlow(initial.positionMs)
    override fun play(songs: List<PlayableSong>, startIndex: Int, origin: QueueOrigin?, shuffle: Boolean) = Unit
    override fun playNext(song: PlayableSong) = Unit
    override fun addToQueue(song: PlayableSong) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun moveInQueue(from: Int, to: Int) = Unit
    override fun skipTo(index: Int) = Unit
    override fun playPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffle(on: Boolean) = Unit
    override fun setRepeat(choice: RepeatChoice) = Unit
    override fun setSleepTimer(minutes: Int?) = Unit
    override fun startRadio() = Unit
}
