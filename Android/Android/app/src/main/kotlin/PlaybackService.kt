package tracks.module

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/// Tracks's player: ExoPlayer in a media session service, so music keeps playing with the app
/// closed, and the notification, lock screen, headphones and car can control it.
///
/// It also keeps count. `PlayRecorder` writes each song that plays long enough to the queue the
/// app reads (`ListenQueue.swift`), which is where the statistics come from.
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var recorder: PlayRecorder? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        recorder = PlayRecorder(this, player)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /// Swiping Tracks away stops the music only if it wasn't playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        recorder?.finish()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/// Follows what the player plays, and for how long, to write down the songs that count.
class PlayRecorder(private val context: Context, private val player: Player) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var listen: Listen? = null
    private var playingSince: Long? = null

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 5_000L)
        }
    }

    init {
        player.addListener(this)
        handler.post(tick)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        update()
        close()
        listen = mediaItem?.let { Listen.from(it) }
        if (player.isPlaying) playingSince = System.currentTimeMillis()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        update()
        playingSince = if (isPlaying) System.currentTimeMillis() else null
    }

    fun finish() {
        update()
        close()
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    /// Adds the time played since the last look, and writes the song once it counts.
    private fun update() {
        val since = playingSince ?: return
        val now = System.currentTimeMillis()
        listen?.let {
            it.playedMillis += now - since
            if (!it.written && it.counts()) write(it)
        }
        playingSince = now
    }

    /// A song left after a while is written too; the app decides whether it counts.
    private fun close() {
        listen?.let { if (!it.written && it.playedMillis >= Listen.WORTH_ASKING_MILLIS) write(it) }
        listen = null
    }

    private fun write(listen: Listen) {
        listen.written = true
        PlayerBridge.appendListen(context, listen.json())
    }
}

/// A song as it played.
class Listen(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkURL: String?,
    val durationSeconds: Double?,
    val songID: String,
    val serverID: String?
) {
    val startedAt: Long = System.currentTimeMillis()
    var playedMillis: Long = 0
    var written = false

    /// Last.fm's rule, as TracksCore's `ScrobblePolicy` has it: longer than 30 seconds, played
    /// for half its length or 4 minutes. This only decides when to write; the app checks again.
    fun counts(): Boolean {
        val played = playedMillis / 1000.0
        val duration = durationSeconds ?: return played >= 30.0
        if (duration <= 30.0) return false
        return played >= minOf(duration / 2, 240.0)
    }

    fun json(): String = JSONObject().apply {
        put("title", title)
        put("artist", artist)
        album?.let { put("album", it) }
        artworkURL?.let { put("artworkURL", it) }
        put("startedAt", startedAt / 1000.0)
        durationSeconds?.let { put("duration", it) }
        put("playedFor", playedMillis / 1000.0)
        put("songID", songID)
        serverID?.let { put("serverID", it) }
    }.toString()

    companion object {
        /// Anything shorter was a skip, not worth a line in the queue.
        const val WORTH_ASKING_MILLIS = 10_000L

        fun from(item: MediaItem): Listen? {
            val metadata = item.mediaMetadata
            val title = metadata.title?.toString() ?: return null
            val extras = metadata.extras
            val duration = extras?.getDouble("duration") ?: 0.0
            return Listen(
                title = title,
                artist = metadata.artist?.toString().orEmpty(),
                album = metadata.albumTitle?.toString(),
                // A reference the app resolves, never the cover's address, which carries the
                // server's token.
                artworkURL = extras?.getString("coverRef"),
                durationSeconds = if (duration > 0) duration else null,
                songID = item.mediaId,
                serverID = extras?.getString("serverID")
            )
        }
    }
}

