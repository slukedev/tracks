package com.luke.tracks.playback

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import uniffi.tracks_engine.Listen
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioSteering

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

/** The title to show: files without tags (ringtones, voice notes) can come with none. */
val PlayableSong.titleLabel: String get() = title.ifBlank { "Untitled" }

/** What the library calls a file's artist when its tags don't say. */
const val UNKNOWN_ARTIST = "Unknown artist"

/** Whether the artist is really known: MediaStore says unknown as "<unknown>", the library as [UNKNOWN_ARTIST]. */
val PlayableSong.hasArtist: Boolean get() = artistName.isNotBlank() && artistName != "<unknown>" && artistName != UNKNOWN_ARTIST

/** The artist to show. */
val PlayableSong.artistLabel: String get() = if (hasArtist) artistName else UNKNOWN_ARTIST

enum class RepeatChoice { Off, All, One }

/** Why the queue is playing, for the Now Playing header ("Playing from On Repeat"). */
data class QueueOrigin(val title: String, val kind: Kind) {
    enum class Kind { Mix, Radio, Album, Artist, Playlist, Library, Search, Other }
}

data class PlayerState(
    val current: PlayableSong? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Meant to be playing and waiting on the song: buffering, or a song being got ([SongsToGet]). */
    val isLoading: Boolean = false,
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

    /** The current song's length as far as it's known: the player's word, else the library's. */
    val lengthMs: Long? get() = ListenCounter.knownLength(durationMs) ?: ListenCounter.knownLength(current?.durationMs)
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

    /** Tracks Radio plays more like the song on now, from the next song. */
    fun playMoreLikeThis()
}

/** What Tracks Radio is doing, for the player's screens. */
data class RadioState(
    /** Songs the station put in the queue, by media id, with why each came up. */
    val picks: Map<String, RadioReason?> = emptyMap(),
    /** What it last changed course for, after a run of skips or a song let play. */
    val steering: RadioSteering? = null,
    /** Songs asked for more like this listen, by identity, so asking shows as done. */
    val askedMoreLike: Set<String> = emptySet(),
) {
    fun isPick(song: PlayableSong) = song.mediaId in picks
    fun reason(song: PlayableSong): RadioReason? = picks[song.mediaId]
}

/**
 * Tracks Radio's songs, picked one at a time by the engine's `RadioStation` as the song before
 * starts, so every pick follows what was just skipped, let play or loved. The player asks for
 * the next song as each one starts and says how each went.
 */
interface RadioSource {
    val state: StateFlow<RadioState>

    /** The first two songs of a fresh station, opening on [startingWith] when given. Empty when there's nothing to play. */
    suspend fun start(startingWith: PlayableSong? = null): List<PlayableSong>

    /** The song after the ones queued. */
    suspend fun next(): PlayableSong?

    /** Whether the song is one of the station's own, so what's said about it can steer. */
    fun knows(song: PlayableSong): Boolean

    /** How a song gave way: skipped, moved on from or let play. */
    fun note(song: PlayableSong, listen: Listen)

    /** Liked while it played. */
    fun loved(song: PlayableSong)

    /** Asked for more like it: as a like, without liking it. */
    fun moreLikeThis(song: PlayableSong)

    /** Gone back to from well into it. */
    fun replayed(song: PlayableSong)

    /** Taken out of Up Next: heard as a skip. */
    fun removed(song: PlayableSong)

    /** Asked to hear less of: heard as a skip, and left out from now on. */
    fun heardLess(identity: String)

    /** A pick that never played, made way for another. */
    fun release(song: PlayableSong)

    /** Remembers a song skipped anywhere in Tracks, so the radio plays it less. */
    fun recordSkip(song: PlayableSong)
}
