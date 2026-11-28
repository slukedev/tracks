package com.luke.tracks.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.luke.tracks.MainActivity
import com.luke.tracks.R
import com.luke.tracks.data.HistoryRepository
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.discover.newSong
import com.luke.tracks.discover.serverPicks
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.net.Http
import com.luke.tracks.net.Logs
import com.luke.tracks.sources.PlayResolver
import com.luke.tracks.sources.SkippedSongs
import com.luke.tracks.sources.SongCopies
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.sources.WantedSong
import com.luke.tracks.sources.sources
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.streaming.CatalogPlays
import com.luke.tracks.streaming.Streaming
import com.luke.tracks.streaming.RelayStatus
import com.luke.tracks.streaming.playResolver
import com.luke.tracks.playback.radio.RadioOnRelay
import com.luke.tracks.playback.radio.RadioPicks
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.Listen
import uniffi.tracks_engine.radioListen
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Commands Tracks' controllers and the media controls send beyond Media3's own. */
object TracksCommands {
    const val LIKE = "tracks.like"
    const val SHUFFLE = "tracks.shuffle"
    const val REPEAT = "tracks.repeat"
    const val SLEEP = "tracks.sleep"
    const val RADIO = "tracks.radio"
    const val MORE_LIKE_THIS = "tracks.moreLikeThis"
    const val MINUTES = "minutes"

    val all = listOf(LIKE, SHUFFLE, REPEAT, SLEEP, RADIO, MORE_LIKE_THIS).map { SessionCommand(it, Bundle.EMPTY) }
    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
}

