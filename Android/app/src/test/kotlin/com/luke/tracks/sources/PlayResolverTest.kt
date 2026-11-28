package com.luke.tracks.sources

import android.net.Uri
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a song plays from: the first place in Play From that can play it now. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayResolverTest {
    private val apple = PlayFromPlace.Service("apple_music")
    private val spotify = PlayFromPlace.Service("spotify")
    private val home = PlayFromPlace.From(SourceId.Server("home"))
    private val phone = PlayFromPlace.From(SourceId.Device)
    private val get = PlayFromPlace.Get

    private val appleOnHome = Streamer("home", "am1", "apple_music")
    private val spotifyOnHome = Streamer("home", "sp1", "spotify")

    private fun song(mediaId: String) = PlayableSong(mediaId, Uri.parse("https://example.net/$mediaId"), "Karma Police", "Radiohead", "OK Computer", null, 264_000, IDENTITY)

    private fun track(ref: String, serviceId: String = "am1", playable: Boolean? = null) =
        TracksideCatalogTrack(ref = ref, serviceId = serviceId, title = "Karma Police", artist = "Radiohead", playable = playable)

    private val gotten = song("trackside-get:home:$IDENTITY")

    private inner class Setup(
        var order: List<PlayFromPlace>,
        var getOn: Boolean = true,
        var owned: List<PlayableSong> = emptyList(),
        var offline: Set<String> = emptySet(),
        var streamers: List<Streamer> = listOf(appleOnHome, spotifyOnHome),
        var answers: Map<String, suspend () -> TracksideCatalogTrack?> = mapOf(
            "am1" to { track("am:1") },
            "sp1" to { track("sp:1", serviceId = "sp1") },
        ),
        lookupMs: Long = 1_000,
    ) {
        val asked = mutableListOf<String>()
        val resolver = PlayResolver(
            order = { PlayFromOrder(order) },
            getOn = { getOn },
            copies = { identity -> owned.filter { it.identity == identity } },
            placeOf = { mediaId -> PlayFromPlace.From(SourceId.ofMediaId(mediaId) ?: SourceId.Unknown(mediaId)) },
            canPlay = { it !in offline },
            streamers = { streamers },
            find = { streamer, _ ->
                asked += streamer.serviceId
                answers[streamer.serviceId]?.invoke()
            },
            streamed = { serverId, track, identity -> song("catalog:$serverId:${track.ref}").copy(identity = identity) },
            lookupMs = lookupMs,
        )

        fun wanted(picked: PickedTrack? = null, canGet: Boolean = true) =
            WantedSong("Karma Police", "Radiohead", "OK Computer", 264_000, IDENTITY, picked = picked, get = if (canGet) ({ gotten }) else null)
    }

    private suspend fun Setup.played(picked: PickedTrack? = null): String? = resolver.resolve(wanted(picked)).song?.mediaId

    @Test fun everyOrderOfServicesAndGetPlaysFromTheFirst() = runBlocking {
        val expected = mapOf(apple to "catalog:home:am:1", spotify to "catalog:home:sp:1", get to gotten.mediaId)
        for (order in permutations(listOf(apple, spotify, get))) {
            val setup = Setup(order)
            assertEquals("$order", expected.getValue(order.first()), setup.played())
            // Nothing after the place that plays is asked.
            assertEquals("$order", order.takeWhile { it != get }.size.coerceAtMost(1), setup.asked.size)
        }
    }

    @Test fun getOffLeavesItOutEntirely() = runBlocking {
        val setup = Setup(listOf(get, apple), getOn = false)
        assertEquals("catalog:home:am:1", setup.played())
        setup.streamers = emptyList()
        assertEquals(Resolution.Nowhere, setup.resolver.resolve(setup.wanted()))
    }

    @Test fun aServiceThatDoesntPlayHereIsPassedOver() = runBlocking {
        // No relay offered for this device: the server lists no streamers.
        val setup = Setup(listOf(apple, get), streamers = emptyList())
        assertEquals(gotten.mediaId, setup.played())
        assertEquals(emptyList<String>(), setup.asked)
        assertEquals(Resolution.Nowhere, setup.resolver.resolve(setup.wanted(canGet = false)))
    }

    @Test fun aServiceWithoutTheSongOrOneItCantPlayIsPassedOver() = runBlocking {
        val setup = Setup(listOf(apple, spotify, get), answers = mapOf("am1" to { null }, "sp1" to { track("sp:2", serviceId = "sp1", playable = false) }))
        assertEquals(gotten.mediaId, setup.played())
        assertEquals(listOf("am1", "sp1"), setup.asked)
    }

    @Test fun aSongYouHavePlaysFromYourCopiesWithoutLookingItUp() = runBlocking {
        val setup = Setup(listOf(apple, phone, home), owned = listOf(song("subsonic:home:9"), song("local:4")))
        assertEquals("local:4", setup.played())
        assertEquals(emptyList<String>(), setup.asked)
        // Its source offline: the next copy in the order.
        setup.offline = setOf("local:4")
        assertEquals("subsonic:home:9", setup.played())
    }

    @Test fun aSongYouHaveNoPlayableCopyOfComesFromTheServicesNeverFromGet() = runBlocking {
        val setup = Setup(listOf(home, get, apple), owned = listOf(song("subsonic:home:9")), offline = setOf("subsonic:home:9"))
        assertEquals("catalog:home:am:1", setup.played())
    }

    @Test fun offlineLookupsAreTheNextPlace() = runBlocking {
        val setup = Setup(listOf(apple, spotify, get), answers = mapOf("am1" to { throw java.io.IOException("offline") }, "sp1" to { throw java.io.IOException("offline") }))
        assertEquals(gotten.mediaId, setup.played())
    }

    @Test fun aLookupThatTakesTooLongIsTheNextPlace() = runBlocking {
        val setup = Setup(
            listOf(apple, spotify),
            answers = mapOf("am1" to { delay(5_000); track("am:1") }, "sp1" to { track("sp:1", serviceId = "sp1") }),
            lookupMs = 50,
        )
        val started = System.nanoTime()
        assertEquals("catalog:home:sp:1", setup.played())
        assertTrue("gave up at the budget", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test fun aPickStreamsUnlessYourCopyIsRankedAboveItsService() = runBlocking {
        val picked = PickedTrack("home", track("am:7"), "apple_music")
        val owned = listOf(song("subsonic:home:9"))
        assertEquals("catalog:home:am:7", Setup(listOf(apple, home), owned = owned).played(picked))
        assertEquals("subsonic:home:9", Setup(listOf(home, apple), owned = owned).played(picked))
    }

    @Test fun aPickStartsAtItsOwnServiceAndGoesOnDownTheOrderWhenItCantPlayHere() = runBlocking {
        val picked = PickedTrack("home", track("am:7"), "apple_music")
        val setup = Setup(listOf(spotify, apple, home), owned = listOf(song("subsonic:home:9")))
        assertEquals("catalog:home:am:7", setup.played(picked))
        assertEquals(emptyList<String>(), setup.asked)
        // The relay isn't offered: your copy below it plays, then Get, then nothing.
        setup.streamers = emptyList()
        assertEquals("subsonic:home:9", setup.played(picked))
        setup.owned = emptyList()
        setup.order = listOf(apple, get)
        assertEquals(gotten.mediaId, setup.played(picked))
        setup.getOn = false
        assertNull(setup.played(picked))
    }

    @Test fun nowAsksNoServerAndManyAreLookedUpWithTheFirstAskedFirst() = runBlocking {
        val setup = Setup(listOf(apple, get))
        assertEquals(gotten.mediaId, setup.resolver.resolveNow(setup.wanted()).song?.mediaId)
        assertEquals(emptyList<String>(), setup.asked)

        val all = setup.resolver.resolveAll(List(3) { setup.wanted() }, first = 2)
        assertEquals(List(3) { "catalog:home:am:1" }, all.map { it.song?.mediaId })
        assertEquals(3, setup.asked.size)
    }

    @Test fun theOneLineSaysWhatCantPlay() {
        assertEquals("Can't play “Karma Police” here.", PlayResolver.cantPlay("Karma Police"))
        assertEquals("Can't play this here.", PlayResolver.cantPlay(null))
        assertTrue(SkippedSongs(1, cantPlay = "Karma Police").message.startsWith("Can't play"))
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list) else list.flatMap { head -> permutations(list - head).map { listOf(head) + it } }

    private companion object {
        const val IDENTITY = "karma police|radiohead"
    }
}
