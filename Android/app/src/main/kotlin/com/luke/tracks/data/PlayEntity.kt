package com.luke.tracks.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One kept play: the source of truth for the history on Android. The engine's index mirrors
 * this table and can always be rebuilt from it, as it is from SwiftData on Apple platforms.
 */
@Entity(
    tableName = "plays",
    indices = [Index("playedAt"), Index("scrobbledAt")],
)
data class PlayEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    /** A content://, file:// or https:// address for the cover. */
    val artworkUri: String?,
    val playedAt: Long,
    /** The song's length, when known. */
    val durationMs: Long?,
    /** How long it actually played, when measured. */
    val listenedMs: Long?,
    val kind: PlayKindValue,
    val source: PlaySourceValue,
    /** The app it played in: Tracks, or null for imports. Older plays may name another app. */
    val appPackage: String?,
    val appName: String?,
    val stationName: String?,
    /** What the player plays it by: a MediaStore uri, a server's song id, or empty. */
    val mediaId: String,
    val scrobbledAt: Long? = null,
    val scrobbleError: String? = null,
    val scrobbleAttempts: Int = 0,
)

/** The raw values match the engine's and the Swift app's. */
enum class PlayKindValue { Radio, OnDemand, Imported, LastFm }

/**
 * Where a play came from. The engine knows two sources: streaming (Apple Music on iPhone,
 * Last.fm imports on Android) and your own music.
 */
enum class PlaySourceValue { Streaming, YourMusic }