/**
 * Plays your music and offers it to the system: the media notification and lock screen,
 * Bluetooth and headset buttons, Android Auto, Wear OS, Assistant and the output switcher.
 * It also keeps each song once it has played long enough, keeps Tracks Radio going, and runs
 * the sleep timer, so all of that works whoever is in control.
 */
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exo: ExoPlayer
    private lateinit var player: FreshShufflePlayer
    private lateinit var tree: BrowseTree
    private lateinit var memory: QueueMemory
    private var session: MediaLibrarySession? = null
    private val graph get() = applicationContext.graph

    private var liked: Set<String> = emptySet()
    private val searches = HashMap<String, List<PlayableSong>>()

    // The play under way.
    private var counter: ListenCounter? = null
    private var counterItem: MediaItem? = null
    private var playId: String = HistoryRepository.newId()
    private var playStartedAt = 0L
    private var ticking: Job? = null
    private var lastTick = 0L

    // Tracks Radio.
    private var fetchingRadio = false
    /** The station's picks, each played from the first place in Play From that can play it here. */
    private lateinit var radioPicks: RadioPicks
    /** Nothing ahead could be picked: the station has run out, said once. */
    private var radioRanOut = false
    /** Where in the queue the song playing sat, to tell going back from moving on. */
    private var playingIndex = 0

    private var sleepJob: Job? = null
    private var failuresInARow = 0

    /** Songs queued before they're in your library, got as they're reached. */
    private lateinit var getting: GetAsReached

    // Copies across sources.
    private val copies = SongCopies {
        (graph.library as? CombinedLibrary)?.let { listOf(it.device.tracks.value, it.servers.tracks.value) }.orEmpty()
    }
    /** The song whose copies have been tried after failing, by identity, and the ones tried. */
    private var triedFor: String? = null
    private val tried = mutableSetOf<String>()
    /** The song on when songs ahead were last checked, so each song checks once. */
    private var checkedAheadOf: String? = null
    /** Nothing ahead could play: when this song ends the music stops, and the message says why. */
    private var nothingAfter: String? = null
    private var nothingAfterCount = 0

    /**
     * The session's player while a queue with any streaming service's song plays through the
     * relay; null without Trackside. [player] plays everything else.
     */
    private var relayPlayer: RelayPlayer? = null

    /**
     * The relay stopped working under Tracks Radio (a session error, music services gone): it
     * plays on here from your own copies until you start something new or the relay plays again.
     */
    private var relayDown = false

    /** Songs coming up looked up in Play From, a couple ahead, on the phone's own player. */
    private var lookingAhead: Job? = null
    private var lookedAheadOf: String? = null

    /** The relay's server's own client, which holds its pinned certificates. */
    @Volatile private var relayHttp: okhttp3.OkHttpClient = Http.base

    private val usingRelay: Boolean get() = relayPlayer != null && session?.player === relayPlayer

    /** Whichever player the session has now: the phone's own, or the relay's. */
    private val active: Player get() = session?.player ?: player

    /** Files on the phone, and server songs signed only as each request goes out ([com.luke.tracks.net.ServerLinks]). */
    private val dataSources by lazy { DefaultDataSource.Factory(this, OkHttpDataSource.Factory(Http.media)) }

    override fun onCreate() {
        super.onCreate()
        memory = QueueMemory(this)
        tree = BrowseTree(graph) { ::player.isInitialized && player.isPlaying }
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                // Songs still to get wait in the queue, loading, until they're got.
                WaitingMediaSource.Factory(
                    DefaultMediaSourceFactory(dataSources)
                        // A dropped connection resumes from where the song broke off, a few times over.
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)),
                ),
            )
            .build()
        player = FreshShufflePlayer(exo)
        player.addListener(listener)
        getting = GetAsReached(
            queue = gettingQueue,
            scope = scope,
            // Get taken out of Play From since the song was queued: it isn't got after all.
            get = { song, status ->
                if (graph.playResolver.gets()) graph.serverPicks.getToPlay(song, status) else ToPlay.Failed(PlayResolver.cantPlay(song.title))
            },
            cover = { song ->
                SongsToGet.parse(song.mediaId)?.let { (server, _) ->
                    graph.serverPicks.newCover(ServerSong(server, newSong(song.title, song.artistName, song.albumTitle, song.durationMs)))?.let(Uri::parse)
                }
            },
            // Said the way songs passed over are, and the next song plays.
            failed = { message -> graph.sources.reportSkipped(SkippedSongs(1, message)) },
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setData(Uri.parse("tracks://nowplaying")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(dataSources).build()))
            .setMediaButtonPreferences(buttons())
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_tracks_launcher_monochrome) })
        radioPicks = RadioPicks(scope, graph.radio, resolve = { picked, accept -> placeFor(picked, accept) })
        Streaming.of(graph)?.let(::startRelay)
        // A song asked to hear less of turns the station: the pick made ready behind the next makes way.
        scope.launch { graph.settings.settings.map { it.hiddenSongs }.distinctUntilChanged().collect { radioPicks.steered() } }

        scope.launch {
            graph.settings.settings.collect { settings ->
                if (settings.likedSongs != liked) {
                    liked = settings.likedSongs
                    refreshButtons()
                }
            }
        }
        // Shuffle and repeat have no place on a station without an end.
        scope.launch { PlaybackBridge.origin.collect { refreshButtons() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends the session; while playing, the music goes on.
        if (!active.playWhenReady || active.mediaItemCount == 0) {
            if (usingRelay) Streaming.of(graph)?.relay?.stop()
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        getting.stop()
        remember()
        finishPlay()
        PlaybackBridge.sleepAtMs.value = null
        session?.release()
        session = null
        relayPlayer?.release()
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    // MARK: Listening and keeping

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A service's song can only play through the relay: the whole queue moves there.
            if (mediaItem != null && CatalogMedia.isCatalog(mediaItem.mediaId) && relayPlays && !usingRelay) {
                // The song that gave way is heard as any is; the relay takes the queue at this boundary.
                noteLeaving(reason)
                // Not from inside the player's own callback: once it's done telling everyone.
                android.os.Handler(mainLooper).post { moveQueueToRelay() }
                return
            }
            val identity = mediaItem?.toPlayableSong()?.identity
            if (identity != triedFor) {
                triedFor = null
                tried.clear()
            }
            if (mediaItem?.mediaId != nothingAfter) nothingAfter = null
            noteLeaving(reason)
            finishPlay()
            startPlay(mediaItem)
            playingIndex = player.currentMediaItemIndex
            topUpRadio()
            lookAhead()
            refreshButtons()
            remember()
            getting.check()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // A pick taken out of Up next: another takes its place.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                playingIndex = player.currentMediaItemIndex
                topUpRadio()
                lookAhead()
                getting.check()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = getting.check()

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Previous well into a station's song starts it over: wanting to hear it again.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItemIndex == new.mediaItemIndex &&
                old.positionMs >= RESTARTS_AFTER_MS && new.positionMs < 1_000 && isRadio()
            ) {
                player.currentMediaItem?.toPlayableSong()?.let(graph.radio::replayed)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A service's song never loads here: the relay is taking the queue.
            if (player.currentMediaItem?.mediaId?.let(CatalogMedia::isCatalog) == true && relayPlayer != null && !relayDown) return
            if (!playAnotherCopy()) skipUnplayable(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && nothingAfter != null && nothingAfter == player.currentMediaItem?.mediaId) {
                graph.sources.reportSkipped(SkippedSongs(nothingAfterCount, nothingLeft = true))
                nothingAfter = null
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                failuresInARow = 0
                startTicking()
            } else {
                stopTicking()
                remember()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()
        override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()
    }

    /**
     * A song that won't play (a format this phone can't decode, a file gone, a server down)
     * stops Media3 where it is. Moves on to the next one and says so, and stops after a whole
     * queue's worth of failures rather than spinning.
     */
    private fun skipUnplayable(error: PlaybackException) {
        Logs.w("Couldn't play ${player.currentMediaItem?.mediaId}", error)
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "that song"
        failuresInARow++
        val moveOn = player.hasNextMediaItem() && failuresInARow < player.mediaItemCount
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        // Its source can't play now (a server offline): the same quiet message as songs skipped ahead.
        if (moveOn && !graph.sources.canPlay(mediaId)) {
            graph.sources.reportSkipped(SkippedSongs(1, graph.sources.whyUnavailable(mediaId)))
        } else if (CatalogMedia.isCatalog(mediaId)) {
            say(PlayResolver.cantPlay(player.currentMediaItem?.mediaMetadata?.title?.toString()))
        } else {
            say(if (moveOn) "Couldn't play $title. Playing the next song." else "Couldn't play $title.")
        }
        if (!moveOn) return
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    // MARK: Copies across sources

    /**
     * The copy of a song to queue. With every source on, the first in the Play From order that
     * can play now; filtered to one source, the one asked for, unless it can't play. A service's
     * song stays as it is: Play From chose it when it was asked for.
     */
    private fun preferred(item: MediaItem): MediaItem {
        if (CatalogMedia.isCatalog(item.mediaId)) return item
        val song = item.toPlayableSong()
        val all = copies.of(song)
        if (all.size < 2) return item
        val registry = graph.sources
        if (registry.selection.value != SourceSelection.All && registry.canPlay(song.mediaId)) return item
        val best = graph.playResolver.resolveNow(WantedSong(song.title, song.artistName, song.albumTitle, song.durationMs, song.identity, known = all)).song ?: return item
        return if (best.mediaId == song.mediaId) item else best.toMediaItem()
    }

    private suspend fun preferred(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.Default) { items.map(::preferred) }

    /**
     * Without a relay, a service's song can't play here: it's left out and said so once, never
     * swapped for another copy or got instead.
     */
    private fun withoutServiceSongs(items: List<MediaItem>): List<MediaItem> {
        if (relayPlayer != null) return items
        val (services, rest) = items.partition { CatalogMedia.isCatalog(it.mediaId) }
        if (services.isEmpty()) return items
        say(PlayResolver.cantPlay(services.singleOrNull()?.mediaMetadata?.title?.toString()))
        if (rest.isEmpty()) throw UnsupportedOperationException("A service's songs, with no relay to play them")
        return rest
    }

    /** The song as Play From is asked for it: what it is, and every copy of it you have. */
    private fun wanted(song: PlayableSong) =
        WantedSong(song.title, song.artistName, song.albumTitle, song.durationMs, song.identity, known = copies.of(song))

    /** Whether the relay can play a service's song now: there is one, and it hasn't failed under Tracks Radio. */
    private val relayPlays: Boolean get() = relayPlayer != null && !relayDown

    /** What the player in use can play: the relay its own server's songs; the phone anything but a service's song without a relay. */
    private fun playsHere(): (PlayableSong) -> Boolean {
        val relay = relayPlayer
        val serverId = relay?.serverId
        if (usingRelay && serverId != null) return { RelayPlayer.playsOn(serverId, it.mediaId) }
        val services = relayPlays
        return { services || !CatalogMedia.isCatalog(it.mediaId) }
    }

    /** What the phone's own player can play, without the relay. */
    private val playsOnPhone: (PlayableSong) -> Boolean = { !CatalogMedia.isCatalog(it.mediaId) }

    /**
     * Where a song plays from: the first place in Play From that [accept] takes, looked up off
     * the main thread when a service comes before your copies. A service's song and one being got
     * stay as they are; filtered to one source, the copy asked for plays while it can.
     */
    private suspend fun placeFor(song: PlayableSong, accept: (PlayableSong) -> Boolean): PlayableSong? {
        if (CatalogMedia.isCatalog(song.mediaId) || song.isToGet || song.identity.isEmpty()) return song.takeIf(accept)
        val registry = graph.sources
        if (registry.selection.value != SourceSelection.All && registry.canPlay(song.mediaId) && accept(song)) return song
        return withContext(Dispatchers.Default) {
            graph.playResolver.resolve(wanted(song), lookups = relayPlays || usingRelay, accept = accept).song
        }
    }

    private suspend fun placeFor(item: MediaItem, accept: (PlayableSong) -> Boolean): MediaItem? =
        placeFor(item.toPlayableSong(), accept)?.let { if (it.mediaId == item.mediaId) item else it.toMediaItem() }

    /**
     * The next couple of songs on the phone's own player, looked up in Play From while the one
     * before plays, so one a service comes first for is ready when it's reached. One that will
     * play through the relay has a session opened for it ahead, so the change of player is quick.
     */
    private fun lookAhead() {
        if (usingRelay) {
            relayPlayer?.prefetch()
            return
        }
        val key = "${player.currentMediaItemIndex}:${player.currentMediaItem?.mediaId}:${player.mediaItemCount}"
        if (key == lookedAheadOf && lookingAhead?.isActive == true) return
        lookedAheadOf = key
        lookingAhead?.cancel()
        val picks = graph.radio.state.value.picks
        val ahead = upNextIndices().take(AHEAD_SONGS).map { it to player.getMediaItemAt(it) }
        val accept = playsHere()
        lookingAhead = scope.launch {
            for ((index, item) in ahead) {
                if (CatalogMedia.isCatalog(item.mediaId)) {
                    CatalogMedia.parse(item.mediaId)?.first?.let { Streaming.of(graph)?.relay?.warm(it) }
                    continue
                }
                if (SongsToGet.isToGet(item) || item.mediaId in picks) continue
                val song = item.toPlayableSong()
                if (!withContext(Dispatchers.Default) { graph.playResolver.looksUp(wanted(song)) }) continue
                val place = placeFor(item, accept) ?: continue
                if (place.mediaId == item.mediaId || usingRelay) continue
                if (index >= player.mediaItemCount || player.getMediaItemAt(index).mediaId != item.mediaId) continue
                player.replaceMediaItem(index, place)
                CatalogMedia.parse(place.mediaId)?.first?.let { Streaming.of(graph)?.relay?.warm(it) }
            }
        }
    }

    /** Another copy of a song that can play now, in the Play From order, other than the ones in [besides]. */
    private fun otherCopy(song: PlayableSong, besides: Set<String>): PlayableSong? {
        if (CatalogMedia.isCatalog(song.mediaId)) return null
        val registry = graph.sources
        return registry.ranked(copies.of(song)) { it.mediaId }
            .firstOrNull { it.mediaId !in besides && it.mediaId != song.mediaId && registry.canPlay(it.mediaId) }
    }

    /**
     * The song on failed: plays it from its next copy, from where it had got to. False when
     * every copy has been tried, or it's one of Tracks Radio's picks, which the station
     * replaces itself.
     */
    private fun playAnotherCopy(): Boolean {
        val item = player.currentMediaItem ?: return false
        if (item.mediaId in graph.radio.state.value.picks) return false
        val song = item.toPlayableSong()
        if (triedFor != song.identity) {
            triedFor = song.identity
            tried.clear()
        }
        tried += song.mediaId
        val copy = otherCopy(song, tried) ?: return false
        Logs.w("Couldn't play ${song.mediaId}; playing ${copy.mediaId}")
        tried += copy.mediaId
        val position = player.currentPosition
        player.replaceMediaItem(player.currentMediaItemIndex, copy.toMediaItem())
        player.seekTo(player.currentMediaItemIndex, position)
        player.prepare()
        player.play()
        return true
    }

    /**
     * Half a minute before the song on ends: songs next in Up Next that can't play now are
     * played from another copy where there is one, and otherwise left out, with one quiet
     * message. Only the run straight after the song on: songs further on may be back by then.
     */
    private fun checkAhead() {
        val registry = graph.sources
        val ahead = upNextIndices()
        val gone = mutableListOf<Int>()
        val reasons = mutableSetOf<String?>()
        for (index in ahead) {
            val item = player.getMediaItemAt(index)
            if (registry.canPlay(item.mediaId)) break
            val isPick = item.mediaId in graph.radio.state.value.picks
            val copy = if (isPick) null else otherCopy(item.toPlayableSong(), emptySet())
            if (copy != null) {
                player.replaceMediaItem(index, copy.toMediaItem())
                break
            }
            gone += index
            reasons += registry.whyUnavailable(item.mediaId)
        }
        if (gone.isEmpty()) return
        val reason = reasons.singleOrNull()
        val everything = gone.size == ahead.size && !isRadio()
        gone.sortedDescending().forEach { index ->
            player.getMediaItemAt(index).toPlayableSong().takeIf { it.mediaId in graph.radio.state.value.picks }?.let(graph.radio::release)
            player.removeMediaItem(index)
        }
        if (everything) {
            nothingAfter = player.currentMediaItem?.mediaId
            nothingAfterCount = gone.size
        } else {
            graph.sources.reportSkipped(SkippedSongs(gone.size, reason))
        }
    }

    /** The queue after the song on, in the order it will play, shuffled or not. */
    private fun upNextIndices(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        fun after(index: Int) = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
        return generateSequence(after(player.currentMediaItemIndex), ::after).toList()
    }

    private fun checkAheadIfDue() {
        if (usingRelay) return sendAheadIfDue()
        val current = player.currentMediaItem ?: return
        val key = "${player.currentMediaItemIndex}:${current.mediaId}"
        if (checkedAheadOf == key) return
        val length = ListenCounter.knownLength(exo.duration) ?: return
        if (length - exo.currentPosition > AHEAD_MS) return
        checkedAheadOf = key
        checkAhead()
    }

    /**
     * Half a minute before the relay's song ends, the song waiting next is sent to the server,
     * so it follows on without a gap. Until then it can still make way (More Like This, Up Next).
     */
    private fun sendAheadIfDue() {
        val relay = relayPlayer ?: return
        if (!relay.holding || relay.currentMediaItemIndex < relay.sentCount - 1) return
        val current = relay.currentMediaItem ?: return
        val key = "relay:${relay.currentMediaItemIndex}:${current.mediaId}"
        if (checkedAheadOf == key) return
        val length = ListenCounter.knownLength(relay.duration) ?: return
        if (length - relay.currentPosition > AHEAD_MS) return
        checkedAheadOf = key
        relay.sendNext()
    }

    private fun say(message: String) {
        runCatching { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun startPlay(item: MediaItem?) {
        counterItem = item
        counter = item?.let { ListenCounter(it.mediaMetadata.durationMs ?: it.toPlayableSong().durationMs) }
        playId = HistoryRepository.newId()
        playStartedAt = System.currentTimeMillis()
        publishKeep()
    }

    /** The last word on a kept play: how long it really played. */
    private fun finishPlay() {
        val count = counter ?: return
        val item = counterItem ?: return
        if (count.kept) {
            val play = entity(item, count)
            graph.scope.launch { graph.history.keepAll(listOf(play)) }
        }
        counter = null
    }

    private fun startTicking() {
        if (ticking?.isActive == true) return
        lastTick = SystemClock.elapsedRealtime()
        ticking = scope.launch {
            while (isActive) {
                delay(500)
                tick()
                checkAheadIfDue()
            }
        }
    }

    private fun stopTicking() {
        tick()
        ticking?.cancel()
        ticking = null
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = ((now - lastTick) * (if (usingRelay) 1f else exo.playbackParameters.speed)).toLong()
        lastTick = now
        var count = counter ?: return
        // A stream that didn't say its length up front: count against it once it's known.
        if (count.durationMs == null && ListenCounter.knownLength(activeDuration()) != null && !count.kept) {
            val listened = count.listenedMs
            count = ListenCounter(activeDuration()).also { it.add(listened) }
            counter = count
        }
        if (count.add(elapsed)) keep(count)
        publishKeep()
    }

    private fun keep(count: ListenCounter) {
        val item = counterItem ?: return
        val play = entity(item, count)
        graph.scope.launch { graph.history.keep(play) }
    }

    private fun activeDuration(): Long = if (usingRelay) active.duration else exo.duration

    private fun entity(item: MediaItem, count: ListenCounter): PlayEntity {
        val song = item.toPlayableSong()
        // A service's song is kept as coming from that service; a library song through the relay is still yours.
        val (source, storedSource) = if (CatalogMedia.isCatalog(song.mediaId)) CatalogPlays.source(serviceKind(song.mediaId)) else PlaySourceValue.YourMusic to null
        return PlayEntity(
            id = playId,
            title = song.title,
            artistName = song.artistName,
            albumTitle = song.albumTitle,
            artworkUri = song.artworkUri?.toString(),
            playedAt = playStartedAt,
            durationMs = ListenCounter.knownLength(song.durationMs) ?: ListenCounter.knownLength(activeDuration()),
            listenedMs = count.listenedMs,
            kind = PlayKindValue.OnDemand,
            source = source,
            cloudFields = CatalogPlays.cloudFields(storedSource),
            appPackage = packageName,
            appName = "Tracks",
            stationName = if (isRadio() && graph.radio.state.value.picks.containsKey(item.mediaId)) "Tracks Radio" else null,
            mediaId = song.mediaId,
        )
    }

    /** The service a song from the relay came from, by its kind ("spotify", "tidal"). */
    private fun serviceKind(mediaId: String): String? {
        val relay = relayPlayer ?: return null
        val serverId = CatalogMedia.parse(mediaId)?.first ?: return null
        val item = (0 until relay.mediaItemCount).firstNotNullOfOrNull { i -> relay.itemAt(i)?.takeIf { it.mediaId == mediaId } }
        val serviceId = item?.serviceId ?: return null
        return Streaming.of(graph)?.repository?.state?.value?.server(serverId)?.service(serviceId)?.logoKey
    }

    private fun publishKeep() {
        val count = counter
        val progress = ((count?.progress ?: 0f) * 100).roundToInt() / 100f
        val next = PlaybackBridge.Keep(counterItem?.mediaId, progress, count?.kept == true)
        if (PlaybackBridge.keep.value != next) PlaybackBridge.keep.value = next
    }

    // MARK: Songs to get

    /** The player, as getting songs as they're reached needs it. */
    private val gettingQueue = object : GetAsReached.Queue {
        override val current: PlayableSong? get() = player.currentMediaItem?.toPlayableSong()
        override val playWhenReady: Boolean get() = player.playWhenReady

        override fun indexOf(mediaId: String): Int? {
            if (player.currentMediaItem?.mediaId == mediaId) return player.currentMediaItemIndex
            return (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
        }

        override fun replace(index: Int, song: PlayableSong) {
            if (index !in 0 until player.mediaItemCount) return
            player.replaceMediaItem(index, song.toMediaItem())
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }

        override fun remove(index: Int) {
            if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
        }
    }

    // MARK: Tracks Radio

    /**
     * How the song that just gave way went, told by how long it played: a skip is remembered
     * against the song wherever it played, and Tracks Radio hears about its own songs. Going
     * back a song, and a new queue replacing this one, say nothing.
     */
    private fun noteLeaving(reason: Int) {
        val item = counterItem ?: return
        // Moving on from a song still being got isn't a skip: it never played.
        if (SongsToGet.isToGet(item)) return
        val song = item.toPlayableSong()
        val listen = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> Listen.FINISHED
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                if (active.currentMediaItemIndex < playingIndex) return
                val length = ListenCounter.knownLength(counter?.durationMs) ?: ListenCounter.knownLength(song.durationMs)
                radioListen(counter?.listenedMs ?: 0, length)
            }
            else -> return
        }
        if (listen == Listen.SKIPPED) graph.radio.recordSkip(song)
        if (isRadio()) {
            graph.radio.note(song, listen)
            // A skip turns the station: the pick made ready behind the next makes way.
            if (listen == Listen.SKIPPED) radioPicks.steered()
        }
    }

    /**
     * Whether Tracks Radio is what's on, on the phone's own player or through the relay. A
     * service's station is a Station, never this (one saved as Tracks Radio before stations
     * had their own kind comes back as a Station, [Resumption]).
     */
    private fun isRadio() = PlaybackBridge.origin.value?.kind == QueueOrigin.Kind.Radio

    /** Whether one of the station's songs is waiting in Up next, after any you've queued. */
    private fun radioPickWaiting(): Int? {
        val picks = graph.radio.state.value.picks
        val queue = active
        return (queue.mediaItemCount - 1 downTo queue.currentMediaItemIndex + 1)
            .firstOrNull { queue.getMediaItemAt(it).mediaId in picks }
    }

    /**
     * Picks the next song once there's none of the station's waiting after this one. Only one
     * is queued ahead, so every pick knows how the song before it went. It plays from the first
     * place in Play From that the player in use can play: a service's song on the phone's own
     * player hands the station to the relay as it's reached; on the relay, the pick waits to be
     * sent until it's due ([sendAheadIfDue]).
     */
    private fun topUpRadio() {
        if (!isRadio() || fetchingRadio || active.mediaItemCount == 0 || radioPickWaiting() != null) return
        fetchingRadio = true
        val onRelay = usingRelay
        val where: Any = if (onRelay) relayPlayer?.serverId.orEmpty() else HERE
        val accept = playsHere()
        scope.launch {
            try {
                val next = runCatching { radioPicks.next(where, accept) }.getOrNull()
                val queue = active
                if (next == null) {
                    if (isRadio() && !radioRanOut && queue.currentMediaItemIndex >= queue.mediaItemCount - 1) {
                        radioRanOut = true
                        say(RADIO_CANT_PLAY)
                    }
                    return@launch
                }
                radioRanOut = false
                if (!isRadio() || usingRelay != onRelay || radioPickWaiting() != null) {
                    graph.radio.release(next)
                    return@launch
                }
                if (onRelay) {
                    relayPlayer?.hold(listOf(next.toMediaItem()))
                    sendAheadIfDue()
                } else {
                    player.addMediaItem(next.toMediaItem())
                    CatalogMedia.parse(next.mediaId)?.first?.let { Streaming.of(graph)?.relay?.warm(it) }
                }
            } finally {
                fetchingRadio = false
            }
        }
    }

    /**
     * The station's song waiting next was picked before what was just said: it makes way, so
     * the answer is heard next rather than a song later. Kept when it's already by the artist
     * asked for, and never a song you queued. On the relay, one already sent to the server stays:
     * the server's queue can't be trimmed from here.
     */
    private fun repickNext(unlessBy: String) {
        radioPicks.steered()
        val index = radioPickWaiting() ?: return
        if (usingRelay && index < (relayPlayer?.sentCount ?: 0)) return
        val queue = active
        val waiting = queue.getMediaItemAt(index).toPlayableSong()
        if (waiting.artistName == unlessBy) return
        graph.radio.release(waiting)
        queue.removeMediaItem(index)
        if (usingRelay) topUpRadio()
    }

    /** More like the song on now, from the very next song. */
    private fun moreLikeThis() {
        val current = active.currentMediaItem?.toPlayableSong() ?: return
        if (!isRadio() || !graph.radio.knows(current)) return
        graph.radio.moreLikeThis(current)
        repickNext(current.artistName)
    }

    /**
     * Tracks Radio's first songs, opening on [startingWith] when given, set as what plays, each
     * from the first place in Play From that can play it. The session's player is set for them:
     * the relay when the first plays from a service, else the phone's own.
     */
    private suspend fun radioSongs(startingWith: PlayableSong? = null): List<PlayableSong> {
        radioPicks.stop()
        relayDown = false
        radioRanOut = false
        val picked = runCatching { graph.radio.start(startingWith) }
            .onFailure { Logs.w("Tracks Radio didn't start", it) }
            .getOrDefault(emptyList())
        if (picked.isEmpty()) {
            say("Add some music, and Tracks Radio will have something to play.")
            return picked
        }
        val accept: (PlayableSong) -> Boolean = { relayPlays || !CatalogMedia.isCatalog(it.mediaId) }
        val placed = coroutineScope { picked.map { async { placeFor(it, accept) } }.awaitAll() }
        val songs = picked.zip(placed).mapNotNull { (song, place) ->
            if (place == null) graph.radio.release(song) else graph.radio.playsAs(song, place)
            place
        }
        if (songs.isEmpty()) {
            say(RADIO_CANT_PLAY)
            return songs
        }
        PlaybackBridge.origin.value = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
        if (CatalogMedia.isCatalog(songs.first().mediaId)) useRelay() else useNative()
        // A station has no order to shuffle and no end to repeat.
        if (active.shuffleModeEnabled) active.shuffleModeEnabled = false
        if (active.repeatMode != Player.REPEAT_MODE_OFF) active.repeatMode = Player.REPEAT_MODE_OFF
        return songs
    }

    private fun startRadio() {
        scope.launch {
            val songs = radioSongs()
            if (songs.isEmpty()) return@launch
            active.setMediaItems(songs.map { it.toMediaItem() }, 0, 0)
            active.prepare()
            active.play()
        }
    }

    /** Tracks Radio picked up where it is if it's what's on, or started. */
    private suspend fun radioQueue(): MediaSession.MediaItemsWithStartPosition {
        val queue = active
        if (isRadio() && queue.mediaItemCount > 0) {
            val items = List(queue.mediaItemCount) { queue.getMediaItemAt(it) }
            return MediaSession.MediaItemsWithStartPosition(items, queue.currentMediaItemIndex, queue.currentPosition)
        }
        if (!graph.settings.settings.first().showTracksRadio) {
            tree.hourMix()?.let { (songs, start, origin) ->
                useNative()
                PlaybackBridge.origin.value = origin
                return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, start, 0)
            }
        }
        return MediaSession.MediaItemsWithStartPosition(radioSongs().map { it.toMediaItem() }, 0, 0)
    }

    /**
     * The relay stopped working under Tracks Radio: the station carries on here, from the song it
     * was on where you have a copy, else from its next pick, played from the places after the
     * relay's in Play From. Said in one line only when nothing can play.
     */
    private fun radioWithoutRelay() {
        val relay = relayPlayer ?: return
        if (!usingRelay || !isRadio()) return
        relayDown = true
        val current = relay.currentMediaItem?.toPlayableSong()
        val position = relay.currentPosition
        val playing = relay.playWhenReady || Streaming.of(graph)?.relay?.status?.value?.phase == RelayStatus.Phase.Failed
        radioPicks.stop()
        useNative()
        scope.launch {
            val here = current?.let { placeFor(it, playsOnPhone) }
            if (here != null && current != null) graph.radio.playsAs(current, here)
            val first = here ?: runCatching { radioPicks.next(HERE, playsOnPhone) }.getOrNull()
            if (first == null) {
                say(RADIO_CANT_PLAY)
                return@launch
            }
            if (usingRelay || !isRadio()) return@launch
            player.setMediaItems(listOf(first.toMediaItem()), 0, if (here != null) position else 0)
            player.prepare()
            if (playing) player.play()
        }
    }

    // MARK: Sleep timer

    /** The volume the sleep timer fades: whichever player is sounding. */
    private var volume: Float
        get() = if (usingRelay) relayPlayer!!.audio.volume else exo.volume
        set(value) {
            exo.volume = value
            relayPlayer?.audio?.volume = value
        }

    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        volume = 1f
        if (minutes <= 0) {
            PlaybackBridge.sleepAtMs.value = null
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        PlaybackBridge.sleepAtMs.value = at
        sleepJob = scope.launch {
            delay((at - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
            // Fades out over the last ten seconds along a gentle curve, then stops.
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val t = ((SystemClock.elapsedRealtime() - start).toFloat() / FADE_MS).coerceIn(0f, 1f)
                volume = (0.5f * (1f + cos(PI.toFloat() * t)))
                if (t >= 1f) break
                delay(32)
            }
            active.pause()
            volume = 1f
            PlaybackBridge.sleepAtMs.value = null
        }
    }

    // MARK: Media controls

    private fun currentIdentity(): String? =
        active.currentMediaItem?.toPlayableSong()?.identity?.takeIf { it.isNotEmpty() }

    private fun buttons(): List<CommandButton> {
        val isLiked = currentIdentity()?.let { it in liked } == true
        val shuffle = if (::player.isInitialized) active.shuffleModeEnabled else false
        val repeat = if (::player.isInitialized) active.repeatMode else Player.REPEAT_MODE_OFF
        val like = CommandButton.Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (isLiked) "Unlike" else "Like")
            .setSessionCommand(TracksCommands.command(TracksCommands.LIKE))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        // A station picks as it goes: there's no order to shuffle, or end to repeat.
        if (PlaybackBridge.origin.value?.isStation == true) return listOf(like)
        return listOf(
            like,
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Turn shuffle off" else "Shuffle")
                .setSessionCommand(TracksCommands.command(TracksCommands.SHUFFLE))
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(
                when (repeat) {
                    Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                    Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                    else -> CommandButton.ICON_REPEAT_OFF
                },
            )
                .setDisplayName(
                    when (repeat) {
                        Player.REPEAT_MODE_ONE -> "Repeat this song"
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Repeat"
                    },
                )
                .setSessionCommand(TracksCommands.command(TracksCommands.REPEAT))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun refreshButtons() {
        session?.setMediaButtonPreferences(buttons())
    }

    private fun toggleLike() {
        val identity = currentIdentity() ?: return
        val mediaId = active.currentMediaItem?.mediaId.orEmpty()
        val on = identity !in liked
        liked = if (on) liked + identity else liked - identity
        refreshButtons()
        // Liking the song on tells Tracks Radio to play more like it, from the next song.
        val current = active.currentMediaItem?.toPlayableSong()
        if (on && current != null && isRadio() && graph.radio.knows(current)) {
            graph.radio.loved(current)
            repickNext(current.artistName)
        }
        graph.scope.launch {
            graph.settings.update { it.copy(likedSongs = if (on) it.likedSongs + identity else it.likedSongs - identity) }
            (graph.library as? CombinedLibrary)?.servers?.star(mediaId, on)
        }
    }

    private fun remember() {
        if (!::player.isInitialized) return
        val source = active
        if (source.mediaItemCount == 0) return
        val songs = List(source.mediaItemCount) { source.getMediaItemAt(it).toPlayableSong() }
        memory.save(songs, source.currentMediaItemIndex, source.currentPosition, PlaybackBridge.origin.value)
    }

    // MARK: The relay

    private fun startRelay(streaming: Streaming) {
        val relay = RelayPlayer(
            context = this,
            engine = streaming.relay,
            scope = scope,
            http = { relayHttp },
            playNatively = ::playNatively,
        )
        relay.services = {
            relay.serverId?.let { id -> streaming.repository.state.value.server(id)?.services?.associate { it.id to it.name } }.orEmpty()
        }
        relay.waits = { serverId, item ->
            !RelayPlayer.playsOn(serverId, item.mediaId) ||
                (!CatalogMedia.isCatalog(item.mediaId) && item.mediaId !in graph.radio.state.value.picks && graph.playResolver.looksUp(wanted(item.toPlayableSong())))
        }
        relay.resolve = { serverId, item -> placeFor(item) { RelayPlayer.playsOn(serverId, it.mediaId) } }
        relay.passedOver = { item ->
            val song = item.toPlayableSong()
            // A pick that can't play here makes way for another; any other song is said, as songs skipped ahead are.
            if (song.mediaId in graph.radio.state.value.picks) {
                graph.radio.release(song)
                topUpRadio()
            } else graph.sources.reportSkipped(SkippedSongs(1, PlayResolver.cantPlay(song.title)))
        }
        relay.addListener(relayListener)
        relayPlayer = relay
        // Under Tracks Radio, a relay that stops is met by playing on here; the station says if nothing can.
        scope.launch { streaming.relay.messages.collect { if (!(usingRelay && isRadio())) say(it) } }
        scope.launch {
            streaming.relay.status.collect { status ->
                if (status.phase == RelayStatus.Phase.Live) relayDown = false
                if (RadioOnRelay.stopped(status) && usingRelay && isRadio()) radioWithoutRelay()
            }
        }
        scope.launch {
            streaming.relay.status.map { it.serverId }.distinctUntilChanged().collect { id ->
                relayHttp = id?.let { streaming.servers.tracksideClient(it)?.events } ?: Http.base
            }
        }
    }

    /**
     * What the relay plays is kept and counted as the phone's own player's is, and Tracks Radio
     * hears how each song went and picks on, as it does on the phone's own player.
     */
    private val relayListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!usingRelay) return
            noteLeaving(reason)
            finishPlay()
            startPlay(mediaItem)
            playingIndex = active.currentMediaItemIndex
            topUpRadio()
            relayPlayer?.prefetch()
            refreshButtons()
            remember()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (!usingRelay || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            playingIndex = active.currentMediaItemIndex
            topUpRadio()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (!usingRelay) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItemIndex == new.mediaItemIndex &&
                old.positionMs >= RESTARTS_AFTER_MS && new.positionMs < 1_000 && isRadio()
            ) {
                active.currentMediaItem?.toPlayableSong()?.let(graph.radio::replayed)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!usingRelay) return
            if (isPlaying) startTicking() else {
                stopTicking()
                remember()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()
        override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()
    }

    /** The relay takes over the session: the phone's own player lets go of its queue and the audio. */
    private fun useRelay() {
        val relay = relayPlayer ?: return
        if (usingRelay) return
        finishPlay()
        player.pause()
        player.clearMediaItems()
        session?.player = relay
        refreshButtons()
    }

    /** Back to the phone's own player; the relay's session ends, and the server keeps its queue. */
    private fun useNative() {
        if (!usingRelay) return
        finishPlay()
        Streaming.of(graph)?.relay?.stop()
        session?.player = player
        refreshButtons()
    }

    /** The relay was handed a queue of library songs only: the phone plays them itself. */
    private fun playNatively(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        useNative()
        val restored = items.mapNotNull { it.restored() }
        if (restored.isEmpty()) return
        player.setMediaItems(restored, startIndex.coerceIn(0, restored.lastIndex), if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
        player.prepare()
    }

    /** A queue that reached a service's song on the phone's own player: it plays on through the relay from there. */
    private fun moveQueueToRelay() {
        val relay = relayPlayer ?: return
        if (usingRelay || player.currentMediaItem?.mediaId?.let(CatalogMedia::isCatalog) != true) return
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val index = player.currentMediaItemIndex
        val playing = player.playWhenReady
        useRelay()
        relay.setMediaItems(items, index, 0)
        if (playing) relay.play()
    }

    // MARK: The session

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (error: Throwable) {
                future.setException(error)
            }
        }
        return future
    }

    private suspend fun resolve(item: MediaItem, controller: MediaSession.ControllerInfo): MediaItem? = ControllerAccess.resolve(
        item,
        own = ControllerAccess.isOwn(describe(controller), packageName, Process.myUid()),
        restore = { it.restored() },
        search = { query -> tree.search(query).firstOrNull()?.toMediaItem() },
        lookup = { id -> tree.song(id)?.toMediaItem() },
    )

    private fun describe(controller: MediaSession.ControllerInfo) = ControllerAccess.Controller(
        packageName = controller.packageName,
        uid = controller.uid,
        systemTrusted = controller.isTrusted,
        systemRole = session?.isMediaNotificationController(controller) == true,
        systemPackage = isSystemPackage(controller.packageName, controller.uid),
    )

    private fun isSystemPackage(name: String, uid: Int): Boolean = runCatching {
        val owns = packageManager.getPackagesForUid(uid)?.contains(name) == true
        val flags = packageManager.getApplicationInfo(name, 0).flags
        owns && flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    private val callback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!ControllerAccess.trusts(describe(controller), packageName, Process.myUid())) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SessionCommands.EMPTY)
                    .setAvailablePlayerCommands(ControllerAccess.PLAYBACK_ONLY)
                    .build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            TracksCommands.all.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setMediaButtonPreferences(buttons())
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                TracksCommands.LIKE -> toggleLike()
                TracksCommands.SHUFFLE -> active.shuffleModeEnabled = !active.shuffleModeEnabled
                TracksCommands.REPEAT -> active.repeatMode = when (active.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                TracksCommands.SLEEP -> setSleep(args.getInt(TracksCommands.MINUTES, 0))
                TracksCommands.RADIO -> startRadio()
                TracksCommands.MORE_LIKE_THIS -> moreLikeThis()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.root, params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            future { LibraryResult.ofItemList(tree.children(parentId).page(page, pageSize), params) }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            future { tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            future {
                val results = tree.search(query)
                searches[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            future {
                val results = searches[query] ?: tree.search(query)
                LibraryResult.ofItemList(results.map { it.toMediaItem() }.page(page, pageSize), params)
            }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            future { preferred(withoutServiceSongs(mediaItems.mapNotNull { resolve(it, controller) })).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val single = mediaItems.singleOrNull()
            if (single != null && single.localConfiguration == null && single.requestMetadata.mediaUri == null) {
                // Everything a car or the Assistant names is in your library: the phone plays it itself.
                useNative()
                val query = single.requestMetadata.searchQuery
                when {
                    // "Play music on Tracks": nothing named, so Tracks Radio.
                    single.mediaId == BrowseTree.RADIO || (query != null && query.isBlank()) -> return@future radioQueue()
                    query != null -> {
                        val songs = tree.search(query)
                        if (songs.isNotEmpty()) PlaybackBridge.origin.value = QueueOrigin(query, QueueOrigin.Kind.Search)
                        return@future MediaSession.MediaItemsWithStartPosition(preferred(songs.map { it.toMediaItem() }), 0, 0)
                    }
                    else -> tree.expand(single.mediaId)?.let { (songs, start, origin) ->
                        PlaybackBridge.origin.value = origin
                        return@future MediaSession.MediaItemsWithStartPosition(preferred(songs.map { it.toMediaItem() }), start, 0)
                    }
                }
            }
            val found = withoutServiceSongs(mediaItems.mapNotNull { resolve(it, controller) })
            relayDown = false
            radioPicks.stop()
            val start = startIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0))
            // The first song from the first place in Play From, looked up now where a service comes first; the rest as each comes near.
            val first = found.getOrNull(start)?.let { item -> placeFor(item) { relayPlays || !CatalogMedia.isCatalog(it.mediaId) } }
            val queue = if (first != null && first.mediaId != found[start].mediaId) found.toMutableList().also { it[start] = first } else found
            val relayServer = CatalogMedia.relayServer(queue.map { it.mediaId })
            // A queue starting on a service's song, or on its server's own with a service's song in it, plays through the relay, which holds what's to be looked up.
            if (relayPlayer != null && relayServer != null && queue.getOrNull(start)?.let { RelayPlayer.playsOn(relayServer, it.mediaId) } == true) {
                useRelay()
                return@future MediaSession.MediaItemsWithStartPosition(queue, start, startPositionMs)
            }
            useNative()
            val resolved = preferred(queue)
            MediaSession.MediaItemsWithStartPosition(resolved, start, startPositionMs)
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            future {
                val saved = memory.restore()
                val paused = saved?.songs?.getOrNull(saved.index)
                val streams = saved != null && Resumption.streams(saved.songs)
                if (saved != null && paused != null && Resumption.restartsTracksRadio(saved.origin, saved.songs, saved.tracksRadio)) {
                    // Tracks Radio starts again from the song it was on, and picks on from there.
                    val songs = radioSongs(startingWith = paused)
                    if (songs.isNotEmpty()) return@future MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, saved.positionMs)
                }
                if (saved != null) {
                    PlaybackBridge.origin.value = Resumption.origin(saved.origin, saved.songs, saved.tracksRadio)
                    // A queue with a service's song comes back through the relay, which starts a session for it.
                    if (relayPlayer != null && streams) useRelay() else useNative()
                    MediaSession.MediaItemsWithStartPosition(saved.songs.map { it.toMediaItem() }, saved.index, saved.positionMs)
                } else {
                    val songs = if (isForPlayback) radioSongs() else emptyList()
                    if (songs.isEmpty()) throw UnsupportedOperationException("Nothing to resume")
                    MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, 0)
                }
            }
    }

    private companion object {
        const val FADE_MS = 10_000L
        /** How long before a song ends the songs after it are checked, as on the iPhone. */
        const val AHEAD_MS = 30_000L
        const val LOAD_RETRIES = 6
        /** How far into a song Previous starts it over, as Media3 has it. */
        const val RESTARTS_AFTER_MS = 3_000L
        /** Songs coming up looked up in Play From ahead of being reached. */
        const val AHEAD_SONGS = 2
        /** Where Tracks Radio's picks play when it isn't the relay: this device's own player. */
        const val HERE = "here"
        const val RADIO_CANT_PLAY = "Tracks Radio can't play anything right now."

        fun List<MediaItem>.page(page: Int, size: Int): ImmutableList<MediaItem> {
            if (size <= 0 || size == Int.MAX_VALUE) return ImmutableList.copyOf(this)
            val from = (page.toLong() * size).coerceAtMost(this.size.toLong()).toInt()
            return ImmutableList.copyOf(subList(from, (from + size).coerceAtMost(this.size)))
        }
    }
}
