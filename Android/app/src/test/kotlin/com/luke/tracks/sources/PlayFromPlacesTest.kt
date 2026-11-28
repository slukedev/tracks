package com.luke.tracks.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Streaming services and Get as places in Play From, and orders saved before they were. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayFromPlacesTest {
    private val apple = ServicePlace(PlayFromPlace.APPLE_MUSIC, "Apple Music")
    private val tidal = ServicePlace("tidal", "Tidal")

    private fun prefs(name: String, stored: String? = null) =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE).also {
            it.edit().clear().apply { if (stored != null) putString("playFromOrder", stored).putBoolean("playFromOrderMigrated", true) }.commit()
        }

    private fun registry(prefs: android.content.SharedPreferences, servers: List<Source> = listOf(FakeSources.home)) = SourcesRegistry(
        prefs,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        listOf(FakeProvider(SourceKind.Device, listOf(FakeSources.device)), FakeProvider(SourceKind.Server, servers)),
    )

    @Test fun placesAreStoredAsTheIphoneStoresThem() {
        assertEquals("appleMusic", PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC).raw)
        assertEquals("service:tidal", PlayFromPlace.Service("tidal").raw)
        assertEquals("get", PlayFromPlace.Get.raw)
        val stored = "appleMusic\nservice:tidal\nget\ndownloads\nserver:s1\ndevice\nsomethingNew"
        val order = PlayFromOrder.parse(stored)
        assertEquals(
            listOf(PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC), PlayFromPlace.Service("tidal"), PlayFromPlace.Get, PlayFromPlace.Downloads),
            order.places.take(4),
        )
        assertEquals(PlayFromPlace.From(SourceId.Unknown("somethingNew")), order.places.last())
        assertEquals(stored, order.raw)
    }

    @Test fun anExistingOrderKeepsItsPlacesAndGainsTheServicesThenGet() {
        val prefs = prefs("play-from-existing", "server:s1\ndevice")
        val registry = registry(prefs)
        registry.noteOffered(listOf(apple, tidal), getsCopies = true)
        assertEquals("server:s1\ndevice\nappleMusic\nservice:tidal\nget", prefs.getString("playFromOrder", null))
        assertEquals(
            listOf("Home", "On This Device", "Apple Music", "Tidal", "Get"),
            registry.playFromChoices.value.map { it.name },
        )
    }

    @Test fun anIphonesAppleMusicPlaceKeepsItsPositionAndUnknownPlacesRoundTrip() {
        val prefs = prefs("play-from-iphone", "appleMusic\nspotify\nserver:s1\ndevice")
        val registry = registry(prefs)
        registry.noteOffered(listOf(apple), getsCopies = true)
        assertEquals("appleMusic\nspotify\nserver:s1\ndevice\nget", prefs.getString("playFromOrder", null))
        assertEquals(
            listOf(PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC), PlayFromPlace.From(SourceId.Server("s1")), PlayFromPlace.From(SourceId.Device), PlayFromPlace.Get),
            registry.arrangedPlayFrom(),
        )
    }

    @Test fun getStartsOnAndLastAndAPlaceAddedLaterGoesBeforeIt() {
        val prefs = prefs("play-from-new")
        val servers = MutableStateFlow(listOf(FakeSources.home))
        val provider = object : SourceProvider {
            override val kind = SourceKind.Server
            override val sources = servers
            override val offersAdd = MutableStateFlow(true)
            override suspend fun remove(id: SourceId) = Unit
        }
        val registry = SourcesRegistry(prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), listOf(provider), loadedServers = MutableStateFlow(listOf("s1")))
        registry.noteOffered(emptyList(), getsCopies = true)
        assertTrue(registry.getOn.value)
        assertEquals("device\nserver:s1\nget", prefs.getString("playFromOrder", null))
        registry.noteOffered(listOf(apple), getsCopies = true)
        servers.value = listOf(FakeSources.home, FakeSources.studio)
        assertEquals("device\nserver:s1\nappleMusic\nserver:s2\nget", prefs.getString("playFromOrder", null))
    }

    @Test fun getTurnedOffKeepsItsPlaceAndStillMoves() {
        val prefs = prefs("play-from-get-off", "get\nserver:s1\ndevice")
        val registry = registry(prefs)
        registry.noteOffered(listOf(apple), getsCopies = true)
        registry.setGet(false)
        assertFalse(registry.getOn.value)
        val shown = registry.arrangedPlayFrom()
        assertEquals(listOf("Get", "Home", "On This Device", "Apple Music"), registry.playFromChoices.value.map { it.name })
        registry.setPlayFrom(shown.reversed())
        assertEquals("appleMusic\ndevice\nserver:s1\nget", prefs.getString("playFromOrder", null))
        assertFalse("kept across launches", registry(prefs).getOn.value)
    }

    @Test fun getIsAPlaceOnlyWhereAServerCouldGetASong() {
        val registry = registry(prefs("play-from-no-get"))
        registry.noteOffered(listOf(apple), getsCopies = false)
        assertFalse(PlayFromPlace.Get in registry.arrangedPlayFrom())
    }
}
