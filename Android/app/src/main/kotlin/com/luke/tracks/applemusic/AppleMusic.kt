package com.luke.tracks.applemusic

import android.content.Context
import androidx.compose.runtime.Composable
import com.luke.tracks.BuildConfig
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.luke.tracks.library.KeystoreSecretBox
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.net.Http
import com.luke.tracks.sources.Removal
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceProvider
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.ui.applemusic.AppleMusicSourcePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uniffi.tracks_engine.songIdentity

/**
 * Apple Music, present when the build has Apple's MusicKit SDK, and then always offered in
 * Sources. Signing in needs a developer token from a paired Trackside server or the build.
 */
object AppleMusic {
    @Volatile
    var service: AppleMusicService? = null
        private set

    @Volatile
    var provider: AppleMusicSourceProvider? = null
        private set

    /** Apple Music's shelves on Play. */
    @Volatile
    var shelves: AppleMusicShelves? = null
        private set

    /**
     * Sets Apple Music up when the build has the SDK. Developer tokens come from a paired
     * Trackside server that offers them, then the one built in.
     */
    fun install(context: Context, scope: CoroutineScope, servers: ServerLibrary) {
        if (service != null) return
        val sdk = AppleMusicSdkFlavor.create(context.applicationContext) ?: run {
            com.luke.tracks.net.Logs.i("Apple Music: off, this build has no MusicKit SDK (or this phone can't run it)")
            return
        }
        val trackside = TracksideDeveloperTokens(
            scope = scope,
            servers = servers.entries.map { entries -> entries.filter { it.server.isTrackside }.map { it.server.id } },
            clientFor = servers::tracksideClient,
            ready = { servers.loading.first { !it } },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = trackside.refresh()
        })
        install(context.applicationContext, scope, sdk, FirstDeveloperToken(listOf(trackside, BuildTimeDeveloperToken(BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN))))
    }

    private fun install(app: Context, scope: CoroutineScope, sdk: AppleMusicSdk, tokens: DeveloperTokenProvider) {
        val created = AppleMusicService(
            tokens = tokens,
            vault = SealedAppleMusicVault(app.getSharedPreferences(SealedAppleMusicVault.PREFS, Context.MODE_PRIVATE), KeystoreSecretBox("tracks.appleMusic")),
            sdk = sdk,
            scope = scope,
            appInstalled = { isAppInstalled(app) },
            identity = ::songIdentity,
            cache = app.filesDir.resolve("apple-music-library.json"),
            apiFor = { user -> AppleMusicApi(Http.api, tokens, user) },
        )
        service = created
        provider = AppleMusicSourceProvider(created, scope)
        created.start()
        shelves = AppleMusicShelves(created, scope)
    }

    /** The Apple Music app, which MusicKit's sign-in goes through. */
    const val APP_PACKAGE = "com.apple.android.music"

    @Suppress("DEPRECATION")
    fun isAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(APP_PACKAGE, 0) }.isSuccess
}

/** Apple Music in Settings > Sources: listed once signed in, with its own page. */
class AppleMusicSourceProvider(private val service: AppleMusicService, scope: CoroutineScope) : SourceProvider {
    override val kind = SourceKind.AppleMusic

    override val sources: StateFlow<List<Source>> = combine(service.state, service.library) { state, library -> sources(state, library.size) }
        .stateIn(scope, SharingStarted.Eagerly, sources(service.state.value, service.library.value.size))

    /** Offered whenever the build has MusicKit; without a developer token its page says what's missing. */
    override val offersAdd: StateFlow<Boolean> = sources.map { it.isEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, sources.value.isEmpty())

    override fun sourceOf(mediaId: String): SourceId? = if (AppleMusicUris.isAppleMusic(mediaId)) SourceId.AppleMusic else null

    override fun removal(source: Source) = Removal(title = "Remove Apple Music?", message = "Your plays stay in History.", confirm = "Remove")

    override suspend fun remove(id: SourceId) {
        if (id == SourceId.AppleMusic) service.signOut()
    }

    @Composable
    override fun Page(source: Source?, onDone: () -> Unit) = AppleMusicSourcePage(service, onDone)

    companion object {
        const val NAME = "Apple Music"

        fun sources(state: AppleMusicState, songs: Int = 0): List<Source> {
            val status = when (state) {
                is AppleMusicState.Connected -> SourceStatus.Connected
                is AppleMusicState.NeedsSignIn -> SourceStatus.SignIn
                AppleMusicState.Checking -> SourceStatus.Syncing()
                else -> return emptyList()
            }
            return listOf(Source(SourceId.AppleMusic, NAME, SourceKind.AppleMusic, status, songCount = songs.takeIf { it > 0 }))
        }
    }
}
