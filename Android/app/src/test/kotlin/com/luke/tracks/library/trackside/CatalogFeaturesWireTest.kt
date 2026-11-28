package com.luke.tracks.library.trackside

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String): String =
    requireNotNull(CatalogFeaturesWireTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

/** The server's newer service features, read from its own fixtures: saving, radio, takeover, held starts, matching. */
class CatalogFeaturesWireTest {
    @Test fun meSaysWhichFeaturesThisKeyHas() {
        val all = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me.json")).catalogFeatures
        assertEquals(CatalogFeatures(save = true, radio = true, match = true, queueRemove = true, hold = true, takeover = true), all)
        // An older server leaves every flag out: none of it is offered.
        assertEquals(CatalogFeatures(), TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me-without-acquisition.json")).catalogFeatures)
    }

    @Test fun aServiceSaysWhatItsHeartCanSave() {
        val list = TracksideJson.decodeFromString(TracksideCatalogServices.serializer(), fixture("catalog-services.json"))
        val spotify = list.services.first { it.logoKey == "spotify" }
        assertTrue(spotify.saves("track"))
        assertTrue(spotify.saves("album"))
        assertFalse("a kind it can't write", spotify.saves("radio"))
        list.services.filterNot { it.can(CatalogCapability.LIBRARY_EDIT) }.forEach { assertFalse(it.saves("track")) }
    }

    @Test fun savingAnswersWhereTheItemNowStands() {
        val saved = TracksideJson.decodeFromString(TracksideCatalogSaved.serializer(), fixture("catalog-saved.json"))
        assertTrue(saved.saved)
        assertEquals("bqtyy4kcexcsg6wa0y4w5", saved.serviceId)
        assertTrue(saved.ref.startsWith("mc1."))
    }

    @Test fun aFailedCommandSaysWhyAndWhoHoldsTheStream() {
        val failed = TracksideJson.decodeFromString(SpeakerEvents.Command.serializer(), fixture("speaker-command-failed-event.json"))
        assertEquals("failed", failed.outcome)
        val error = failed.error!!
        assertEquals("command_failed", error.code)
        assertEquals("service_busy", error.reason)
        assertEquals("bqtyy4kcexcsg6wa0y4w5", error.serviceId)
        assertEquals("Living Room", error.holderName)
    }

    @Test fun playBodiesAreWhatTheServerExpects() {
        fun body(request: TracksideSpeakerPlayRequest) = TracksideJson.parseToJsonElement(TracksideJson.encodeToString(TracksideSpeakerPlayRequest.serializer(), request))
        val ref = "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0"
        assertEquals(
            TracksideJson.parseToJsonElement(fixture("speaker-play-held-request.json")),
            body(TracksideSpeakerPlayRequest(listOf(TracksideSpeakerPlayRequest.Item(ref = ref)), hold = true, takeover = true)),
        )
        // The radio body leaves `mode` out, which the server reads as replace.
        val radio = body(
            TracksideSpeakerPlayRequest(
                listOf(TracksideSpeakerPlayRequest.Item(ref = ref)),
                context = TracksideSpeaker.Context("station", name = "Halo Radio"),
                radio = true,
                takeover = true,
            ),
        ) as JsonObject
        assertEquals(TracksideJson.parseToJsonElement(fixture("speaker-play-radio-request.json")), JsonObject(radio.filterKeys { it != "mode" }))
        // Nothing new is sent unless asked for.
        val plain = body(TracksideSpeakerPlayRequest(listOf(TracksideSpeakerPlayRequest.Item(ref = ref)))) as JsonObject
        assertNull(plain["radio"])
        assertNull(plain["takeover"])
        assertNull(plain["hold"])
    }

    @Test fun aBatchMatchReadsAndIsSentAsTheServerShowsIt() {
        val answer = TracksideJson.decodeFromString(TracksideCatalogMatch.serializer(), fixture("catalog-match.json"))
        assertFalse(answer.complete)
        assertEquals(12, answer.retryAfterS)
        val halo = answer.results[0].matches
        assertEquals("Halo", halo[0].track?.title)
        assertTrue(halo[0].settled)
        assertFalse("timed out is asked again", halo[1].settled)
        assertEquals(listOf(false, true), answer.results[1].matches.map { it.settled })

        val request = TracksideCatalogMatchRequest(
            items = listOf(
                TracksideCatalogMatchRequest.Item("sdjcz08vf67h7cx28snq6", "Halo", "Beyoncé", "I Am... Sasha Fierce", 261_640, "USSM10804555"),
                TracksideCatalogMatchRequest.Item("local-2", "Single Ladies (Put a Ring on It)", "Beyoncé", durationMs = 193_213),
            ),
            services = listOf("bqtyy4kcexcsg6wa0y4w5", "bnr6z9ds6zzv5xjpv0j1s"),
        )
        assertEquals(
            TracksideJson.parseToJsonElement(fixture("catalog-match-request.json")),
            TracksideJson.parseToJsonElement(TracksideJson.encodeToString(TracksideCatalogMatchRequest.serializer(), request)),
        )
    }

    @Test fun savedIsKnownOrLeftOut() {
        val album = TracksideJson.decodeFromString(TracksideCatalogPage.serializer(), fixture("catalog-album.json"))
        assertEquals(true, album.album?.saved)
        assertEquals(mapOf("Halo" to true, "Single Ladies (Put a Ring on It)" to false), album.tracks.filter { it.saved != null }.associate { it.title to it.saved })
        val search = TracksideJson.decodeFromString(TracksideCatalogSearch.serializer(), fixture("catalog-search.json"))
        assertTrue("unknown in a search, not false", search.tracks!!.items.all { it.saved == null })
    }
}
