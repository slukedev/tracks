package com.luke.tracks.sync

import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.library.trackside.CloudJson
import com.luke.tracks.library.trackside.CloudRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Plays as Trackside Cloud keeps them, under the Apple apps' field names: core fields that
 * never change once kept, and ones filled in later where they're missing.
 */
object CloudPlays {
    fun kind(value: PlayKindValue): String = when (value) {
        PlayKindValue.Radio -> "radio"
        PlayKindValue.OnDemand -> "onDemand"
        PlayKindValue.Imported -> "imported"
        PlayKindValue.LastFm -> "lastFM"
    }

    fun kind(value: String?): PlayKindValue = when (value) {
        "radio" -> PlayKindValue.Radio
        "imported" -> PlayKindValue.Imported
        "lastFM" -> PlayKindValue.LastFm
        else -> PlayKindValue.OnDemand
    }

    /** As the engine names sources: Android's streaming plays are Apple Music's in the engine. */
    fun source(value: PlaySourceValue): String = when (value) {
        PlaySourceValue.Streaming -> "appleMusic"
        PlaySourceValue.YourMusic -> "yourMusic"
        PlaySourceValue.Spotify -> "spotify"
        PlaySourceValue.Other -> "unknown"
    }

    /** The play's source as stored: an [PlaySourceValue.Other] one by the name it came with. */
    fun source(play: PlayEntity): String =
        if (play.source == PlaySourceValue.Other) storedSource(play.cloudFields) ?: source(play.source) else source(play.source)

    fun source(value: String?): PlaySourceValue = when (value) {
        "yourMusic" -> PlaySourceValue.YourMusic
        null, "", "appleMusic" -> PlaySourceValue.Streaming
        "spotify" -> PlaySourceValue.Spotify
        else -> PlaySourceValue.Other
    }

    /** The `source` field of a play's cloud fields, as another app wrote it. */
    fun storedSource(cloudFields: String?): String? = cloudFields?.let {
        runCatching { (CloudJson.parseToJsonElement(it).jsonObject["source"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }?.takeIf { it.isNotEmpty() }

    /** Only a cover anyone can load travels: files and a server's own links stay on this phone. */
    private fun sharedArtwork(uri: String?) = uri?.takeIf { it.startsWith("https://") }

    /**
     * What goes up for a play. One that came from the cloud goes back as it came, with only
     * what was filled in here added, so fields this app doesn't keep are never lost and its
     * core never conflicts.
     */
    fun fields(play: PlayEntity, deviceId: String): JsonObject {
        val came = play.cloudFields?.let { runCatching { CloudJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        val out = LinkedHashMap<String, JsonElement>(came ?: emptyMap())
        fun core(key: String, value: Any?) {
            if (came != null && key in came) return
            put(out, key, value)
        }
        fun fill(key: String, value: Any?) {
            if (out[key].isMissing()) put(out, key, value)
        }
        core("title", play.title)
        core("artistName", play.artistName)
        core("albumTitle", play.albumTitle)
        core("playedAtMs", play.playedAt)
        core("kind", kind(play.kind))
        core("source", source(play))
        core("platform", "android")
        core("recordedByDeviceID", play.recordedBy ?: deviceId)
        sharedArtwork(play.artworkUri)?.let { if (came == null) put(out, "artworkURL", it) }
        fill("albumTitle", play.albumTitle)
        fill("durationMs", play.durationMs)
        fill("playedMs", play.listenedMs)
        fill("stationName", play.stationName)
        if (out["scrobbledAtMs"].isMissing() && out["scrobbledAt"].isMissing()) put(out, "scrobbledAtMs", play.scrobbledAt)
        return JsonObject(out)
    }

    /** A play from the cloud, merged into what's here when it's here already. */
    fun entity(record: CloudRecord, existing: PlayEntity?, isMine: (String) -> Boolean): PlayEntity? {
        val fields = record.fields ?: return null
        val title = fields.text("title") ?: return null
        val artist = fields.text("artistName") ?: return null
        val playedAt = fields.number("playedAtMs") ?: return null
        val scrobbled = fields.number("scrobbledAtMs") ?: fields.number("scrobbledAt")
        val artwork = fields.text("artworkURL")
        if (existing != null) {
            return existing.copy(
                albumTitle = existing.albumTitle ?: fields.text("albumTitle"),
                artworkUri = artwork ?: existing.artworkUri,
                durationMs = existing.durationMs ?: fields.number("durationMs"),
                listenedMs = existing.listenedMs ?: fields.number("playedMs"),
                scrobbledAt = existing.scrobbledAt ?: scrobbled,
                cloudVersion = record.version ?: existing.cloudVersion,
                cloudFields = fields.toString(),
            )
        }
        val kind = kind(fields.text("kind"))
        val recordedBy = fields.text("recordedByDeviceID") ?: record.deviceId
        return PlayEntity(
            id = record.id,
            title = title,
            artistName = artist,
            albumTitle = fields.text("albumTitle"),
            artworkUri = artwork,
            playedAt = playedAt,
            durationMs = fields.number("durationMs"),
            listenedMs = fields.number("playedMs"),
            kind = kind,
            source = source(fields.text("source")),
            appPackage = null,
            appName = if (kind == PlayKindValue.LastFm) "Last.fm" else null,
            stationName = fields.text("stationName"),
            mediaId = "",
            scrobbledAt = scrobbled,
            recordedBy = recordedBy?.takeUnless(isMine),
            cloudVersion = record.version,
            cloudPending = 0,
            cloudFields = fields.toString(),
        )
    }

    private fun JsonElement?.isMissing() = this == null || this is kotlinx.serialization.json.JsonNull

    private fun put(out: MutableMap<String, JsonElement>, key: String, value: Any?) {
        when (value) {
            null -> out.remove(key)
            is String -> out[key] = JsonPrimitive(value)
            is Number -> out[key] = JsonPrimitive(value)
            is Boolean -> out[key] = JsonPrimitive(value)
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
}
