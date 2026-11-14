package com.luke.tracks.ui.play.components

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.luke.tracks.MainActivity
import com.luke.tracks.R
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.play.title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixSong
import uniffi.tracks_engine.SongStats
import uniffi.tracks_engine.foldedName

/** A song as the Play pages show it: what the history knows, and whether it plays here. */
@Immutable
data class SongRef(
    val identity: String,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val artworkUrl: Any?,
    val plays: Long?,
    val playable: PlayableSong?,
) {
    val coverSeed: String get() = albumTitle ?: title
}

fun MixSong.ref(playable: PlayableSong?) = SongRef(
    identity, title, artistName, albumTitle,
    playable?.artworkUri ?: artworkUrl?.takeIf { it.isNotBlank() }, plays.toLong(), playable,
)

fun SongStats.ref(playable: PlayableSong?) = SongRef(
    identity, title, artistName, albumTitle,
    playable?.artworkUri ?: artworkUrl?.takeIf { it.isNotBlank() }, plays.toLong(), playable,
)

fun PlayableSong.ref(plays: Long? = null) = SongRef(identity, title, artistName, albumTitle, artworkUri, plays, this)

/**
 * What a person can do with songs and mixes on the Play pages, with the haptics Google's
 * apps give each: a confirm tick when something is queued, a lighter one for the rest.
 */
@Stable
class PlayActions(
    private val context: Context,
    private val navigator: Navigator,
    private val haptics: HapticFeedback,
    private val scope: CoroutineScope,
) {
    private val graph get() = context.graph

    fun playMix(mix: Mix, songs: List<PlayableSong>, shuffle: Boolean = false, startAt: PlayableSong? = null): Boolean {
        if (songs.isEmpty()) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            return false
        }
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        val start = startAt?.let { songs.indexOf(it) }?.coerceAtLeast(0) ?: 0
        graph.player.play(songs, start, QueueOrigin(mix.kind.title, QueueOrigin.Kind.Mix), shuffle)
        return true
    }

    fun startRadio() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        graph.player.startRadio()
    }

    fun playPause() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        graph.player.playPause()
    }

    fun play(song: SongRef, origin: QueueOrigin? = null) {
        val playable = song.playable ?: return openElsewhere(song)
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        graph.player.play(listOf(playable), 0, origin)
    }

    fun playNext(song: SongRef) {
        song.playable?.let {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            graph.player.playNext(it)
        }
    }

    fun addToQueue(song: SongRef) {
        song.playable?.let {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            graph.player.addToQueue(it)
        }
    }

    /** Leaves the song out of every mix and the radio. */
    fun hearLess(identity: String) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { graph.settings.update { it.copy(hiddenSongs = it.hiddenSongs + identity) } }
    }

    fun undoHearLess(identity: String) {
        scope.launch { graph.settings.update { it.copy(hiddenSongs = it.hiddenSongs - identity) } }
    }

    fun goToArtist(name: String) {
        navigator.open(ArtistRoute(foldedName(name), name))
    }

    fun share(song: SongRef) = shareSong(context, song.title, song.artistName)

    fun openElsewhere(song: SongRef) = openInAnotherApp(context, song.title, song.artistName, song.albumTitle)

    fun shareMix(mix: Mix) {
        val lines = mix.songs.take(10).joinToString("\n") { "${it.title}, ${it.artistName}" }
        share(context, "${mix.kind.title} from Tracks\n\n$lines")
    }

    /** Puts a mix on the home screen, the way Google's apps pin a playlist. */
    fun pinMix(mix: Mix) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tracks://mix/${mix.id}"), context, MainActivity::class.java)
        val shortcut = ShortcutInfoCompat.Builder(context, "mix-${mix.id}")
            .setShortLabel(mix.kind.title)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_radio))
            .setIntent(intent)
            .build()
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
    }
}

@Composable
fun rememberPlayActions(): PlayActions {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return remember(context, navigator, haptics, scope) { PlayActions(context, navigator, haptics, scope) }
}

fun shareSong(context: Context, title: String, artist: String) = share(context, "$title by $artist")

fun shareText(context: Context, text: String) = share(context, text)

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Hands a song this phone can't play to another music app (Spotify, YouTube Music and the
 * like), the way Assistant asks them: play from search.
 */
fun openInAnotherApp(context: Context, title: String, artist: String, album: String?) {
    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putExtra(SearchManager.QUERY, "$title $artist")
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        album?.let { putExtra(MediaStore.EXTRA_MEDIA_ALBUM, it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Never back to Tracks itself: the chooser leaves this app out.
    val chooser = Intent.createChooser(intent, "Open in")
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, MainActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No music app answers; a web search still finds it.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "$title $artist")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
