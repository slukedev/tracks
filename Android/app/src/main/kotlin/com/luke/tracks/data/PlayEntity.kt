package com.luke.tracks.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One kept play: the source of truth for the history on Android. The engine's index mirrors
 * this table and can always be rebuilt from it, as it is from SwiftData on Apple platforms.
 */
@Entity(
    tableName = "plays",
    indices = [Index("playedAt"), Index("scrobbledAt"), Index("cloudPending")],
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
    /** The device that recorded it, as Trackside Cloud names devices; null for this phone. */
    val recordedBy: String? = null,
    /** The version Trackside Cloud last stored it at, or null when it never has. */
    val cloudVersion: Long? = null,
    /** Counts changes not yet in the cloud; 0 once it's in step. */
    @ColumnInfo(defaultValue = "1") val cloudPending: Int = 1,
    /** The fields as the cloud last sent them, so ones this app doesn't keep go back unchanged. */
    val cloudFields: String? = null,
)

/** The raw values match the engine's and the Swift app's. */
enum class PlayKindValue { Radio, OnDemand, Imported, LastFm }

/**
 * Where a play came from: streaming (Apple Music on iPhone, Last.fm imports on Android), your
 * own music, Spotify (sideload builds), or [Other]: a source a newer app stored, whose own name
 * stays in [PlayEntity.cloudFields] so it syncs back unchanged and counts only under All.
 */
enum class PlaySourceValue { Streaming, YourMusic, Spotify, Other }
