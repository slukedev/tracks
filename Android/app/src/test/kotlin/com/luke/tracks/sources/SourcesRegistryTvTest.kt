package com.luke.tracks.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On TV the registry holds servers (Trackside) and nothing else, however the others arrive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourcesRegistryTvTest {
    private fun registry(allowed: Set<SourceKind>): SourcesRegistry {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("tv-sources", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val builtIn = listOf(
            FakeProvider(SourceKind.Device, listOf(FakeSources.device)),
            FakeProvider(SourceKind.Server, listOf(FakeSources.home, FakeSources.studio)),
        )
        return SourcesRegistry(
            prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), builtIn, allowedKinds = allowed,
        )
    }

    @Test fun tvKeepsOnlyServers() {
        val registry = registry(TV_SOURCE_KINDS)
        registry.register(FakeProvider(SourceKind.Device, listOf(FakeSources.device)))

        assertEquals(listOf(SourceKind.Server), registry.registered.value.map { it.kind })
        assertEquals(listOf(FakeSources.home, FakeSources.studio), registry.sources.value)
        assertNull(registry.provider(SourceKind.Device))
        assertEquals(
            listOf(SourceId.Server("s1"), SourceId.Server("s2")).map(PlayFromPlace::From),
            registry.arrangedPlayFrom(),
        )
    }

    @Test fun aTvsChoiceOfOneServerAndItsPlayFromOrderAreKeptAsThePhoneKeepsThem() {
        val registry = registry(TV_SOURCE_KINDS)
        registry.select(SourceSelection.Only(SourceId.Server("s2")))
        assertEquals(SourceSelection.Only(SourceId.Server("s2")), registry.selection.value)

        val studioFirst = listOf(SourceId.Server("s2"), SourceId.Server("s1")).map(PlayFromPlace::From)
        registry.setPlayFrom(studioFirst)
        assertEquals(studioFirst, registry.arrangedPlayFrom())

        // The same stored form as the phone's and the iPhone's, places this TV doesn't have kept.
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("tv-sources", Context.MODE_PRIVATE)
        assertEquals("server:s2", prefs.getString("sourceFilter", null))
        assertEquals("device\nserver:s2\nserver:s1", prefs.getString("playFromOrder", null))
        val again = SourcesRegistry(prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), listOf(FakeProvider(SourceKind.Server, listOf(FakeSources.home, FakeSources.studio))), allowedKinds = TV_SOURCE_KINDS)
        assertEquals(studioFirst, again.arrangedPlayFrom())
        assertEquals(SourceSelection.Only(SourceId.Server("s2")), again.selection.value)
    }

    @Test fun phoneKeepsEverySource() {
        val registry = registry(SourceKind.entries.toSet())
        assertEquals(SourceKind.entries.toList(), registry.sources.value.map { it.kind }.distinct())
    }
}
