package com.luke.tracks.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A kind of source with fixed sources, for screenshots and tests. */
class FakeProvider(
    override val kind: SourceKind,
    list: List<Source>,
    offers: Boolean = kind == SourceKind.Server,
) : SourceProvider {
    override val sources: StateFlow<List<Source>> = MutableStateFlow(list)
    override val offersAdd: StateFlow<Boolean> = MutableStateFlow(offers)
    val removed = mutableListOf<SourceId>()
    override suspend fun remove(id: SourceId) {
        removed += id
    }
}

object FakeSources {
    val device = Source(SourceId.Device, "On This Device", SourceKind.Device, SourceStatus.Songs(2104), songCount = 2104)
    val home = Source(SourceId.Server("s1"), "Home", SourceKind.Server, SourceStatus.Connected, songCount = 48_000, address = "https://home.example.net")
    val studio = Source(SourceId.Server("s2"), "Studio NAS", SourceKind.Server, SourceStatus.Offline("Can't reach this address."), address = "http://192.168.1.20:4533")

    /** A registry with these sources and nothing stored, so tests don't share a filter. */
    fun registry(
        vararg sources: Source,
        name: String = "test-sources",
        providers: List<SourceProvider> = sources.groupBy { it.kind }.map { (kind, list) -> FakeProvider(kind, list) },
    ): SourcesRegistry {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        return SourcesRegistry(prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), providers)
    }
}
