package com.luke.tracks.sync

import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.engine.Engine
import com.luke.tracks.engine.toEnginePlay
import com.luke.tracks.library.trackside.CloudJson
import com.luke.tracks.library.trackside.CloudRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.tracks_engine.PlaySource
import uniffi.tracks_engine.SourceFilter

/** A source a newer app stored: kept as it came, counted only under All. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaySourceTest {
    private fun record(id: String, source: String) = CloudJson.decodeFromString(
        CloudRecord.serializer(),
        """{"id":"$id","version":1,"device_id":"other","fields":{"title":"Song $id","artistName":"Artist","playedAtMs":1794600000000,"kind":"onDemand","source":"$source"}}""",
    )

    @Test fun anUnknownSourceRoundTripsAndCountsOnlyUnderAll() = runBlocking {
        val vinyl = CloudPlays.entity(record("00000000-0000-4000-8000-000000000001", "vinyl"), null) { false }!!
        val apple = CloudPlays.entity(record("00000000-0000-4000-8000-000000000002", "appleMusic"), null) { false }!!
        assertEquals(PlaySourceValue.Other, vinyl.source)
        assertEquals(PlaySourceValue.Streaming, apple.source)

        // It goes back up as it came.
        assertEquals(JsonPrimitive("vinyl"), CloudPlays.fields(vinyl, "me").jsonObject["source"])
        assertEquals(PlaySource.Unknown("vinyl"), vinyl.toEnginePlay().source)

        val engine = Engine(null)
        engine.upsert(listOf(vinyl, apple))
        assertEquals(2uL, engine.read { totals(Engine.allTime, SourceFilter.ALL).plays })
        assertEquals(1uL, engine.read { totals(Engine.allTime, SourceFilter.APPLE_MUSIC).plays })
    }
}
