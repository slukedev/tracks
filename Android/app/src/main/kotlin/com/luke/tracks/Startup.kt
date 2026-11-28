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
import com.luke.tracks.playback.MediaControllerPlayer
import com.luke.tracks.playback.radio.TracksRadio
import com.luke.tracks.sync.TvSync
import com.luke.tracks.sync.deviceIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wires the parts that need the Application: the library, the player, workers, widgets and
 * shortcuts. Each part's owner adds its line here.
 */
object Startup {
    fun run(app: Application, graph: AppGraph) {
        // TV plays from Trackside only: no device files, Subsonic, Last.fm, widgets or shortcuts.
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
        graph.player = MediaControllerPlayer(app).also {
            // Robolectric has no session to connect to; screenshots use the player idle.
            if (Build.FINGERPRINT != "robolectric") it.connect()
        }
        graph.radio = TracksRadio(graph).also { it.watch() }
        // Last.fm scrobbling, widgets, shortcuts and notification channels; none of them on TV.
        com.luke.tracks.system.SystemIntegration.start(app, graph)
        // Trackside Cloud sync, and each Trackside server's listens, now playing and live events.
        val sync = com.luke.tracks.sync.CloudSync.install(graph, servers)
        if (tv) {
            // A TV syncs only with a server it's linked to, so a phone's choice restored from
            // its backup is settled before anything runs.
            graph.scope.launch {
                servers.loading.first { !it }
                TvSync.settle(sync, TvSync.linkedServers(servers.entries.value).map { it.server.id })
                sync.start()
            }
        } else {
            sync.start()
        }
        com.luke.tracks.library.trackside.TracksideServices.install(graph, servers).start()
    }
}
