package com.luke.tracks.library.trackside

/** What sync asks of Trackside Cloud: a Trackside server in the app, a stand-in in tests. */
interface CloudApi {
    /** `PUT /cloud/devices/{id}`: binds this device key to [deviceId], before anything is pushed. */
    suspend fun register(deviceId: String, registration: CloudDeviceRegistration)
    suspend fun pull(entity: String, cursor: String?, limit: Int): CloudPage
    suspend fun push(entity: String, items: List<CloudPushItem>): List<CloudPushResult>
    suspend fun settings(cursor: String?): CloudSettingsPage
    suspend fun patchSettings(settings: Map<String, CloudSetting>): List<CloudSettingResult>
    suspend fun usage(): CloudUsage
}

/** Trackside Cloud on one of your Trackside servers, over its device key. */
class TracksideCloud(private val client: TracksideClient) : CloudApi {
    override suspend fun register(deviceId: String, registration: CloudDeviceRegistration) {
        client.cloud(
            "devices/${deviceId.lowercase()}", "PUT",
            CloudJson.encodeToString(CloudDeviceRegistration.serializer(), registration),
            kotlinx.serialization.json.JsonObject.serializer(),
        )
    }

    override suspend fun pull(entity: String, cursor: String?, limit: Int): CloudPage =
        client.cloud(entity, "GET", null, CloudPage.serializer(), listOfNotNull("limit" to "$limit", cursor?.let { "cursor" to it }).toMap())

    override suspend fun push(entity: String, items: List<CloudPushItem>): List<CloudPushResult> =
        client.cloud("$entity:batch", "POST", CloudJson.encodeToString(CloudBatch.serializer(), CloudBatch(items)), CloudPushResults.serializer()).results

    override suspend fun settings(cursor: String?): CloudSettingsPage =
        client.cloud("settings", "GET", null, CloudSettingsPage.serializer(), cursor?.let { mapOf("cursor" to it) }.orEmpty())

    override suspend fun patchSettings(settings: Map<String, CloudSetting>): List<CloudSettingResult> =
        client.cloud("settings", "PATCH", CloudJson.encodeToString(CloudSettingsPatch.serializer(), CloudSettingsPatch(settings)), CloudSettingResults.serializer()).results

    override suspend fun usage(): CloudUsage = client.cloud("usage", "GET", null, CloudUsage.serializer())
}
