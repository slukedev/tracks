package com.luke.tracks.library

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.provider.MediaStore.Audio.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading the phone's music from MediaStore as different phones serve it: Samsung's ringtones
 * flagged as music, system sounds, recordings, missing and null columns, and a MediaStore that
 * refuses the full query. Across every Android Tracks runs on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 34, 35, 36])
class DeviceAudioTest {
    @Suppress("DEPRECATION")
    private val columns = arrayOf(
        Media._ID, Media.TITLE, Media.ARTIST, Media.ALBUM, Media.ALBUM_ID, Media.DURATION,
        Media.RELATIVE_PATH, Media.DATA, Media.IS_RINGTONE, Media.IS_NOTIFICATION,
    )

    private fun row(
        id: Long, title: String?, artist: String? = "Low Tide Club", album: String? = "Saltwater", albumId: Long? = 7,
        durationMs: Long? = 200_000, relativePath: String? = "Music/", data: String? = "/storage/emulated/0/Music/$id.mp3",
        ringtone: Int? = 0, notification: Int? = 0,
    ): Array<Any?> = arrayOf(id, title, artist, album, albumId, durationMs, relativePath, data, ringtone, notification)

    private fun cursor(vararg rows: Array<Any?>, names: Array<String> = columns): Cursor =
        MatrixCursor(names).apply { rows.forEach(::addRow) }

    @Test fun leavesOutSoundsAndKeepsMusic() {
        val tracks = DeviceAudio.read(
            cursor(
                row(1, "Undertow"),
                // Samsung ships a full song under Music/Samsung: that one is music.
                row(2, "Over the Horizon", artist = "Samsung", relativePath = "Music/Samsung/", data = "/storage/emulated/0/Music/Samsung/Over_the_Horizon.mp3", durationMs = 191_000),
                // Its ringtone cut, flagged as music too but also as a ringtone.
                row(3, "Over the Horizon", artist = "<unknown>", ringtone = 1, durationMs = 28_000),
                row(4, "Whistle", notification = 1),
                row(5, "Chime", relativePath = "Notifications/", data = "/storage/emulated/0/Notifications/Chime.ogg"),
                row(6, "Morning Flower", relativePath = null, data = "/system/media/audio/alarms/Morning_Flower.ogg"),
                row(7, "Moto", relativePath = null, data = "/product/media/audio/ringtones/Moto.ogg"),
                row(8, "Voice 001", relativePath = "Recordings/Voice Recorder/"),
                row(9, "Call with Sam", relativePath = "Call/"),
                // Short files: never kept, so not offered.
                row(10, "Blip", durationMs = 3_000),
                row(11, "Edge", durationMs = 30_000),
            ),
        )
        assertEquals(listOf(1L, 2L), tracks.map { it.song.mediaId.removePrefix("local:").toLong() })
    }

    @Test fun toleratesNullsAndUnknowns() {
        val tracks = DeviceAudio.read(
            cursor(
                row(1, "No Tags", artist = "<unknown>", album = "<unknown>", albumId = null, durationMs = null, relativePath = null, data = null, ringtone = null, notification = null),
                row(2, null),
                row(3, "   "),
                row(4, "Zero Length", durationMs = 0),
            ),
        )
        assertEquals(listOf("No Tags", "Zero Length"), tracks.map { it.song.title })
        val first = tracks.first()
        assertEquals("Unknown artist", first.song.artistName)
        assertNull(first.song.albumTitle)
        assertNull("no album, so no cover address", first.song.artworkUri)
        assertNull(first.song.durationMs)
        assertNull(tracks[1].song.durationMs)
    }

    @Test fun readsACursorWithOnlyTheEssentials() {
        val tracks = DeviceAudio.read(cursor(arrayOf(5L, "Bare"), names = arrayOf(Media._ID, Media.TITLE)))
        val song = tracks.single().song
        assertEquals("local:5", song.mediaId)
        assertEquals("Unknown artist", song.artistName)
        assertNull(song.artworkUri)
        // Without an id or title there's nothing to play.
        assertTrue(DeviceAudio.read(cursor(arrayOf("x"), names = arrayOf(Media.ARTIST))).isEmpty())
    }

    @Test fun fallsBackWhenAPhoneRefusesTheFullQuery() {
        val asked = mutableListOf<String>()
        val tracks = DeviceAudio.query { columns, selection ->
            asked += selection
            if (Media.IS_RINGTONE in columns) throw IllegalArgumentException("no such column: is_ringtone")
            cursor(row(1, "Undertow"), row(2, "Chime", relativePath = "Ringtones/"), row(3, "Blip", durationMs = 2_000))
        }
        assertEquals(listOf(DeviceAudio.selection(), DeviceAudio.basicSelection), asked)
        assertEquals(listOf("Undertow"), tracks.map { it.song.title })
    }

    @Test fun selectionExcludesEveryFlagThisAndroidHas() {
        val selection = DeviceAudio.selection()
        assertTrue(selection.startsWith("${Media.IS_MUSIC} != 0"))
        for (flag in listOf(Media.IS_RINGTONE, Media.IS_NOTIFICATION, Media.IS_ALARM, Media.IS_PODCAST, Media.IS_AUDIOBOOK)) {
            assertTrue(flag, "($flag IS NULL OR $flag = 0)" in selection)
        }
        assertEquals(Build.VERSION.SDK_INT >= 31, Media.IS_RECORDING in DeviceAudio.notMusicFlags)
    }

    @Test fun soundFolders() {
        assertTrue(DeviceAudio.isSoundFolder("Ringtones/", null))
        assertTrue(DeviceAudio.isSoundFolder("Alarms/", null))
        assertTrue(DeviceAudio.isSoundFolder(null, "/storage/1234-ABCD/Media/notifications/ding.ogg"))
        assertTrue(DeviceAudio.isSoundFolder(null, "/system/media/audio/ui/Lock.ogg"))
        assertFalse(DeviceAudio.isSoundFolder("Music/Samsung/", "/storage/emulated/0/Music/Samsung/Over_the_Horizon.mp3"))
        // A song named like a folder is still a song.
        assertFalse(DeviceAudio.isSoundFolder("Music/", "/storage/emulated/0/Music/Alarms.mp3"))
        assertFalse(DeviceAudio.isSoundFolder(null, null))
    }
}
