package com.luke.tracks.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import com.luke.tracks.library.ServerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    const val MINUTES = "minutes"

    val all = listOf(LIKE, SHUFFLE, REPEAT, SLEEP, RADIO).map { SessionCommand(it, Bundle.EMPTY) }
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
    private val radioIds = HashSet<String>()
    private var fetchingRadio = false

    private var sleepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        memory = QueueMemory(this)
        tree = BrowseTree(graph)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(ServerLibrary.defaultHttp))))
            .build()
        player = FreshShufflePlayer(exo)
        player.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setData(Uri.parse("tracks://nowplaying")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(open)
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
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    // MARK: Listening and keeping

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            noteRadio(reason)
            finishPlay()
            startPlay(mediaItem)
            topUpRadio()
            refreshButtons()
            remember()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicking() else {
                stopTicking()
                remember()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()
        override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()
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
        if (count.durationMs == null && exo.duration > 0 && exo.duration != C.TIME_UNSET && !count.kept) {
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
            durationMs = song.durationMs ?: exo.duration.takeIf { it > 0 && it != C.TIME_UNSET },
            listenedMs = count.listenedMs,
            kind = PlayKindValue.OnDemand,
            source = PlaySourceValue.YourMusic,
            appPackage = packageName,
            appName = "Tracks",
            stationName = if (item.mediaId in radioIds) "Tracks Radio" else null,
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

    private fun noteRadio(reason: Int) {
        val item = counterItem ?: return
        if (item.mediaId !in radioIds) return
        val listened = counter?.listenedMs ?: 0
        val listen = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> RadioListen.Finished
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> if (listened < 30_000) RadioListen.Skipped else RadioListen.MovedOn
            else -> RadioListen.MovedOn
        }
        runCatching { graph.radio.note(item.toPlayableSong(), listen) }
    }

    private fun isRadio() = PlaybackBridge.origin.value?.kind == QueueOrigin.Kind.Radio

    private fun topUpRadio() {
        if (!isRadio() || fetchingRadio) return
        val left = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (left >= 3) return
        fetchingRadio = true
        scope.launch {
            try {
                val more = runCatching { graph.radio.more(5) }.getOrDefault(emptyList())
                if (isRadio() && more.isNotEmpty()) {
                    radioIds += more.map { it.mediaId }
                    player.addMediaItems(more.map { it.toMediaItem() })
                }
            } finally {
                fetchingRadio = false
            }
        }
    }

    private suspend fun radioSongs(): List<PlayableSong> {
        val songs = runCatching { graph.radio.start() }.getOrDefault(emptyList())
        if (songs.isEmpty()) return songs
        PlaybackBridge.origin.value = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
        radioIds.clear()
        radioIds += songs.map { it.mediaId }
        if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
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
        return listOf(
            CommandButton.Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (isLiked) "Unlike" else "Like")
                .setSessionCommand(TracksCommands.command(TracksCommands.LIKE))
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
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
            future { mediaItems.mapNotNull { resolve(it) }.toMutableList() }

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
                    single.mediaId == BrowseTree.RADIO || (query != null && query.isBlank()) -> {
                        val songs = radioSongs()
                        return@future MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, 0)
                    }
                    query != null -> {
                        val songs = tree.search(query)
                        if (songs.isNotEmpty()) PlaybackBridge.origin.value = QueueOrigin(query, QueueOrigin.Kind.Search)
                        return@future MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, 0)
                    }
                    else -> tree.expand(single.mediaId)?.let { (songs, start, origin) ->
                        PlaybackBridge.origin.value = origin
                        return@future MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, start, 0)
                    }
                }
            }
            val resolved = mediaItems.mapNotNull { resolve(it) }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            future {
                val saved = memory.restore()
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

        fun List<MediaItem>.page(page: Int, size: Int): ImmutableList<MediaItem> {
            if (size <= 0 || size == Int.MAX_VALUE) return ImmutableList.copyOf(this)
            val from = (page.toLong() * size).coerceAtMost(this.size.toLong()).toInt()
            return ImmutableList.copyOf(subList(from, (from + size).coerceAtMost(this.size)))
        }
    }
}
