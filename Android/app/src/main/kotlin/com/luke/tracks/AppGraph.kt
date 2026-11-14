package com.luke.tracks

import android.content.Context
import com.luke.tracks.data.HistoryDatabase
import com.luke.tracks.data.HistoryRepository
import com.luke.tracks.data.SettingsRepository
import com.luke.tracks.engine.Engine
import com.luke.tracks.library.MusicLibrary
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.RadioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Everything the app shares, made once per process. Sample data uses its own database and
 * index, so turning it on never touches the real history.
 */
class AppGraph(val context: Context, val sampleData: Boolean) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)

    private val databaseName = if (sampleData) "sample.db" else "history.db"
    private val indexName = if (sampleData) "sample-index.sqlite" else "index.sqlite"

    val engine = Engine(context.filesDir.resolve(indexName).path)
    val history = HistoryRepository(HistoryDatabase.open(context, databaseName), engine)

    /** Set by the library implementation at startup. */
    lateinit var library: MusicLibrary

    /** Set by the playback implementation at startup. */
    lateinit var player: PlayerController

    /** Set by the Play tab's Tracks Radio at startup. */
    lateinit var radio: RadioSource
}
