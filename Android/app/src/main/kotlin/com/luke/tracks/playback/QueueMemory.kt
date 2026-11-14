package com.luke.tracks.playback

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last queue, so playback can pick up where it was: from the media controls after a
 * restart, from a Bluetooth play button, from Android Auto.
 */
class QueueMemory(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    @Serializable
    private data class Saved(val songs: List<SavedSong>, val index: Int, val positionMs: Long, val origin: String? = null, val originKind: String? = null)

    @Serializable
    private data class SavedSong(
        val mediaId: String, val uri: String, val title: String, val artist: String, val album: String? = null,
        val artwork: String? = null, val durationMs: Long? = null, val identity: String, val track: Int? = null,
        val year: Int? = null, val genre: String? = null,
    )

    data class Restored(val songs: List<PlayableSong>, val index: Int, val positionMs: Long, val origin: QueueOrigin?)

    fun save(songs: List<PlayableSong>, index: Int, positionMs: Long, origin: QueueOrigin?) {
        if (songs.isEmpty()) return
        // Up to a couple of hundred around the song playing now.
        val from = (index - 50).coerceAtLeast(0)
        val window = songs.subList(from, (from + 200).coerceAtMost(songs.size))
        val saved = Saved(
            songs = window.map { SavedSong(it.mediaId, it.uri.toString(), it.title, it.artistName, it.albumTitle, it.artworkUri?.toString(), it.durationMs, it.identity, it.trackNumber, it.year, it.genre) },
            index = index - from,
            positionMs = positionMs,
            origin = origin?.title,
            originKind = origin?.kind?.name,
        )
        prefs.edit().putString(KEY, json.encodeToString(Saved.serializer(), saved)).apply()
    }

    fun restore(): Restored? {
        val raw = prefs.getString(KEY, null) ?: return null
        val saved = runCatching { json.decodeFromString(Saved.serializer(), raw) }.getOrNull() ?: return null
        if (saved.songs.isEmpty()) return null
        val songs = saved.songs.map {
            PlayableSong(it.mediaId, Uri.parse(it.uri), it.title, it.artist, it.album, it.artwork?.let(Uri::parse), it.durationMs, it.identity, it.track, it.year, it.genre)
        }
        val origin = saved.origin?.let { title ->
            QueueOrigin(title, runCatching { QueueOrigin.Kind.valueOf(saved.originKind.orEmpty()) }.getOrDefault(QueueOrigin.Kind.Other))
        }
        return Restored(songs, saved.index.coerceIn(0, songs.lastIndex), saved.positionMs, origin)
    }

    private companion object {
        const val KEY = "queue"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
