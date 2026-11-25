package com.luke.tracks.spotify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.Composable
import com.luke.tracks.R
import com.luke.tracks.library.KeystoreSecretBox
import com.luke.tracks.playback.PcmOpener
import com.luke.tracks.sources.Removal
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceProvider
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.ui.spotify.SpotifyLibraryScreen
import com.luke.tracks.ui.spotify.SpotifySourcePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uniffi.tracks_engine.SpotifyAccount
import uniffi.tracks_engine.SpotifyConfig
import uniffi.tracks_engine.SpotifyLogin

/** Sideload builds: Spotify on the engine's librespot. */
object SpotifyFlavor {
    val markRes: Int? = R.drawable.ic_spotify

    fun create(context: Context, scope: CoroutineScope): SpotifyIntegration {
        val service = SpotifyService(
            engine = LibrespotEngine,
            vault = SealedSpotifyVault(context.getSharedPreferences(SealedSpotifyVault.PREFS, Context.MODE_PRIVATE), KeystoreSecretBox("tracks.spotify")),
            settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE),
            config = SpotifyConfig(osVersion = Build.VERSION.RELEASE, tempDir = context.cacheDir.resolve("spotify").apply { mkdirs() }.path),
            scope = scope,
            onCellular = { onCellular(context) },
        )
        return SpotifyLive(service, scope)
    }

    private const val SETTINGS = "spotify.settings"

    private fun onCellular(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}

private object LibrespotEngine : SpotifyEngine {
    override fun open(credentials: ByteArray, config: SpotifyConfig) = SpotifyAccount.open(credentials, config)
    override fun login(redirectUri: String) = SpotifyLogin(redirectUri)
}

class SpotifyLive(val service: SpotifyService, scope: CoroutineScope) : SpotifyIntegration {
    override val provider: SourceProvider = SpotifySourceProvider(service, scope)
    override val audio: PcmOpener get() = service.audio
    override fun playbackFailed(problem: SpotifyProblem) = service.playbackFailed(problem)

    @Composable
    override fun Library(onBack: () -> Unit) = SpotifyLibraryScreen(service, onBack)
}

/** Spotify in Settings > Sources: listed once signed in, with its own page. */
class SpotifySourceProvider(private val service: SpotifyService, scope: CoroutineScope) : SourceProvider {
    override val kind = SourceKind.Spotify

    override val sources: StateFlow<List<Source>> = service.state
        .map(::sources)
        .stateIn(scope, SharingStarted.Eagerly, sources(service.state.value))

    override val offersAdd: StateFlow<Boolean> = sources
        .map { it.isEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, sources.value.isEmpty())

    override val addSubtitle: String = "Needs Premium"

    override val iconRes: Int? = SpotifyFlavor.markRes

    override fun sourceOf(mediaId: String): SourceId? = if (SpotifyUris.isSpotify(mediaId)) SourceId.Spotify else null

    override fun removal(source: Source) = Removal(
        title = "Sign Out of Spotify?",
        message = "Your plays stay in History. Spotify playlists leave your library.",
        confirm = "Sign Out",
    )

    override suspend fun remove(id: SourceId) {
        if (id == SourceId.Spotify) service.signOut()
    }

    @Composable
    override fun Page(source: Source?, onDone: () -> Unit) = SpotifySourcePage(service, onDone)

    companion object {
        const val NAME = "Spotify"

        fun sources(state: SpotifyState): List<Source> {
            val status = when (state) {
                is SpotifyState.Connected -> if (state.refused) SourceStatus.SignIn else SourceStatus.Connected
                is SpotifyState.NeedsSignIn -> SourceStatus.SignIn
                // Checking an account already listed, or one being added.
                SpotifyState.Checking -> SourceStatus.Syncing()
                else -> return emptyList()
            }
            return listOf(Source(SourceId.Spotify, NAME, SourceKind.Spotify, status))
        }
    }
}
