package com.luke.tracks

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.DeviceLibrary
import com.luke.tracks.library.KeystoreSecretBox
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.sync.deviceIdentity
import com.luke.tracks.playback.MediaControllerPlayer
import com.luke.tracks.playback.radio.TracksRadio
import com.luke.tracks.sources.sources
import com.luke.tracks.spotify.Spotify
import com.luke.tracks.applemusic.AppleMusic

/**
 * Wires the parts that need the Application: the library, the player, workers, widgets and
 * shortcuts. Each part's owner adds its line here.
 */
object Startup {
    fun run(app: Application, graph: AppGraph) {
        // TV plays from Trackside only: no device files, Subsonic, Spotify, Apple Music's SDK,
        // Last.fm, widgets, shortcuts or Trackside Cloud sync.
        val tv = graph.formFactor.tv
        val servers = ServerLibrary(
            app, graph.settings, KeystoreSecretBox(), graph.scope,
            deviceId = { graph.deviceIdentity.id() },
            tracksideOnly = tv,
        )
        graph.library = CombinedLibrary(
            context = app,
            scope = graph.scope,
            device = DeviceLibrary(app, graph.scope),
            servers = servers,
            engine = graph.engine,
            sampleData = graph.sampleData,
            deviceFiles = !tv,
        ).also { it.start() }
        // Back in the foreground, servers may have moved: ask Trackside where they are now.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = servers.refreshAddresses()
        })
        if (!tv) {
            // Spotify, in sideload builds only.
            Spotify.install(app, graph.scope)
            Spotify.integration?.let { graph.sources.register(it.provider) }
            // Apple Music, when there's a developer token and the build has Apple's MusicKit SDK.
            AppleMusic.install(app, graph.scope, servers)
            AppleMusic.provider?.let { graph.sources.register(it) }
        }
        graph.player = MediaControllerPlayer(app).also {
            // Robolectric has no session to connect to; screenshots use the player idle.
            if (Build.FINGERPRINT != "robolectric") it.connect()
        }
        graph.radio = TracksRadio(graph).also { it.watch() }
        // Last.fm scrobbling, widgets, shortcuts and notification channels; none of them on TV.
        com.luke.tracks.system.SystemIntegration.start(app, graph)
        // Trackside Cloud sync, and each Trackside server's listens, now playing and live events.
        com.luke.tracks.sync.CloudSync.install(graph, servers).start()
        com.luke.tracks.library.trackside.TracksideServices.install(graph, servers).start()
    }
}
