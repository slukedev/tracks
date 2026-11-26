package com.luke.tracks.spotify

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import com.luke.tracks.BuildConfig
import com.luke.tracks.playback.PcmOpener
import com.luke.tracks.sources.SourceProvider
import kotlinx.coroutines.CoroutineScope

/** Spotify as the rest of the app sees it, in builds that have it. */
interface SpotifyIntegration {
    /** Its entry in Settings > Sources, registered with [com.luke.tracks.sources.SourcesRegistry]. */
    val provider: SourceProvider

    /** Opens a song's audio for the player, by its `spotify:track:` address. */
    val audio: PcmOpener

    /** A song failed in a way that says something about the account. */
    fun playbackFailed(problem: SpotifyProblem)

    /** Saved albums, playlists, liked songs and search. */
    @Composable
    fun Library(onBack: () -> Unit)
}

/** Why Spotify can't play, each with the one sentence the app says. */
enum class SpotifyProblem(val message: String, val retryable: Boolean, val errorCode: Int) {
    NeedsPremium("Spotify Premium is needed to play here.", false, PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
    Refused("Spotify can't play this account in Tracks.", false, PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
    Offline("Can't reach Spotify. Check your connection.", true, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
    Busy("Spotify is busy. Try again in a few minutes.", false, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
    SignedOut("Sign in to Spotify again to play this.", false, PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
    Unavailable("This song isn't available on Spotify.", false, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
    /** Spotify said no to one request while the sign-in itself still works. */
    NotAllowed("Spotify didn't allow this.", false, PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
    ;

    /** Whether it's about the account rather than the one song. */
    val accountWide: Boolean get() = this == NeedsPremium || this == Refused || this == SignedOut
}

/** A Spotify song that couldn't open or keep playing, carried through Media3 to the service. */
class SpotifyPlaybackException(val problem: SpotifyProblem, cause: Throwable? = null) :
    DataSourceException(problem.message, cause, problem.errorCode)

/** The Spotify problem behind a playback error, if that's what it was. */
fun Throwable.spotifyProblem(): SpotifyProblem? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<SpotifyPlaybackException>().firstOrNull()?.problem

object SpotifyUris {
    const val SCHEME = "spotify"

    fun isSpotify(address: String?): Boolean = address?.startsWith("$SCHEME:") == true

    /** The `open.spotify.com` page for a `spotify:<kind>:<id>` address, for Open in Spotify. */
    fun webPage(uri: String): String? {
        val parts = uri.split(':')
        if (parts.size != 3 || parts[0] != SCHEME || parts[2].isBlank()) return null
        return "https://open.spotify.com/${parts[1]}/${parts[2]}"
    }
}

/** Spotify, present only in sideload builds (BuildConfig.SPOTIFY). */
object Spotify {
    @Volatile
    var integration: SpotifyIntegration? = null
        private set

    val available: Boolean get() = BuildConfig.SPOTIFY

    fun install(context: Context, scope: CoroutineScope) {
        if (!BuildConfig.SPOTIFY || integration != null) return
        integration = SpotifyFlavor.create(context.applicationContext, scope)
    }
}
