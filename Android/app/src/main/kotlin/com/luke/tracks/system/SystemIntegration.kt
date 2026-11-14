package com.luke.tracks.system

import android.app.Application
import android.os.Build
import com.luke.tracks.AppGraph
import com.luke.tracks.lastfm.ScrobbleWorker
import com.luke.tracks.widget.WidgetUpdates
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The parts of Tracks that live outside its window: Last.fm scrobbling after each kept song,
 * the home screen widgets and the launcher's mix shortcuts, kept current as the history
 * changes.
 */
object SystemIntegration {
    @OptIn(FlowPreview::class)
    fun start(app: Application, graph: AppGraph) {
        // Robolectric screenshot runs have no launcher, widgets or WorkManager to talk to.
        if (Build.FINGERPRINT == "robolectric") return
        Notifications.createChannels(app)
        ScrobbleWorker.schedule(app)

        graph.scope.launch {
            graph.history.kept.collect { ScrobbleWorker.enqueue(app) }
        }
        // Every write ticks the engine's revision: kept plays, imports, removals and the
        // index catching up at launch. Widgets follow quickly, shortcuts less often.
        graph.scope.launch {
            graph.engine.revision.drop(1).debounce(1_500).collect { WidgetUpdates.updateAll(app) }
        }
        graph.scope.launch {
            graph.engine.revision.debounce(10_000).collect { Shortcuts.update(app, graph) }
        }
    }
}
