package com.luke.tracks.insights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.engine.Engine
import com.luke.tracks.ui.detail.AlbumScreen
import com.luke.tracks.ui.detail.ArtistScreen
import com.luke.tracks.ui.detail.SongScreen
import com.luke.tracks.ui.history.HistoryScreen
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.search.SearchScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import uniffi.tracks_engine.SourceFilter

/** History, the detail pages and Search, with sample history. */
class DetailScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun nav(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(LocalNavigator provides Navigator()) { content() }
    }

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    fun history() {
        withSampleHistory()
        snap("history", settleMs = 5000, content = nav { HistoryScreen() })
    }

    @Test
    fun historyEmpty() {
        runBlocking { graph.history.replaceAll(emptyList()) }
        snap("history-empty", content = nav { HistoryScreen() })
    }

    @Test
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    fun artist() {
        withSampleHistory()
        val top = runBlocking { graph.engine.read { artistChart(Engine.allTime, null, SourceFilter.ALL, 1u).rows.first().artist } }
        snap("artist", settleMs = 6000, content = nav { ArtistScreen(top.identity, top.name) })
    }

    @Test
    @Config(qualifiers = "w412dp-h2200dp-xxhdpi")
    fun album() {
        withSampleHistory()
        val top = runBlocking { graph.engine.read { albumChart(Engine.allTime, null, SourceFilter.ALL, 1u).rows.first().album } }
        snap("album", settleMs = 6000, content = nav { AlbumScreen(top.identity, top.title, top.artistName) })
    }

    @Test
    @Config(qualifiers = "w412dp-h2200dp-xxhdpi")
    fun song() {
        withSampleHistory()
        // A song with more than one artist when there is one, to show credits.
        val songs = runBlocking { graph.engine.read { songChart(Engine.allTime, null, SourceFilter.ALL, 500u).rows.map { it.song } } }
        val pick = songs.firstOrNull { Identities.credits(it.artistName, it.title).size > 1 } ?: songs.first()
        snap("song", settleMs = 6000, content = nav { SongScreen(pick.identity, pick.title, pick.artistName) })
    }

    @Test
    fun search() {
        withSampleHistory()
        snap("search", settleMs = 4000, content = nav { SearchScreen(initialQuery = "ma") })
    }

    /** The album identity the app works out matches the engine's own. */
    @Test
    fun albumIdentityMatchesEngine() {
        withSampleHistory()
        val songs = runBlocking { graph.engine.read { songChart(Engine.allTime, null, SourceFilter.ALL, 200u).rows.map { it.song } } }
        val albums = runBlocking { graph.engine.read { albumChart(Engine.allTime, null, SourceFilter.ALL, 500u).rows.map { it.album.identity }.toSet() } }
        val worked = songs.mapNotNull { Identities.album(it.albumTitle, it.artistName, it.title) }
        assertEquals(true, worked.isNotEmpty())
        assertEquals(emptyList<String>(), worked.filter { it !in albums })
    }

    @Test
    @Config(qualifiers = "w412dp-h3600dp-xxhdpi")
    fun historyFull() {
        withSampleHistory()
        snap("full-history", settleMs = 6000, content = nav { HistoryScreen() })
    }
}
