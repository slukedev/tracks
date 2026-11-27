package com.luke.tracks.sources

import android.net.Uri
import com.luke.tracks.library.LibrarySourceKind
import com.luke.tracks.library.LibraryTrack
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which copy plays, why a song can't, and what the player says when it skips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourcesPlaybackTest {
    private fun song(mediaId: String, identity: String = "song") =
        PlayableSong(mediaId, Uri.parse("https://example.net/$mediaId"), "Song", "Artist", null, null, 180_000, identity)

    private fun track(mediaId: String, identity: String = "song") = LibraryTrack(song(mediaId, identity), LibrarySourceKind.Server, "album")

    @Test fun copiesRankInThePlayFromOrderAndSkipOnesThatCantPlay() {
        val registry = FakeSources.registry(FakeSources.device, FakeSources.home, FakeSources.studio)
        val copies = listOf("subsonic:s1:b", "subsonic:s2:a", "local:7")
        assertEquals(listOf("local:7", "subsonic:s1:b", "subsonic:s2:a"), registry.ranked(copies) { it })

        registry.setPlayFrom(listOf(SourceId.Server("s2"), SourceId.Device, SourceId.Server("s1")).map(PlayFromPlace::From))
        val ranked = registry.ranked(copies) { it }
        assertEquals(listOf("subsonic:s2:a", "local:7", "subsonic:s1:b"), ranked)
        assertFalse("Studio NAS is offline", registry.canPlay("subsonic:s2:a"))
        assertEquals("local:7", ranked.drop(1).first(registry::canPlay))
    }

    @Test fun anySourceCanGoAnywhereAndOneAddedLaterGoesLast() {
        val device = FakeProvider(SourceKind.Device, listOf(FakeSources.device))
        val servers = MutableStateFlow(listOf(FakeSources.home))
        val serverProvider = object : SourceProvider {
            override val kind = SourceKind.Server
            override val sources = servers
            override val offersAdd = MutableStateFlow(true)
            override suspend fun remove(id: SourceId) = Unit
        }
        val registry = FakeSources.registry(name = "play-from-order", providers = listOf(device, serverProvider))
        val home = PlayFromPlace.From(SourceId.Server("s1"))
        val studio = PlayFromPlace.From(SourceId.Server("s2"))
        val phone = PlayFromPlace.From(SourceId.Device)

        registry.setPlayFrom(listOf(home, phone))
        assertEquals(listOf(home, phone), registry.arrangedPlayFrom())
        registry.setPlayFrom(listOf(phone, home))
        assertEquals(listOf(phone, home), registry.arrangedPlayFrom())

        servers.value = listOf(FakeSources.home, FakeSources.studio)
        assertEquals(listOf(phone, home, studio), registry.arrangedPlayFrom())
        registry.setPlayFrom(listOf(studio, home, phone))
        assertEquals(listOf(studio, home, phone), registry.arrangedPlayFrom())

        servers.value = listOf(FakeSources.studio)
        registry.setPlayFrom(listOf(phone, studio))
        servers.value = listOf(FakeSources.home, FakeSources.studio)
        assertEquals("a source that comes back keeps its place", listOf(phone, home, studio), registry.arrangedPlayFrom())
    }

    @Test fun anOrderSavedBeforeIsWrittenOutAsItPlayed() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("play-from-legacy", android.content.Context.MODE_PRIVATE)
        // Spotify was a source of its own before; its place is kept, last, as one this phone doesn't know.
        prefs.edit().clear().putString("playFromOrder", "server:s2\nspotify\ndevice").commit()
        val registry = SourcesRegistry(
            prefs,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listOf(FakeProvider(SourceKind.Device, listOf(FakeSources.device)), FakeProvider(SourceKind.Server, listOf(FakeSources.home, FakeSources.studio))),
            loadedServers = MutableStateFlow(listOf("s1", "s2")),
        )
        assertEquals(listOf(SourceId.Server("s2"), SourceId.Device, SourceId.Server("s1")).map(PlayFromPlace::From), registry.arrangedPlayFrom())
        assertEquals("server:s2\ndevice\nserver:s1\nspotify", prefs.getString("playFromOrder", null))
    }

    @Test fun theOrderKeepsWhatItDoesntShowWhereItWas() {
        val order = PlayFromOrder.parse("server:gone\nappleMusic\nspotify\ndevice")
        // Apple Music is a place only on the iPhone and the Mac: kept where it was, and movable.
        val moved = order.reordered(listOf(SourceId.Device, SourceId.parse("appleMusic")).map(PlayFromPlace::From))
        assertEquals("server:gone\ndevice\nspotify\nappleMusic", moved.raw)
        assertEquals(order, PlayFromOrder.parse(order.raw))
        assertEquals(
            listOf("subsonic:s1:a", "local:7", "tidal:1"),
            PlayFromOrder.parse("server:s1\ndevice").ranked(listOf("tidal:1", "local:7", "subsonic:s1:a")) {
                PlayFromPlace.From(SourceId.ofMediaId(it) ?: SourceId.Unknown(it))
            },
        )
    }

    @Test fun anOfflineServerSaysSoInFewWords() {
        val registry = FakeSources.registry(FakeSources.device, FakeSources.home, FakeSources.studio)
        assertEquals("Studio NAS is offline", registry.whyUnavailable("subsonic:s2:a"))
        assertNull(registry.whyUnavailable("subsonic:s1:a"))
        assertNull(registry.whyUnavailable("local:1"))
    }

    @Test fun theSkipMessageCountsAndSaysWhenNothingIsLeft() {
        assertEquals("Skipped 1 song", SkippedSongs(1).message)
        assertEquals("Skipped 3 songs", SkippedSongs(3, "Home is offline").message)
        assertEquals("Nothing in Up Next can play right now.", SkippedSongs(2, nothingLeft = true).message)
    }

    @Test fun nothingIsReportedForNoSongsUnlessTheMusicStopped() = runTest {
        val registry = FakeSources.registry(FakeSources.device)
        val heard = mutableListOf<SkippedSongs>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { registry.skips.collect { heard += it } }
        registry.reportSkipped(SkippedSongs(0))
        registry.reportSkipped(SkippedSongs(0, nothingLeft = true))
        assertEquals(listOf(SkippedSongs(0, nothingLeft = true)), heard)
    }

    @Test fun everyCopyIsFoundTheAskedForOneFirst() {
        val device = listOf(track("local:1"), track("local:2", identity = "other"))
        var servers = listOf(track("subsonic:s1:a"), track("subsonic:s2:b"))
        val copies = SongCopies { listOf(device, servers) }
        val found = copies.of(song("subsonic:s2:b"))
        assertEquals(listOf("subsonic:s2:b", "local:1", "subsonic:s1:a"), found.map { it.mediaId })
        val again = copies.of(song("local:1"))
        assertEquals(listOf("local:1", "subsonic:s1:a", "subsonic:s2:b"), again.map { it.mediaId })
        servers = listOf(track("subsonic:s1:a"))
        assertEquals(listOf("local:1", "subsonic:s1:a"), copies.of(song("local:1")).map { it.mediaId })
        assertEquals(listOf("x"), copies.of(song("x", identity = "")).map { it.mediaId })
    }

    @Test fun aServerReachedFromAwayAndOneSyncingSayWhere() {
        val server = SubsonicServer(id = "s1", name = "Home", url = "https://home.example.net", username = "")
        val entries = MutableStateFlow(listOf(ServerEntry(server, ServerStatus.Ready(10))))
        val away = MutableStateFlow(emptySet<String>())
        val progress = MutableStateFlow(emptyMap<String, Float>())
        val provider = ServerSourceProvider(entries, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), away, progress) {}
        assertSame(SourceStatus.Connected, provider.sources.value.single().status)
        away.value = setOf("s1")
        assertSame(SourceStatus.Away, provider.sources.value.single().status)
        assertTrue(provider.sources.value.single().status.canPlay)
        entries.value = listOf(ServerEntry(server, ServerStatus.Syncing))
        progress.value = mapOf("s1" to 0.42f)
        assertEquals(SourceStatus.Syncing(0.42f), provider.sources.value.single().status)
    }
}