/// What the Swift side calls, through Skip's `AnyDynamicObject`: a queue to play, the
/// transport, and the player's state to show.
///
/// Commands go to a `MediaController` connected to `PlaybackService`, which is what lets the
/// service run in the foreground with its notification while music plays.
/// A class with a companion rather than an `object`: Skip's reflection finds statics through the
/// companion.
class PlayerBridge {
    companion object {
        const val QUEUE_FILE = "pending-listens.jsonl"
        private val lock = Any()
        private val main = Handler(Looper.getMainLooper())
        private var controllerFuture: ListenableFuture<MediaController>? = null
        private var controller: MediaController? = null
        private val pending = mutableListOf<(MediaController) -> Unit>()

        private val context: Context get() = skip.foundation.ProcessInfo.processInfo.androidContext

        /// Runs a command on the main thread once the controller is connected.
        private fun withController(command: (MediaController) -> Unit) {
            main.post {
                controller?.let { command(it); return@post }
                pending.add(command)
                if (controllerFuture == null) {
                    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                    val future = MediaController.Builder(context, token).buildAsync()
                    controllerFuture = future
                    future.addListener({
                        val connected = try { future.get() } catch (_: Exception) { null }
                        controller = connected
                        controllerFuture = null
                        if (connected != null) {
                            pending.forEach { it(connected) }
                        }
                        pending.clear()
                    }, ContextCompat.getMainExecutor(context))
                }
            }
        }

        /// Replaces the queue. Each entry is `{id, url, title, artist, album?, artworkURL?,
        /// coverRef?, duration?, serverID?}`: `artworkURL` for the notification, `coverRef` for the
        /// history.
        fun setQueue(json: String, startIndex: Int, play: Boolean) {
            val entries = JSONArray(json)
            val items = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val extras = Bundle().apply {
                    putDouble("duration", entry.optDouble("duration", 0.0))
                    entry.optString("serverID").takeIf { it.isNotEmpty() }?.let { putString("serverID", it) }
                    entry.optString("coverRef").takeIf { it.isNotEmpty() }?.let { putString("coverRef", it) }
                }
                val metadata = MediaMetadata.Builder()
                    .setTitle(entry.getString("title"))
                    .setArtist(entry.optString("artist"))
                    .setAlbumTitle(entry.optString("album").takeIf { it.isNotEmpty() })
                    .setArtworkUri(entry.optString("artworkURL").takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(extras)
                    .build()
                MediaItem.Builder()
                    .setMediaId(entry.getString("id"))
                    .setUri(entry.getString("url"))
                    .setMediaMetadata(metadata)
                    .build()
            }
            withController {
                it.setMediaItems(items, startIndex.coerceIn(0, maxOf(items.size - 1, 0)), 0L)
                it.prepare()
                if (play) it.play()
            }
        }

        fun play() = withController { it.play() }
        fun stop() = withController { it.stop(); it.clearMediaItems() }
        fun pause() = withController { it.pause() }
        fun next() = withController { it.seekToNextMediaItem() }
        fun skipTo(index: Int) = withController { it.seekTo(index, 0L) }
        fun seekTo(seconds: Double) = withController { it.seekTo((seconds * 1000).toLong()) }
        fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
        /// 0 off, 1 the song, 2 the queue, as `Player.REPEAT_MODE_*`.
        fun setRepeat(mode: Int) = withController { it.repeatMode = mode }

        /// To the start of the song, or the one before if it has only just begun, as players do.
        fun previous() = withController { it.seekToPrevious() }

        /// What's playing, for the mini player and Now Playing. Read on the main thread; empty
        /// until the controller has connected.
        fun snapshot(): String {
            val player = controller ?: return "{}"
            return JSONObject().apply {
                put("index", player.currentMediaItemIndex)
                put("count", player.mediaItemCount)
                put("id", player.currentMediaItem?.mediaId ?: "")
                put("isPlaying", player.isPlaying)
                put("playWhenReady", player.playWhenReady)
                put("isBuffering", player.playbackState == Player.STATE_BUFFERING)
                put("position", player.currentPosition / 1000.0)
                put("duration", if (player.duration == C.TIME_UNSET) 0.0 else player.duration / 1000.0)
                put("shuffle", player.shuffleModeEnabled)
                put("repeat", player.repeatMode)
            }.toString()
        }

        fun appendListen(context: Context, line: String) {
            synchronized(lock) {
                File(context.filesDir, QUEUE_FILE).appendText(line + "\n")
            }
        }
    }
}
