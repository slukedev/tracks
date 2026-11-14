package com.luke.tracks

import android.app.Application
import android.os.Build
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.DeviceLibrary
import com.luke.tracks.library.KeystoreSecretBox
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.playback.MediaControllerPlayer
import com.luke.tracks.playback.NoRadio

/**
 * Wires the parts that need the Application: the library, the player, workers, widgets and
 * shortcuts. Each part's owner adds its line here.
 */
object Startup {
    fun run(app: Application, graph: AppGraph) {
        graph.library = CombinedLibrary(
            context = app,
            scope = graph.scope,
            device = DeviceLibrary(app, graph.scope),
            servers = ServerLibrary(app, graph.settings, KeystoreSecretBox(), graph.scope),
            engine = graph.engine,
            sampleData = graph.sampleData,
        ).also { it.start() }
        graph.player = MediaControllerPlayer(app).also {
            // Robolectric has no session to connect to; screenshots use the player idle.
            if (Build.FINGERPRINT != "robolectric") it.connect()
        }
        graph.radio = NoRadio()
    }
}
