package com.luke.tracks

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.luke.tracks.net.Http
import com.luke.tracks.data.SampleData
import kotlinx.coroutines.launch

class TracksApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        com.luke.tracks.net.CrashLog.install(this)
        // Read once at launch: switching sample data on or off restarts the app.
        val sample = getSharedPreferences("launch", MODE_PRIVATE).getBoolean("sampleData", false)
        graph = AppGraph(this, sample)
        Startup.run(this, graph)
        graph.scope.launch {
            if (sample) SampleData.fillIfEmpty(graph.history)
            graph.history.syncIndex()
        }
    }

    /**
     * Covers load over the app's own client, so a server's cover links are signed as they go
     * out and cached under the link, never the signed address.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.media })) }
        .build()

    /** WorkManager starts on first use (its startup initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}

val Context.graph: AppGraph get() = (applicationContext as TracksApplication).graph
