package com.luke.tracks.speakers

import android.content.Context
import com.luke.tracks.AppGraph
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RepeatChoice
import com.luke.tracks.playback.hasArtist
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Your servers' speakers for this process, once the Trackside services start. */
object Speakers {
    private val installed = java.util.WeakHashMap<AppGraph, SpeakersRepository>()

    fun install(graph: AppGraph, servers: ServerLibrary): SpeakersRepository = synchronized(installed) {
        installed.getOrPut(graph) {
            SpeakersRepository(
                scope = graph.scope,
                clientFor = { id -> servers.tracksideClient(id)?.let(::TracksideSpeakerClient) },
                store = PreferencesSpeakerStore(graph.context),
            ).also { repository ->
                repository.watchServers(
                    servers.entries
                        .map { list -> list.filter { it.server.isTrackside }.map { SpeakerServer(it.server.id, it.server.name) } }
                        .distinctUntilChanged(),
                )
                // Music starting on the phone takes the players back from the speaker.
                graph.scope.launch {
                    graph.player.state.map { it.isPlaying }.distinctUntilChanged().drop(1).filter { it }.collect {
                        if (repository.state.value.active != null) repository.release()
                    }
                }
            }
        }
    }

    fun of(graph: AppGraph): SpeakersRepository? = synchronized(installed) { installed[graph] }
}

/** The chosen speaker in a small preferences file, so it's still chosen after the system closes the app. */
class PreferencesSpeakerStore(context: Context) : ActiveSpeakerStore {
    private val prefs = context.getSharedPreferences("speakers", Context.MODE_PRIVATE)

    override fun load(): SpeakerKey? {
        val server = prefs.getString(SERVER, null) ?: return null
        val speaker = prefs.getString(SPEAKER, null) ?: return null
        return SpeakerKey(server, speaker)
    }

    override fun save(key: SpeakerKey?) {
        val editor = prefs.edit()
        if (key == null) editor.clear() else editor.putString(SERVER, key.serverId).putString(SPEAKER, key.speakerId)
        editor.apply()
    }

    private companion object {
        const val SERVER = "server"
        const val SPEAKER = "speaker"
    }
}

/** The phone's own player, paused while a speaker plays its queue. */
class PlayerHandoff(private val player: PlayerController) : LocalHandoff {
    override fun pauseForSpeaker(): Boolean {
        val playing = player.state.value.isPlaying || player.state.value.isLoading
        if (playing) player.playPause()
        return playing
    }

    override fun resumeHere() {
        if (!player.state.value.isPlaying) player.playPause()
    }
}

/** What the phone is playing, as a speaker on one server can play it. */
object CastQueue {
    /**
     * The queue from the song on now, as the server's own songs: other sources' songs are left
     * out, and the start moves to the next song it has when it doesn't have this one. Null
     * when none of the queue is on [serverId].
     */
    fun request(state: PlayerState, positionMs: Long, serverId: String): TracksideSpeakerPlayRequest? {
        val ids = state.queue.map { song -> ServerLibrary.parseMediaId(song.mediaId)?.takeIf { it.first == serverId }?.second }
        if (ids.all { it == null }) return null
        val current = state.index.coerceIn(0, (state.queue.size - 1).coerceAtLeast(0))
        val startAt = (current until ids.size).firstOrNull { ids[it] != null } ?: ids.indexOfFirst { it != null }
        val startsOnCurrent = startAt == current
        // Up to the limit, from a little before the start so going back still works there.
        val kept = ids.withIndex().filter { it.value != null }
        val startPosition = kept.indexOfFirst { it.index == startAt }
        val from = (startPosition - BEFORE).coerceAtLeast(0).let { minOf(it, (kept.size - TracksideSpeakerPlayRequest.MAX_ITEMS).coerceAtLeast(0)) }
        val window = kept.drop(from).take(TracksideSpeakerPlayRequest.MAX_ITEMS)
        return TracksideSpeakerPlayRequest(
            items = window.map { TracksideSpeakerPlayRequest.Item(songId = it.value) },
            mode = "replace",
            startIndex = startPosition - from,
            positionMs = if (startsOnCurrent) positionMs.coerceAtLeast(0) else 0,
            shuffle = state.shuffle,
            repeat = when (state.repeat) {
                RepeatChoice.Off -> "off"
                RepeatChoice.All -> "all"
                RepeatChoice.One -> "one"
            },
            context = state.origin?.let { TracksideSpeaker.Context(kind = kind(it.kind), name = it.title) },
        )
    }

    /** What the speaker will show at once, before the server answers. */
    fun preview(state: PlayerState, request: TracksideSpeakerPlayRequest): TracksideSpeaker.NowPlaying? {
        val songId = request.items.getOrNull(request.startIndex ?: 0)?.songId ?: return null
        val song = state.queue.firstOrNull { ServerLibrary.parseMediaId(it.mediaId)?.second == songId } ?: return null
        return preview(song, songId)
    }

    private fun preview(song: PlayableSong, songId: String) = TracksideSpeaker.NowPlaying(
        title = song.title,
        artist = song.artistName.takeIf { song.hasArtist },
        album = song.albumTitle,
        durationMs = song.durationMs,
        songId = songId,
        origin = "queue",
    )

    private fun kind(kind: QueueOrigin.Kind) = when (kind) {
        QueueOrigin.Kind.Mix -> "mix"
        QueueOrigin.Kind.Radio -> "station"
        QueueOrigin.Kind.Album -> "album"
        QueueOrigin.Kind.Artist -> "artist"
        QueueOrigin.Kind.Playlist -> "playlist"
        QueueOrigin.Kind.Search -> "search"
        QueueOrigin.Kind.Library, QueueOrigin.Kind.Other -> "other"
    }

    private const val BEFORE = 50
}
