package com.luke.tracks.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.library.trackside.TracksideTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** On TV only Trackside servers are used; a Subsonic server restored from a phone stays saved but untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerLibraryTvTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as TracksApplication
    private val settings get() = app.graph.settings

    private val plain = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
    }

    private val navidrome = SubsonicServer("s1", "Navidrome", "https://navidrome.example.net", "luke")
    private val home = SubsonicServer(
        "t1", "Home", "https://home.example.net", "luke",
        kind = ServerKind.Trackside, serverUuid = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b",
    )

    private fun library(tracksideOnly: Boolean) = ServerLibrary(
        app, settings, plain, app.graph.scope, network = null,
        deviceId = { "tv" },
        tracksideTransport = { TracksideTransport { _, _ -> throw IOException("offline in tests") } },
        subsonicTransport = { _, _ -> SubsonicTransport { throw IOException("offline in tests") } },
        networkIdentity = { null },
        tracksideOnly = tracksideOnly,
    )

    private fun entriesOf(library: ServerLibrary) = runBlocking {
        settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(navidrome, home))) }
        library.start()
        withTimeout(10_000) { library.loading.first { !it } }
        library.entries.value.map { it.server.id }
    }

    @Test fun tvUsesOnlyTrackside() {
        assertEquals(listOf("t1"), entriesOf(library(tracksideOnly = true)))
        val saved = runBlocking { ServerLibrary.parse(settings.settings.first().subsonicServers) }
        assertEquals(listOf("s1", "t1"), saved.map { it.id })
    }

    @Test fun phoneUsesEveryServer() {
        assertEquals(listOf("s1", "t1"), entriesOf(library(tracksideOnly = false)))
    }
}
