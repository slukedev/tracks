package com.luke.tracks.playback

/** A station with nothing to play, for previews and tests. */
class NoRadio : RadioSource {
    override suspend fun start(): List<PlayableSong> = emptyList()
    override suspend fun more(count: Int): List<PlayableSong> = emptyList()
    override fun note(song: PlayableSong, listen: RadioListen) = Unit
}
