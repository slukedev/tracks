package com.luke.tracks

import android.app.Application
import com.luke.tracks.library.EmptyLibrary
import com.luke.tracks.playback.IdlePlayer

/**
 * Wires the parts that need the Application: the library, the player, workers, widgets and
 * shortcuts. Each part's owner adds its line here.
 */
object Startup {
    fun run(app: Application, graph: AppGraph) {
        graph.library = EmptyLibrary()
        graph.player = IdlePlayer()
    }
}
