package com.luke.tracks

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.luke.tracks.net.Http
import com.luke.tracks.data.SampleData
import com.luke.tracks.system.FormFactor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TracksApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        com.luke.tracks.net.CrashLog.install(this)
        // Read once at launch: switching sample data on or off restarts the app.
        val sample = getSharedPreferences("launch", MODE_PRIVATE).getBoolean("sampleData", false)
        val formFactor = FormFactor.of(this)
        // TV boxes are slow enough that main-thread disk and network work shows; debug builds say where.
        if (BuildConfig.DEBUG && formFactor.tv) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        graph = AppGraph(this, sample, formFactor)
        Startup.run(this, graph)
        graph.scope.launch {
            if (sample) SampleData.fillIfEmpty(graph.history)
            graph.history.syncIndex()
        }
    }

    /**
     * Never called on a device; Robolectric calls it as each test ends, so this graph's work
     * (MediaStore reads, server sync) stops instead of failing in a later test.
     */
    override fun onTerminate() {
        if (::graph.isInitialized) graph.scope.cancel()
        super.onTerminate()
    }

    /**
     * Covers load over the app's own client, so a server's cover links are signed as they go
     * out and cached under the link, never the signed address. Low-memory devices (many TV
     * boxes) keep fewer decoded covers in memory.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.media })) }
        .apply {
            if (graph.formFactor.lowRam) memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
        }
        .build()

    /** WorkManager starts on first use (its startup initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
}

val Context.graph: AppGraph get() = (applicationContext as TracksApplication).graph
