package com.luke.tracks.capture

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.HistoryRepository
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.graph
import com.luke.tracks.lastfm.lastFm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the songs other apps play (Spotify, YouTube Music, radio apps), the way Tracks keeps
 * Apple Music on iPhone. Android lets a notification listener read every app's media
 * session; Tracks reads only those, and never the notifications themselves.
 *
 * Everything here runs on the main thread except the writes, which go to IO.
 */
class CaptureListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<MediaSession.Token, Followed>()
    private var manager: MediaSessionManager? = null
    private val labels = mutableMapOf<String, String>()

    /** The newest cover bitmap each app sent, with the song it belongs to. */
    private val covers = mutableMapOf<String, Pair<String, Bitmap>>()

    private val settings by lazy {
        graph.settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    }

    private val tracker by lazy {
        PlayTracker(
            ownPackage = packageName,
            shouldKeep = { duration, played ->
                if (duration == null) played >= 30.0 else uniffi.tracks_engine.lastfmShouldScrobble(duration, played)
            },
            isExcluded = { pkg -> !settings.value.captureOtherApps || pkg in settings.value.excludedApps },
            onKeep = ::keep,
            onStart = ::started,
        )
    }

    private val tick = Runnable { tracker.tick(); scheduleTick() }

    private inner class Followed(val controller: MediaController) : MediaController.Callback() {
        val pkg: String = controller.packageName

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            read(metadata)
            scheduleTick()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            tracker.onPlayback(pkg, state?.state == PlaybackState.STATE_PLAYING, state?.estimatedPosition())
            scheduleTick()
        }

        override fun onSessionDestroyed() {
            forget(controller.sessionToken)
        }

        fun read(metadata: MediaMetadata?) {
            val track = metadata?.toTrack()
            if (track != null && pkg != packageName) CaptureAccess.noteSeen(this@CaptureListenerService, pkg)
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (track != null && bitmap != null && track.title != null && track.artist != null) {
                covers[pkg] = PlayTracker.songKey(track.title, track.artist) to bitmap
            }
            tracker.onMetadata(pkg, track)
        }
    }

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> follow(controllers.orEmpty()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        settings.value // Starts following the settings now.
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        this.manager = manager
        val component = CaptureAccess.component(this)
        runCatching {
            manager.addOnActiveSessionsChangedListener(changed, component, handler)
            follow(manager.getActiveSessions(component))
        }.onFailure { Log.w(TAG, "Couldn't read media sessions", it) }
    }

    override fun onListenerDisconnected() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(changed) }
        sessions.keys.toList().forEach(::forget)
        handler.removeCallbacks(tick)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        scope.cancel()
        super.onDestroy()
    }

    private fun follow(controllers: List<MediaController>) {
        val tokens = controllers.map { it.sessionToken }.toSet()
        sessions.keys.filter { it !in tokens }.forEach(::forget)
        controllers.filter { it.sessionToken !in sessions }.forEach { controller ->
            val followed = Followed(controller)
            sessions[controller.sessionToken] = followed
            controller.registerCallback(followed, handler)
            followed.read(controller.metadata)
            val state = controller.playbackState
            tracker.onPlayback(followed.pkg, state?.state == PlaybackState.STATE_PLAYING, state?.estimatedPosition())
        }
        scheduleTick()
    }

    private fun forget(token: MediaSession.Token) {
        val followed = sessions.remove(token) ?: return
        runCatching { followed.controller.unregisterCallback(followed) }
        if (sessions.values.none { it.pkg == followed.pkg }) {
            tracker.onSessionGone(followed.pkg)
            covers.remove(followed.pkg)
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val due = tracker.msUntilDue() ?: return
        handler.postDelayed(tick, due + 250)
    }

    private fun started(song: TrackedSong) {
        if (graph.sampleData) return
        val s = settings.value
        if (!s.scrobbleEnabled || s.lastFmUser == null) return
        graph.scope.launch { graph.lastFm.nowPlaying(song.title, song.artist, song.album) }
    }

    private fun keep(kept: KeptSong) {
        // Sample data is pretend; a real play kept into it would be lost when it's turned off.
        if (graph.sampleData) return
        val song = kept.song
        val cover = covers[song.packageName]?.takeIf { it.first == song.key }?.second
        val appName = labels.getOrPut(song.packageName) {
            CaptureAccess.label(packageManager, song.packageName) ?: song.packageName
        }
        val history = graph.history
        scope.launch {
            val artwork = song.artUri ?: cover?.let { saveCover(it, song) }
            history.keep(
                PlayEntity(
                    id = HistoryRepository.newId(),
                    title = song.title,
                    artistName = song.artist,
                    albumTitle = song.album,
                    artworkUri = artwork,
                    playedAt = kept.startedAtMs,
                    durationMs = song.durationMs,
                    listenedMs = kept.listenedMs,
                    kind = if (song.isRadio) PlayKindValue.Radio else PlayKindValue.OnDemand,
                    source = PlaySourceValue.Streaming,
                    appPackage = song.packageName,
                    appName = appName,
                    stationName = song.stationName,
                    mediaId = "",
                ),
            )
        }
    }

    /** Saves the cover once per song and album, as a small JPEG in the app's own files. */
    private suspend fun saveCover(bitmap: Bitmap, song: TrackedSong): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(filesDir, "covers").apply { mkdirs() }
            val file = File(dir, hash(song.key + "\u0000" + (song.album ?: "")) + ".jpg")
            if (!file.exists()) {
                val scaled = if (bitmap.width > 600) Bitmap.createScaledBitmap(bitmap, 600, 600 * bitmap.height / bitmap.width, true) else bitmap
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }
            Uri.fromFile(file).toString()
        }.getOrNull()
    }

    companion object {
        private const val TAG = "TracksCapture"

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}

private fun PlaybackState.estimatedPosition(): Long? {
    if (position < 0) return null
    if (state != PlaybackState.STATE_PLAYING) return position
    val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
    return position + (elapsed * playbackSpeed).toLong()
}

private fun MediaMetadata.toTrack(): TrackMetadata = TrackMetadata(
    title = getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
    artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
    album = getString(MediaMetadata.METADATA_KEY_ALBUM),
    albumArtist = getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
    durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
    mediaId = getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
    displaySubtitle = getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
    artUri = getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: getString(MediaMetadata.METADATA_KEY_ART_URI),
    mediaTypeHint = getString(MediaMetadata.METADATA_KEY_GENRE),
)
