package com.luke.tracks.library.trackside

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Trackside Cloud, as tracks-companion's docs/api.md section 19 writes it: snake_case
// envelopes with RFC 3339 times, kebab-case entity paths, and each item's `fields` under the
// apps' own names. Cursors are opaque and go back exactly as they came.

/** Explicit nulls, since a cleared setting is sent as `"value": null`; nothing else sent empty. */
val CloudJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = false
}

/** The cloud's path segments for what Android keeps. */
object CloudEntity {
    const val PLAYS = "plays"
    const val SETTINGS = "settings"
    const val FORGOTTEN_SONGS = "forgotten-songs"
    const val BLOCKED_ARTISTS = "blocked-artists"
    const val EXCLUDED_STATIONS = "excluded-stations"
    const val LIKES = "likes"

    /** Kept as items, each pushed and pulled by itself. */
    val items = listOf(PLAYS, FORGOTTEN_SONGS, BLOCKED_ARTISTS, EXCLUDED_STATIONS, LIKES)
}

/** A record as a pull or a conflict brings it: an upsert with fields, or a deletion. */
@Serializable
data class CloudRecord(
    val id: String,
    val version: Long? = null,
    val fields: JsonObject? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("client_modified_at") val clientModifiedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("merged_into") val mergedInto: String? = null,
) {
    val isDeletion: Boolean get() = deletedAt != null
}

@Serializable
data class CloudPage(
    val upserts: List<CloudRecord> = emptyList(),
    val deletes: List<CloudRecord> = emptyList(),
    val cursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class CloudPushItem(
    val id: String,
    @SerialName("base_version") val baseVersion: Long? = null,
    val fields: JsonObject? = null,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("merged_into") val mergedInto: String? = null,
    val undelete: Boolean? = null,
)

@Serializable
data class CloudBatch(val items: List<CloudPushItem>)

@Serializable
data class CloudPushResult(
    val id: String,
    /** "accepted", "duplicate", "conflict", "rejected", or one this build doesn't know. */
    val status: String,
    val version: Long? = null,
    val reason: String? = null,
    val current: CloudRecord? = null,
)

@Serializable
data class CloudPushResults(val results: List<CloudPushResult> = emptyList())

/** One setting: a cleared one is a null [value] with its time, which wins like any value. */
@Serializable
data class CloudSetting(
    val value: JsonElement?,
    @SerialName("modified_at") val modifiedAt: String,
    @SerialName("device_id") val deviceId: String? = null,
    val version: Long? = null,
)

@Serializable
data class CloudSettingsPage(
    val settings: Map<String, CloudSetting> = emptyMap(),
    val cursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class CloudSettingsPatch(val settings: Map<String, CloudSetting>)

@Serializable
data class CloudSettingResult(
    val key: String,
    val status: String,
    val reason: String? = null,
    val current: CloudSetting? = null,
)

@Serializable
data class CloudSettingResults(val results: List<CloudSettingResult> = emptyList())

@Serializable
data class CloudDeviceRegistration(
    val predecessors: List<String> = emptyList(),
    val name: String? = null,
    val platform: String? = null,
)

@Serializable
data class CloudUsage(
    val items: Long = 0,
    val bytes: Long = 0,
    val quota: Quota? = null,
) {
    @Serializable
    data class Quota(val items: Long? = null, val bytes: Long? = null)
}
