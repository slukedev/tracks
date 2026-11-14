package com.luke.tracks.playback

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** Something the player can play: a file on the phone or a song on your server. */
data class PlayableSong(
    /** Stable and unique: the MediaStore id ("local:123") or the server's ("subsonic:<server>:<id>"). */
    val mediaId: String,
    val uri: Uri,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val artworkUri: Uri?,
    val durationMs: Long?,
    /** The engine's song identity (folded title and artist), to tie it to the history. */
    val identity: String,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
)

enum class RepeatChoice { Off, All, One }

/** Why the queue is playing, for the Now Playing header ("Playing from On Repeat"). */
data class QueueOrigin(val title: String, val kind: Kind) {
    enum class Kind { Mix, Radio, Album, Artist, Playlist, Library, Search, Other }
}

data class PlayerState(
    val current: PlayableSong? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<PlayableSong> = emptyList(),
    val index: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatChoice = RepeatChoice.Off,
    val origin: QueueOrigin? = null,
    /** Fraction of the song that must play before it's kept (Last.fm's rule), 0 to 1. */
    val keepProgress: Float = 0f,
    val kept: Boolean = false,
    /** When the sleep timer stops playback, if it's set. */
    val sleepAtMs: Long? = null,
) {
    val upNext: List<PlayableSong> get() = queue.drop(index + 1)
}

/** Tracks' own player, as the UI drives it. Backed by Media3 in [PlaybackService]. */
interface PlayerController {
    val state: StateFlow<PlayerState>

    /** Live position, sampled while something plays; read it in small leaf composables only. */
    val position: StateFlow<Long>

    fun play(songs: List<PlayableSong>, startIndex: Int = 0, origin: QueueOrigin? = null, shuffle: Boolean = false)
    fun playNext(song: PlayableSong)
    fun addToQueue(song: PlayableSong)
    fun removeFromQueue(index: Int)
    fun moveInQueue(from: Int, to: Int)
    fun skipTo(index: Int)
    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(on: Boolean)
    fun setRepeat(choice: RepeatChoice)
    fun setSleepTimer(minutes: Int?)

    /** Starts Tracks Radio: endless, picked a song at a time from the history and library. */
    fun startRadio()
}

/** How a song the radio chose went, for it to learn from. */
enum class RadioListen { Skipped, MovedOn, Finished }

/**
 * Tracks Radio's songs: picked a few at a time from the history and the library by the
 * engine's `RadioStation`. The player asks for more as the queue runs low and reports how
 * each radio song went.
 */
interface RadioSource {
    /** The first songs of a fresh station; empty when there's nothing playable. */
    suspend fun start(): List<PlayableSong>

    /** The next [count] songs. */
    suspend fun more(count: Int = 5): List<PlayableSong>

    fun note(song: PlayableSong, listen: RadioListen)
}
