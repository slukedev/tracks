package com.luke.tracks

import android.app.Application
import android.content.Context
import com.luke.tracks.data.SampleData
import kotlinx.coroutines.launch

class TracksApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Read once at launch: switching sample data on or off restarts the app.
        val sample = getSharedPreferences("launch", MODE_PRIVATE).getBoolean("sampleData", false)
        graph = AppGraph(this, sample)
        Startup.run(this, graph)
        graph.scope.launch {
            if (sample) SampleData.fillIfEmpty(graph.history)
            graph.history.syncIndex()
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as TracksApplication).graph
