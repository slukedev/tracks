package com.luke.tracks.tv

import android.net.Uri
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RepeatChoice
import com.luke.tracks.ui.tv.link.LinkDirectory
import com.luke.tracks.ui.tv.link.LinkException
import com.luke.tracks.ui.tv.link.LinkOffer
import com.luke.tracks.ui.tv.link.LinkPoll
import com.luke.tracks.ui.tv.link.LinkProblem
import com.luke.tracks.ui.tv.link.LinkServer
import com.luke.tracks.ui.tv.link.TvLinker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant

/** A player that only remembers what it was asked, for key and screenshot tests. */
class FakePlayer(initial: PlayerState = PlayerState(), positionMs: Long = 0) : PlayerController {
    override val state = MutableStateFlow(initial)
    override val position = MutableStateFlow(positionMs)
    val calls = mutableListOf<String>()
    val seeks = mutableListOf<Long>()

    override fun play(songs: List<PlayableSong>, startIndex: Int, origin: QueueOrigin?, shuffle: Boolean) { calls += "play" }
    override fun playNext(song: PlayableSong) { calls += "playNext" }
    override fun addToQueue(song: PlayableSong) { calls += "addToQueue" }
    override fun removeFromQueue(index: Int) { calls += "remove $index" }
    override fun moveInQueue(from: Int, to: Int) { calls += "move" }
    override fun skipTo(index: Int) { calls += "skipTo $index" }
    override fun playPause() { calls += "playPause" }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        position.value = positionMs
    }
    override fun setShuffle(on: Boolean) { calls += "shuffle $on" }
    override fun setRepeat(choice: RepeatChoice) { calls += "repeat $choice" }
    override fun setSleepTimer(minutes: Int?) { calls += "sleep $minutes" }
    override fun startRadio() { calls += "radio" }
    override fun playMoreLikeThis() { calls += "moreLikeThis" }
    override fun playFromSearch(query: String) { calls += "search" }
}

object TvLinkDemo {
    val server = LinkServer("6f1c2a9e-3b7d-4e21-9a0c-5d8e7f6a1b2c", "Living Room Server", "http://192.168.1.20:4533".toHttpUrl())
    val other = LinkServer("0b4e1f2a-9c3d-4a5b-8e6f-7a1b2c3d4e5f", "Studio", "https://music.example.com".toHttpUrl())

    fun offer(code: String = "Q7RM-4X2K", expiresAt: Instant = Instant.parse("2099-01-01T00:00:00Z"), interval: Int = 5) = LinkOffer(
        server = server,
        deviceCode = "device-code-for-tests",
        userCode = code,
        linkUrl = "tracks://link?server=${server.id}&code=$code",
        intervalSeconds = interval,
        expiresAt = expiresAt,
    )

    val songs: List<PlayableSong> by lazy {
        TvDemo.albums.mapIndexed { i, album ->
            PlayableSong(
                mediaId = "server:demo:$i",
                uri = Uri.EMPTY,
                title = listOf("Saltwater", "Northern Lights", "Paper Planes", "Slow Burn", "Gold Hour", "Undertow")[i % 6],
                artistName = album.artist,
                albumTitle = album.title,
                artworkUri = null,
                durationMs = 200_000L + i * 7_000,
                identity = "demo:$i",
            )
        }
    }
}

/** A linker that answers from a script, one answer per poll, the last one repeating. */
class FakeLinker(
    private val offers: List<LinkOffer> = listOf(TvLinkDemo.offer()),
    private val polls: List<LinkPoll> = listOf(LinkPoll.Pending),
    private val startProblem: LinkProblem? = null,
) : TvLinker {
    var starts = 0
    var pollCount = 0
    val approved = mutableListOf<String>()

    override suspend fun start(server: LinkServer): LinkOffer {
        startProblem?.let { throw LinkException(it) }
        return offers[minOf(starts++, offers.lastIndex)]
    }

    override suspend fun poll(offer: LinkOffer): LinkPoll = polls[minOf(pollCount++, polls.lastIndex)]

    override suspend fun approve(serverId: String, userCode: String): String {
        approved += userCode
        return "Living Room TV"
    }
}

class FakeDirectory(servers: List<LinkServer>, private val found: LinkServer? = null) : LinkDirectory {
    override val nearby: Flow<List<LinkServer>> = flowOf(servers)
    override suspend fun lookUp(address: String): LinkServer = found ?: throw LinkException(LinkProblem.NotTrackside)
}

fun playerState(song: PlayableSong = TvLinkDemo.songs[0], playing: Boolean = true, queue: List<PlayableSong> = TvLinkDemo.songs, origin: QueueOrigin? = QueueOrigin("On Repeat", QueueOrigin.Kind.Mix)) =
    PlayerState(
        current = song,
        isPlaying = playing,
        durationMs = song.durationMs ?: 0,
        queue = queue,
        index = queue.indexOf(song).coerceAtLeast(0),
        origin = origin,
    )
