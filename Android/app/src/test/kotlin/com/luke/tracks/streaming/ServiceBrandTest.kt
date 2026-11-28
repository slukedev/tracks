package com.luke.tracks.streaming

import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.sources.PlayFromPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a service is named, marked and placed: by what it is, signed in or not. */
class ServiceBrandTest {
    private fun service(id: String, logo: String?, name: String, state: String = "ok", quality: String? = null) =
        TracksideCatalogService(id, name, logoKey = logo, capabilities = listOf("search", "library"), account = TracksideCatalogService.Account(state), quality = quality)

    private fun server(id: String, vararg services: TracksideCatalogService, status: StreamingServer.Status = StreamingServer.Status.Ok) =
        StreamingServer(id, "Home $id", TracksideClient.CatalogAccess.Play(cellular = false), status, services.toList())

    @Test fun servicesGoByWhatTheyAreNotTheAccountOrServer() {
        assertEquals("Spotify", ServiceBrand.name(service("b1", "spotify", "Spotify (luke)")))
        assertEquals("Apple Music", ServiceBrand.name(service("am1", "apple_music", "AM on Home")))
        assertEquals("Tidal", ServiceBrand.name(service("t1", "tidal", "Tidal")))
        assertEquals("Music Service", ServiceBrand.name(null, ""))
        assertEquals(com.luke.tracks.R.drawable.ic_service_spotify, ServiceBrand.logo("spotify"))
        assertNull("no mark Tracks may ship: the neutral one", ServiceBrand.logo("apple_music"))
    }

    @Test fun lossOnlyWhenTheServerSaysSo() {
        assertEquals("Lossless", ServiceBrand.quality(service("b1", "spotify", "Spotify", quality = "lossless")))
        assertEquals("High", ServiceBrand.quality(service("b1", "spotify", "Spotify", quality = "high")))
        assertEquals("High", ServiceBrand.quality(service("b1", "spotify", "Spotify", quality = null)))
        assertEquals("High", ServiceBrand.quality(service("b1", "spotify", "Spotify", quality = "standard")))
        assertEquals("Lossless", ServiceBrand.quality(service("am1", "apple_music", "Apple Music", quality = "lossless")))
        assertNull(ServiceBrand.quality(service("t1", "tidal", "Tidal", quality = "standard")))
    }

    @Test fun aSongOnTheRelayIsNamedByItsQueueItemOrTheServersOnlyService() {
        val spotify = service("b1", "spotify", "Spotify")
        val apple = service("am1", "apple_music", "Apple Music")
        val song = CatalogMedia.mediaId("s1", "mc1.x")
        val both = StreamingState(listOf(server("s1", spotify, apple)))
        val relay = RelayStatus(serverId = "s1", items = listOf(RelayItem("i1", song, "X", null, null, null, null, "am1")))
        assertEquals(apple, both.serviceOf(song, relay))
        assertNull("two services and no word from the queue: unknown", both.serviceOf(song, RelayStatus()))
        assertEquals(spotify, StreamingState(listOf(server("s1", spotify))).serviceOf(song, RelayStatus()))
        assertNull("not a service's song", both.serviceOf("subsonic:s1:9", relay))
        assertNull("a relay on another server says nothing of this one", both.serviceOf(song, relay.copy(serverId = "s2")))
    }

    @Test fun signedOutServicesStayInPlayFromWithWhereTheyStand() {
        val out = service("b1", "spotify", "Spotify", state = "signed_out")
        val apple = service("am1", "apple_music", "Apple Music")
        val places = StreamingState(listOf(server("s1", out, apple))).servicePlaces()
        assertEquals(listOf("spotify" to ServiceStanding.SignedOut, PlayFromPlace.APPLE_MUSIC to ServiceStanding.Ready), places.map { it.kind to it.standing })
        // Ready on any server is ready.
        val two = StreamingState(listOf(server("s1", out), server("s2", service("b2", "spotify", "Spotify")))).servicePlaces()
        assertEquals(listOf(ServiceStanding.Ready), two.map { it.standing })
        // A server that can't reach its services: unavailable, never signed out.
        val down = StreamingState(listOf(server("s1", out, status = StreamingServer.Status.Unavailable))).servicePlaces()
        assertEquals(listOf(ServiceStanding.Unavailable), down.map { it.standing })
        // Only signed-in services play.
        assertEquals(listOf("am1"), StreamingState(listOf(server("s1", out, apple))).streamers().map { it.serviceId })
        assertEquals("Signed Out", ServiceStanding.SignedOut.word)
        assertEquals("Sign in to Spotify again on your server. It plays from here once it's back.", ServiceStanding.SignedOut.help("Spotify"))
        assertNull(ServiceStanding.Ready.help("Spotify"))
    }
}
