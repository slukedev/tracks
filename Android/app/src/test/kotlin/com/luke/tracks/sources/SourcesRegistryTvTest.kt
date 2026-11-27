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

/** On TV the registry holds Apple Music and nothing else, however the others arrive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourcesRegistryTvTest {
    private val appleMusic = Source(SourceId.AppleMusic, "Apple Music", SourceKind.AppleMusic, SourceStatus.Connected)

    private fun registry(allowed: Set<SourceKind>): SourcesRegistry {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("tv-sources", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val builtIn = listOf(
            FakeProvider(SourceKind.Device, listOf(FakeSources.device)),
            FakeProvider(SourceKind.Server, listOf(FakeSources.home, FakeSources.studio)),
        )
        return SourcesRegistry(
            prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), builtIn,
            spotifyAllowed = true, allowedKinds = allowed,
        )
    }

    @Test fun tvKeepsOnlyAppleMusic() {
        val registry = registry(TV_SOURCE_KINDS)
        assertEquals(emptyList<Source>(), registry.sources.value)

        registry.register(FakeProvider(SourceKind.Spotify, listOf(FakeSources.spotify)))
        registry.register(FakeProvider(SourceKind.AppleMusic, listOf(appleMusic)))

        assertEquals(listOf(SourceKind.AppleMusic), registry.registered.value.map { it.kind })
        assertEquals(listOf(appleMusic), registry.sources.value)
        assertNull(registry.provider(SourceKind.Device))
        assertEquals(listOf(PlayFromPlace.From(SourceId.AppleMusic)), registry.arrangedPlayFrom())
    }

    @Test fun phoneKeepsEverySource() {
        val registry = registry(SourceKind.entries.toSet())
        registry.register(FakeProvider(SourceKind.Spotify, listOf(FakeSources.spotify)))
        registry.register(FakeProvider(SourceKind.AppleMusic, listOf(appleMusic)))
        assertEquals(SourceKind.entries.toList(), registry.sources.value.map { it.kind }.distinct())
    }
}
