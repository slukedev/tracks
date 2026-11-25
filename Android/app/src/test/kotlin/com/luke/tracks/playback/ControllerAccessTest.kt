package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ControllerAccessTest {
    private val own = "com.luke.tracks"
    private val ownUid = 10_123

    private fun controller(name: String, uid: Int = 10_500, systemTrusted: Boolean = false, systemRole: Boolean = false, systemPackage: Boolean = false) =
        ControllerAccess.Controller(name, uid, systemTrusted, systemRole, systemPackage)

    @Test fun anyOtherAppGetsTransportControlsOnly() {
        val foreign = controller("com.example.snoop")
        assertFalse(ControllerAccess.trusts(foreign, own, ownUid))
        // Its connection can't browse, search, queue, or read the queue.
        val allowed = ControllerAccess.PLAYBACK_ONLY
        assertTrue(allowed.contains(Player.COMMAND_PLAY_PAUSE))
        listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_TIMELINE).forEach {
            assertFalse("command $it", allowed.contains(it))
        }
    }

    @Test fun anAppNamedLikeAndroidAutoIsntTrustedUnlessItsTheSystemsOwn() {
        assertFalse(ControllerAccess.trusts(controller("com.google.android.projection.gearhead"), own, ownUid))
        assertTrue(ControllerAccess.trusts(controller("com.google.android.projection.gearhead", systemPackage = true), own, ownUid))
        // Tracks' own package name from another uid is someone else.
        assertFalse(ControllerAccess.trusts(controller(own, uid = 10_999), own, ownUid))
    }

    @Test fun tracksTheSystemAndAppsWithMediaControlAreTrusted() {
        assertTrue(ControllerAccess.trusts(controller(own, uid = ownUid), own, ownUid))
        assertTrue(ControllerAccess.trusts(controller("com.android.systemui", systemTrusted = true), own, ownUid))
        assertTrue(ControllerAccess.trusts(controller(own, uid = ownUid, systemRole = true), own, ownUid))
    }

    private val library = MediaItem.Builder()
        .setMediaId("local:7")
        .setUri("content://media/external/audio/media/7")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Saltwater").setArtist("Low").build())
        .build()

    private suspend fun resolve(item: MediaItem, own: Boolean) = ControllerAccess.resolve(
        item, own,
        restore = { it.restored() },
        search = { null },
        lookup = { id -> library.takeIf { id == it.mediaId } },
    )

    @Test fun aForeignControllersAddressAndDetailsNeverReachThePlayer() = runBlocking {
        val injected = MediaItem.Builder()
            .setMediaId("made-up")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse("file:///data/data/com.luke.tracks/databases/history.db")).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Buy now").setArtist("Spam").build())
            .build()
        assertNull("an unknown id with its own address plays nothing", resolve(injected, own = false))

        // A known id with made-up details plays the library's song, as the library has it.
        val spoofed = injected.buildUpon().setMediaId("local:7").build()
        val played = resolve(spoofed, own = false)!!
        assertEquals("Saltwater", played.mediaMetadata.title)
        assertEquals("content://media/external/audio/media/7", played.localConfiguration?.uri.toString())
    }

    @Test fun tracksOwnQueueComesBackAsItWas() = runBlocking {
        val queued = MediaItem.Builder()
            .setMediaId("radio:1")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse("https://example.com/stream")).build())
            .build()
        assertEquals(Uri.parse("https://example.com/stream"), resolve(queued, own = true)?.localConfiguration?.uri)
    }
}
