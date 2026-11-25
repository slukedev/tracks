package com.luke.tracks.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
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
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.net.Http
import com.luke.tracks.net.Logs
import com.luke.tracks.sources.SkippedSongs
import com.luke.tracks.sources.SongCopies
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.sources.sources
import com.luke.tracks.applemusic.AppleMusic
import com.luke.tracks.applemusic.AppleMusicUris
import com.luke.tracks.applemusic.appleMusicProblem
import com.luke.tracks.spotify.Spotify
import com.luke.tracks.spotify.SpotifyUris
import com.luke.tracks.spotify.spotifyProblem
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
    /** Where in the queue the song playing sat, to tell going back from moving on. */
    private var playingIndex = 0

    private var sleepJob: Job? = null
    private var failuresInARow = 0

    // Copies across sources.
    private val copies = SongCopies {
        (graph.library as? CombinedLibrary)?.let { listOf(it.device.tracks.value, it.servers.tracks.value) }.orEmpty() +
            listOfNotNull(AppleMusic.service?.library?.value)
    }
    /** The song whose copies have been tried after failing, by identity, and the ones tried. */
    private var triedFor: String? = null
    private val tried = mutableSetOf<String>()
    /** The song on when songs ahead were last checked, so each song checks once. */
    private var checkedAheadOf: String? = null
    /** Nothing ahead could play: when this song ends the music stops, and the message says why. */
    private var nothingAfter: String? = null
    private var nothingAfterCount = 0

    /** Files on the phone, and server songs signed only as each request goes out ([com.luke.tracks.net.ServerLinks]). */
    private val dataSources by lazy { DefaultDataSource.Factory(this, OkHttpDataSource.Factory(Http.media)) }

    /** The above, plus Spotify's decoded audio in builds that have it. */
    private val songSources by lazy { SourceDataSource.Factory(dataSources, appleMusic = { AppleMusic.service?.playback }) { Spotify.integration?.audio } }

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
                DefaultMediaSourceFactory(songSources)
                    // A dropped connection resumes from where the song broke off, a few times over.
                    .setLoadErrorHandlingPolicy(SourceLoadErrorPolicy(LOAD_RETRIES)),
            )
            .build()
        player = FreshShufflePlayer(exo)
        player.addListener(listener)
        // Apple Music's songs play through MusicKit, following this player.
        AppleMusic.service?.playback?.attach(exo)

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
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_launcher_monochrome) })

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
        if (!player.playWhenReady || player.mediaItemCount == 0) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        remember()
        finishPlay()
        PlaybackBridge.sleepAtMs.value = null
        session?.release()
        session = null
        AppleMusic.service?.playback?.detach()
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    // MARK: Listening and keeping

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
            refreshButtons()
            remember()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // A pick taken out of Up next: another takes its place.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                playingIndex = player.currentMediaItemIndex
                topUpRadio()
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Previous well into a station's song starts it over: wanting to hear it again.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && old.mediaItemIndex == new.mediaItemIndex &&
                old.positionMs >= RESTARTS_AFTER_MS && new.positionMs < 1_000 && isRadio()
            ) {
                player.currentMediaItem?.toPlayableSong()?.let(graph.radio::replayed)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
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
        val spotify = error.spotifyProblem()
        val appleMusic = error.appleMusicProblem()
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        when {
            // Its source can't play now (a server offline): the same quiet message as songs skipped ahead.
            moveOn && spotify == null && !graph.sources.canPlay(mediaId) ->
                graph.sources.reportSkipped(SkippedSongs(1, graph.sources.whyUnavailable(mediaId)))
            appleMusic != null -> {
                if (!appleMusic.accountWide || failuresInARow == 1) say(appleMusic.message)
                AppleMusic.service?.playbackFailed(appleMusic)
            }
            spotify == null -> say(if (moveOn) "Couldn't play $title. Playing the next song." else "Couldn't play $title.")
            // Said once for a run of songs that fail for the same account-wide reason.
            spotify.accountWide -> {
                if (failuresInARow == 1) say(spotify.message)
                Spotify.integration?.playbackFailed(spotify)
            }
            else -> say(spotify.message)
        }
        if (!moveOn) return
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    // MARK: Copies across sources

    /**
     * The copy of a song to queue. With every source on, the first in the Play From order that
     * can play now; filtered to one source, the one asked for, unless it can't play.
     */
    private fun preferred(item: MediaItem): MediaItem {
        val song = item.toPlayableSong()
        val all = copies.of(song)
        if (all.size < 2) return item
        val registry = graph.sources
        if (registry.selection.value != SourceSelection.All && registry.canPlay(song.mediaId)) return item
        val best = registry.ranked(all) { it.mediaId }.firstOrNull { registry.canPlay(it.mediaId) } ?: return item
        return if (best.mediaId == song.mediaId) item else best.toMediaItem()
    }

    private suspend fun preferred(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.Default) { items.map(::preferred) }

    /** Another copy of a song that can play now, in the Play From order, other than the ones in [besides]. */
    private fun otherCopy(song: PlayableSong, besides: Set<String>): PlayableSong? {
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
        val current = player.currentMediaItem ?: return
        val key = "${player.currentMediaItemIndex}:${current.mediaId}"
        if (checkedAheadOf == key) return
        val length = ListenCounter.knownLength(exo.duration) ?: return
        if (length - exo.currentPosition > AHEAD_MS) return
        checkedAheadOf = key
        checkAhead()
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
        val elapsed = ((now - lastTick) * exo.playbackParameters.speed).toLong()
        lastTick = now
        var count = counter ?: return
        // A stream that didn't say its length up front: count against it once it's known.
        if (count.durationMs == null && ListenCounter.knownLength(exo.duration) != null && !count.kept) {
            val listened = count.listenedMs
            count = ListenCounter(exo.duration).also { it.add(listened) }
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

    private fun entity(item: MediaItem, count: ListenCounter): PlayEntity {
        val song = item.toPlayableSong()
        return PlayEntity(
            id = playId,
            title = song.title,
            artistName = song.artistName,
            albumTitle = song.albumTitle,
            artworkUri = song.artworkUri?.toString(),
            playedAt = playStartedAt,
            durationMs = ListenCounter.knownLength(song.durationMs) ?: ListenCounter.knownLength(exo.duration),
            listenedMs = count.listenedMs,
            kind = PlayKindValue.OnDemand,
            source = when {
                SpotifyUris.isSpotify(song.mediaId) -> PlaySourceValue.Spotify
                // Kept as the engine's Apple Music source, "appleMusic" in History and Trackside Cloud.
                AppleMusicUris.isAppleMusic(song.mediaId) -> PlaySourceValue.Streaming
                else -> PlaySourceValue.YourMusic
            },
            appPackage = packageName,
            appName = "Tracks",
            stationName = if (isRadio() && graph.radio.state.value.picks.containsKey(item.mediaId)) "Tracks Radio" else null,
            mediaId = song.mediaId,
        )
    }

    private fun publishKeep() {
        val count = counter
        val progress = ((count?.progress ?: 0f) * 100).roundToInt() / 100f
        val next = PlaybackBridge.Keep(counterItem?.mediaId, progress, count?.kept == true)
        if (PlaybackBridge.keep.value != next) PlaybackBridge.keep.value = next
    }

    // MARK: Tracks Radio

    /**
     * How the song that just gave way went, told by how long it played: a skip is remembered
     * against the song wherever it played, and Tracks Radio hears about its own songs. Going
     * back a song, and a new queue replacing this one, say nothing.
     */
    private fun noteLeaving(reason: Int) {
        val item = counterItem ?: return
        val song = item.toPlayableSong()
        val listen = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> Listen.FINISHED
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                if (player.currentMediaItemIndex < playingIndex) return
                val length = ListenCounter.knownLength(counter?.durationMs) ?: ListenCounter.knownLength(song.durationMs)
                radioListen(counter?.listenedMs ?: 0, length)
            }
            else -> return
        }
        if (listen == Listen.SKIPPED) graph.radio.recordSkip(song)
        if (isRadio()) graph.radio.note(song, listen)
    }

    private fun isRadio() = PlaybackBridge.origin.value?.kind == QueueOrigin.Kind.Radio

    /** Whether one of the station's songs is waiting in Up next, after any you've queued. */
    private fun radioPickWaiting(): Int? {
        val picks = graph.radio.state.value.picks
        return (player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1)
            .firstOrNull { player.getMediaItemAt(it).mediaId in picks }
    }

    /**
     * Picks the next song once there's none of the station's waiting after this one. Only one
     * is queued ahead, so every pick knows how the song before it went.
     */
    private fun topUpRadio() {
        if (!isRadio() || fetchingRadio || player.mediaItemCount == 0 || radioPickWaiting() != null) return
        fetchingRadio = true
        scope.launch {
            try {
                val next = runCatching { graph.radio.next() }.getOrNull()
                if (isRadio() && next != null && radioPickWaiting() == null) player.addMediaItem(next.toMediaItem())
            } finally {
                fetchingRadio = false
            }
        }
    }

    /**
     * The station's song waiting next was picked before what was just said: it makes way, so
     * the answer is heard next rather than a song later. Kept when it's already by the artist
     * asked for, and never a song you queued.
     */
    private fun repickNext(unlessBy: String) {
        val index = radioPickWaiting() ?: return
        val waiting = player.getMediaItemAt(index).toPlayableSong()
        if (waiting.artistName == unlessBy) return
        graph.radio.release(waiting)
        player.removeMediaItem(index)
    }

    /** More like the song on now, from the very next song. */
    private fun moreLikeThis() {
        val current = player.currentMediaItem?.toPlayableSong() ?: return
        if (!isRadio() || !graph.radio.knows(current)) return
        graph.radio.moreLikeThis(current)
        repickNext(current.artistName)
    }

    /** Tracks Radio's first songs, opening on [startingWith] when given, set as what plays. */
    private suspend fun radioSongs(startingWith: PlayableSong? = null): List<PlayableSong> {
        val songs = runCatching { graph.radio.start(startingWith) }
            .onFailure { Logs.w("Tracks Radio didn't start", it) }
            .getOrDefault(emptyList())
        if (songs.isEmpty()) {
            say("Add some music, and Tracks Radio will have something to play.")
            return songs
        }
        PlaybackBridge.origin.value = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
        // A station has no order to shuffle and no end to repeat.
        if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
        if (player.repeatMode != Player.REPEAT_MODE_OFF) player.repeatMode = Player.REPEAT_MODE_OFF
        return songs
    }

    private fun startRadio() {
        scope.launch {
            val songs = radioSongs()
            if (songs.isEmpty()) return@launch
            player.setMediaItems(songs.map { it.toMediaItem() }, 0, 0)
            player.prepare()
            player.play()
        }
    }

    /** Tracks Radio picked up where it is if it's what's on, or started. */
    private suspend fun radioQueue(): MediaSession.MediaItemsWithStartPosition {
        if (isRadio() && player.mediaItemCount > 0) {
            val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
            return MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
        }
        if (!graph.settings.settings.first().showTracksRadio) {
            tree.hourMix()?.let { (songs, start, origin) ->
                PlaybackBridge.origin.value = origin
                return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, start, 0)
            }
        }
        return MediaSession.MediaItemsWithStartPosition(radioSongs().map { it.toMediaItem() }, 0, 0)
    }

    // MARK: Sleep timer

    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        exo.volume = 1f
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
                exo.volume = (0.5f * (1f + cos(PI.toFloat() * t)))
                if (t >= 1f) break
                delay(32)
            }
            player.pause()
            exo.volume = 1f
            PlaybackBridge.sleepAtMs.value = null
        }
    }

    // MARK: Media controls

    private fun currentIdentity(): String? =
        player.currentMediaItem?.toPlayableSong()?.identity?.takeIf { it.isNotEmpty() }

    private fun buttons(): List<CommandButton> {
        val isLiked = currentIdentity()?.let { it in liked } == true
        val shuffle = if (::player.isInitialized) player.shuffleModeEnabled else false
        val repeat = if (::player.isInitialized) player.repeatMode else Player.REPEAT_MODE_OFF
        val like = CommandButton.Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (isLiked) "Unlike" else "Like")
            .setSessionCommand(TracksCommands.command(TracksCommands.LIKE))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        // A station picks as it goes: there's no order to shuffle, or end to repeat.
        if (isRadio()) return listOf(like)
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
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        val on = identity !in liked
        liked = if (on) liked + identity else liked - identity
        refreshButtons()
        // Liking the song on tells Tracks Radio to play more like it, from the next song.
        val current = player.currentMediaItem?.toPlayableSong()
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
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val songs = List(player.mediaItemCount) { player.getMediaItemAt(it).toPlayableSong() }
        memory.save(songs, player.currentMediaItemIndex, player.currentPosition, PlaybackBridge.origin.value)
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

    private suspend fun resolve(item: MediaItem): MediaItem? {
        item.restored()?.let { return it }
        item.requestMetadata.searchQuery?.let { query -> return tree.search(query).firstOrNull()?.toMediaItem() }
        return tree.song(item.mediaId)?.toMediaItem()
    }

    private val callback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
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
                TracksCommands.SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                TracksCommands.REPEAT -> player.repeatMode = when (player.repeatMode) {
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
            future { preferred(mediaItems.mapNotNull { resolve(it) }).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val single = mediaItems.singleOrNull()
            if (single != null && single.localConfiguration == null && single.requestMetadata.mediaUri == null) {
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
            val resolved = preferred(mediaItems.mapNotNull { resolve(it) })
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            future {
                val saved = memory.restore()
                val paused = saved?.songs?.getOrNull(saved.index)
                if (saved?.origin?.kind == QueueOrigin.Kind.Radio && paused != null) {
                    // Tracks Radio starts again from the song it was on, and picks on from there.
                    val songs = radioSongs(startingWith = paused)
                    if (songs.isNotEmpty()) return@future MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, saved.positionMs)
                }
                if (saved != null) {
                    PlaybackBridge.origin.value = saved.origin
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

        fun List<MediaItem>.page(page: Int, size: Int): ImmutableList<MediaItem> {
            if (size <= 0 || size == Int.MAX_VALUE) return ImmutableList.copyOf(this)
            val from = (page.toLong() * size).coerceAtMost(this.size.toLong()).toInt()
            return ImmutableList.copyOf(subList(from, (from + size).coerceAtMost(this.size)))
        }
    }
}
