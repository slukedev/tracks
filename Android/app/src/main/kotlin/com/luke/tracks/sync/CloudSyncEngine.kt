package com.luke.tracks.sync

import com.luke.tracks.data.CloudDao
import com.luke.tracks.data.CloudItemEntity
import com.luke.tracks.data.HistoryRepository
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.library.trackside.CloudApi
import com.luke.tracks.library.trackside.CloudDeviceRegistration
import com.luke.tracks.library.trackside.CloudEntity
import com.luke.tracks.library.trackside.CloudJson
import com.luke.tracks.library.trackside.CloudPushItem
import com.luke.tracks.library.trackside.CloudPushResult
import com.luke.tracks.library.trackside.CloudRecord
import com.luke.tracks.library.trackside.CloudSetting
import com.luke.tracks.library.trackside.TracksideError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** Why a sync stopped, in a sentence that says what to do. */
sealed class CloudSyncError(message: String) : Exception(message) {
    data object DeviceMismatch : CloudSyncError("Your server knows this phone by another name. Pair it again to keep syncing.")
    data object QuotaExceeded : CloudSyncError("Your server is out of space for synced history. Free some up, then try again.")
    data object NotOffered : CloudSyncError("This server doesn't offer Trackside Cloud. Update it, or turn sync off.")
}

/** The settings and likes sync keeps in step with the cloud: the app's settings, or a stand-in. */
interface SyncedPreferences {
    /** The synced settings' values here, by the cloud's key. */
    suspend fun settings(): Map<String, JsonElement>

    /** Settings that changed elsewhere; keys this app doesn't know are left alone. */
    suspend fun applySettings(values: Map<String, JsonElement>)
    suspend fun likes(): Set<String>
    suspend fun setLikes(likes: Set<String>)
}

/**
 * Trackside Cloud sync, per tracks-companion's docs/api.md section 19, the way the Apple apps
 * do it: register this device, push what changed here, then pull what changed elsewhere.
 *
 * Every write is idempotent by id, so a batch sent twice or a page applied twice costs
 * nothing. Cursors are kept only after their page is in. A `410 sync_reset` (the server was
 * restored from a backup) pulls a full snapshot, keeping everything here, then pushes all of
 * it again so the server gets back what it lost. Nothing here is ever deleted because the
 * server lacks it: only the cloud's own tombstones remove plays.
 */
class CloudSyncEngine(
    private val api: CloudApi,
    private val history: HistoryRepository,
    private val preferences: SyncedPreferences,
    private val deviceId: String,
    private val predecessors: List<String> = emptyList(),
    private val deviceName: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val batchItems: Int = 1_000,
    private val batchBytes: Int = 7 * 1024 * 1024,
    private val pageSize: Int = 2_000,
) {
    data class Report(val pushed: Int = 0, val pulled: Int = 0, val removed: Int = 0, val dropped: Int = 0)

    private val dao: CloudDao = history.database.cloud()
    private val plays = history.database.plays()
    private val lock = Mutex()
    private val mine = (predecessors + deviceId).map { it.lowercase() }.toSet()

    private var pushed = 0
    private var pulled = 0
    private var removed = 0
    private var dropped = 0

    /** Pushes and pulls everything, or pulls only [entities] (after the server's `cloud` event). */
    suspend fun sync(entities: Set<String>? = null): Report = lock.withLock {
        pushed = 0; pulled = 0; removed = 0; dropped = 0
        register()
        capture()
        if (entities == null) {
            CloudEntity.items.forEach { push(it) }
            pushSettings()
        }
        (entities ?: (CloudEntity.items + CloudEntity.SETTINGS).toSet()).forEach { entity ->
            if (entity == CloudEntity.SETTINGS) pullSettings() else if (entity in CloudEntity.items) pullItems(entity)
        }
        dao.setState(LAST_SYNCED, now().toString())
        Report(pushed, pulled, removed, dropped)
    }

    // MARK: Registering

    private suspend fun register() {
        val signature = (listOf(deviceId) + predecessors.sorted()).joinToString(",")
        if (dao.state(REGISTERED) == signature) return
        val registration = CloudDeviceRegistration(predecessors, deviceName, "android")
        try {
            api.register(deviceId, registration)
        } catch (error: TracksideError.Server) {
            when {
                error.code == "device_mismatch" -> throw CloudSyncError.DeviceMismatch
                // A predecessor the server never saw: register without, rather than not at all.
                error.status == 400 && error.field == "predecessors" -> api.register(deviceId, registration.copy(predecessors = emptyList()))
                error.status == 404 -> throw CloudSyncError.NotOffered
                else -> throw error
            }
        } catch (_: TracksideError.NotFound) {
            throw CloudSyncError.NotOffered
        } catch (_: TracksideError.Forbidden) {
            throw CloudSyncError.DeviceMismatch
        }
        dao.setState(REGISTERED, signature)
    }

    // MARK: What changed here

    /**
     * Settings and likes live in the app's settings, so what changed there since the last
     * sync is found by comparing with the cloud's copy. The first time, what's here goes up
     * dated 1970: a seed, which never replaces what another device already chose.
     */
    private suspend fun capture() {
        val first = dao.state(CAPTURED) == null
        val stamp = if (first) 0L else now()
        val stored = dao.items(CloudEntity.SETTINGS).associateBy { it.id }
        val changed = preferences.settings().mapNotNull { (key, value) ->
            val known = stored[key]
            val held = known?.fields?.let { runCatching { CloudJson.parseToJsonElement(it).jsonObject["value"] }.getOrNull() ?: JsonNull }
            if (known != null && held == value) return@mapNotNull null
            CloudItemEntity(
                CloudEntity.SETTINGS, key, settingFields(value),
                modifiedAt = if (known == null) stamp else now(), version = known?.version, pending = (known?.pending ?: 0) + 1,
            )
        }
        val likes = preferences.likes()
        val items = dao.items(CloudEntity.LIKES).associateBy { it.id }
        val liked = likes.filter { items[it]?.loved() != true }.map { id ->
            CloudItemEntity(CloudEntity.LIKES, id, LOVED, modifiedAt = if (items[id] == null) stamp else now(), version = items[id]?.version, pending = (items[id]?.pending ?: 0) + 1)
        }
        val unliked = items.values.filter { it.loved() && it.id !in likes }.map {
            it.copy(fields = NOT_LOVED, deletedAt = null, modifiedAt = now(), pending = it.pending + 1)
        }
        dao.put(changed + liked + unliked)
        if (first) dao.setState(CAPTURED, "1")
    }

    // MARK: Pushing

    private suspend fun push(entity: String) {
        if (entity == CloudEntity.PLAYS) {
            pushPlays()
            pushItems(CloudEntity.PLAYS)
        } else {
            pushItems(entity)
        }
    }

    private suspend fun pushPlays() {
        var afterAt = Long.MIN_VALUE
        var afterId = ""
        while (true) {
            val page = plays.cloudPending(afterAt, afterId, batchItems)
            if (page.isEmpty()) return
            afterAt = page.last().playedAt
            afterId = page.last().id
            val byId = page.associateBy { it.id }
            val items = page.map { play ->
                CloudPushItem(
                    id = play.id,
                    fields = CloudPlays.fields(play, deviceId),
                    clientModifiedAt = time(maxOf(play.playedAt, play.scrobbledAt ?: 0)),
                    deviceId = deviceId,
                )
            }
            send(CloudEntity.PLAYS, items) { item, result ->
                val play = byId.getValue(item.id)
                when (result?.status) {
                    "accepted", "duplicate" -> plays.settle(play.id, play.cloudPending, result.version)
                    "conflict" -> resolvePlay(play, result.current)
                    "rejected" -> when (result.reason) {
                        // Deleted on another device: it goes here too.
                        "deleted" -> history.applyCloud(emptyList(), listOf(play.id)).also { removed++ }
                        else -> plays.settle(play.id, play.cloudPending, play.cloudVersion).also { dropped++ }
                    }
                }
            }
            if (page.size < batchItems) return
        }
    }

    /** The stored copy stands: take its core, keep what's filled in here, and send that if the server lacks it. */
    private suspend fun resolvePlay(play: PlayEntity, current: CloudRecord?) {
        when {
            current == null -> plays.settle(play.id, play.cloudPending, play.cloudVersion)
            current.isDeletion -> history.applyCloud(emptyList(), listOf(play.id)).also { removed++ }
            else -> {
                val merged = CloudPlays.entity(current, play.copy(cloudFields = null), ::isMine) ?: return
                val lacking = CloudPlays.fields(merged, deviceId) != current.fields
                history.applyCloud(listOf(merged.copy(cloudPending = if (lacking) play.cloudPending + 1 else 0)), emptyList())
            }
        }
    }

    private suspend fun pushItems(entity: String) {
        while (true) {
            val page = dao.pending(entity, batchItems)
            if (page.isEmpty()) return
            val byId = page.associateBy { it.id }
            val items = page.map { item ->
                CloudPushItem(
                    id = item.id,
                    fields = item.fields?.let { CloudJson.parseToJsonElement(it).jsonObject },
                    clientModifiedAt = time(item.modifiedAt),
                    deviceId = deviceId,
                    deletedAt = item.deletedAt?.let(::time),
                    mergedInto = item.mergedInto,
                )
            }
            var progress = false
            send(entity, items) { sent, result ->
                val item = byId.getValue(sent.id)
                when (result?.status) {
                    "accepted", "duplicate" -> settleItem(item, result.version)
                    "conflict" -> result.current?.takeIf { entity != CloudEntity.PLAYS }?.let { takeItem(entity, it, force = true) } ?: settleItem(item, item.version)
                    "rejected" -> settleItem(item, item.version).also { dropped++ }
                    else -> return@send
                }
                progress = true
            }
            if (!progress || page.size < batchItems) return
        }
    }

    private suspend fun settleItem(item: CloudItemEntity, version: Long?) {
        // A play's tombstone has done its job once the server has it.
        if (item.entity == CloudEntity.PLAYS) dao.dropIfUnchanged(item.entity, item.id, item.pending)
        else dao.settle(item.entity, item.id, item.pending, version)
    }

    /**
     * Sends in batches of at most [batchItems] items or [batchBytes], then hands each item's
     * result over. Results come in order, one per item; an item without one stays pending.
     */
    private suspend fun send(entity: String, items: List<CloudPushItem>, handle: suspend (CloudPushItem, CloudPushResult?) -> Unit) {
        for (batch in batches(items)) {
            val results = try {
                api.push(entity, batch)
            } catch (error: TracksideError.Server) {
                if (error.status == 413 && batch.size > 1) {
                    batch.chunked((batch.size + 1) / 2).forEach { half -> send(entity, half, handle) }
                    continue
                }
                if (error.code == "device_mismatch") throw CloudSyncError.DeviceMismatch
                if (error.code == "quota_exceeded" || error.status == 507) throw CloudSyncError.QuotaExceeded
                throw error
            }
            val byId = results.associateBy { it.id }
            batch.forEachIndexed { index, item ->
                val result = results.getOrNull(index)?.takeIf { it.id == item.id } ?: byId[item.id]
                when (result?.reason) {
                    "device_mismatch" -> throw CloudSyncError.DeviceMismatch
                    "quota_exceeded" -> throw CloudSyncError.QuotaExceeded
                }
                if (result?.status == "accepted" || result?.status == "duplicate") pushed++
                handle(item, result)
            }
        }
    }

    internal fun batches(items: List<CloudPushItem>): List<List<CloudPushItem>> {
        val out = mutableListOf<List<CloudPushItem>>()
        var current = mutableListOf<CloudPushItem>()
        var bytes = ENVELOPE_BYTES
        items.forEach { item ->
            val size = CloudJson.encodeToString(CloudPushItem.serializer(), item).toByteArray().size + 1
            if (current.isNotEmpty() && (current.size >= batchItems || bytes + size > batchBytes)) {
                out += current
                current = mutableListOf()
                bytes = ENVELOPE_BYTES
            }
            current += item
            bytes += size
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private suspend fun pushSettings() {
        val waiting = dao.pending(CloudEntity.SETTINGS, 1_000)
        if (waiting.isEmpty()) return
        val sending = waiting.associate { item ->
            item.id to CloudSetting(item.settingValue(), time(item.modifiedAt), deviceId)
        }
        val results = api.patchSettings(sending)
        val came = mutableMapOf<String, JsonElement>()
        val byKey = waiting.associateBy { it.id }
        results.forEach { result ->
            val item = byKey[result.key] ?: return@forEach
            val current = result.current
            if (current == null) {
                dao.settle(item.entity, item.id, item.pending, item.version)
                if (result.status == "rejected") dropped++
                return@forEach
            }
            pushed++
            val value = current.value ?: JsonNull
            if (value != item.settingValue()) {
                dao.put(listOf(item.copy(fields = settingFields(value), modifiedAt = millis(current.modifiedAt), version = current.version, pending = 0)))
                came[item.id] = value
            } else {
                dao.settle(item.entity, item.id, item.pending, current.version)
            }
        }
        if (came.isNotEmpty()) preferences.applySettings(came)
    }

    // MARK: Pulling

    private suspend fun pullItems(entity: String) {
        try {
            pullPages(entity, dao.state(cursorKey(entity)))
        } catch (_: TracksideError.SyncReset) {
            // The server started over. Everything stays here; the snapshot fills in, then all
            // of it goes up again so the server has back what it lost.
            dao.clearState(cursorKey(entity))
            pullPages(entity, null)
            if (entity == CloudEntity.PLAYS) plays.markAllCloudPending()
            markEntityPending(entity)
            push(entity)
        }
    }

    private suspend fun markEntityPending(entity: String) {
        val items = dao.items(entity)
        if (items.isNotEmpty()) dao.put(items.map { it.copy(pending = it.pending + 1) })
    }

    private suspend fun pullPages(entity: String, from: String?) {
        var cursor = from
        repeat(MAX_PAGES) {
            val page = try {
                api.pull(entity, cursor, pageSize)
            } catch (error: TracksideError.Server) {
                // A cursor the server didn't make: start over from a snapshot.
                if (error.status == 400 && error.field == "cursor" && cursor != null) throw TracksideError.SyncReset
                throw error
            }
            if (entity == CloudEntity.PLAYS) applyPlays(page.upserts, page.deletes) else applyItems(entity, page.upserts, page.deletes)
            page.cursor?.let {
                dao.setState(cursorKey(entity), it)
                cursor = it
            }
            if (!page.hasMore) return
        }
    }

    private suspend fun applyPlays(upserts: List<CloudRecord>, deletes: List<CloudRecord>) {
        val tombstoned = dao.items(CloudEntity.PLAYS).filter { it.pending > 0 }.map { it.id }.toSet()
        val wanted = upserts.filter { it.id !in tombstoned }
        val existing = wanted.map { it.id }.chunked(500).flatMap { plays.byIds(it) }.associateBy { it.id }
        val merged = wanted.mapNotNull { CloudPlays.entity(it, existing[it.id], ::isMine) }
        val kept = wanted.map(CloudRecord::id).toSet()
        val gone = deletes.map { it.id }.filter { it !in kept }
        history.applyCloud(merged, gone)
        pulled += merged.size
        removed += gone.size
    }

    private suspend fun applyItems(entity: String, upserts: List<CloudRecord>, deletes: List<CloudRecord>) {
        (upserts + deletes).forEach { takeItem(entity, it, force = false) }
        if (entity == CloudEntity.LIKES && (upserts + deletes).isNotEmpty()) {
            val items = dao.items(CloudEntity.LIKES)
            val likes = preferences.likes()
            val next = (likes - items.filterNot { it.loved() }.map { it.id }.toSet()) + items.filter { it.loved() }.map { it.id }
            if (next != likes) preferences.setLikes(next)
        }
    }

    /** Newest wins, as the server decides it; a change here that's newer stays to go up. */
    private suspend fun takeItem(entity: String, record: CloudRecord, force: Boolean) {
        val local = dao.item(entity, record.id)
        val modified = record.clientModifiedAt?.let(::millis) ?: record.deletedAt?.let(::millis) ?: 0
        if (!force && local != null && local.modifiedAt > modified) return
        dao.put(
            listOf(
                CloudItemEntity(
                    entity, record.id,
                    fields = if (record.isDeletion) null else record.fields?.toString(),
                    deletedAt = record.deletedAt?.let(::millis),
                    mergedInto = record.mergedInto,
                    modifiedAt = modified,
                    version = record.version,
                    pending = 0,
                ),
            ),
        )
        pulled++
    }

    private suspend fun pullSettings() {
        var cursor = dao.state(cursorKey(CloudEntity.SETTINGS))
        val came = mutableMapOf<String, JsonElement>()
        repeat(MAX_PAGES) {
            val page = try {
                api.settings(cursor)
            } catch (_: TracksideError.SyncReset) {
                markEntityPending(CloudEntity.SETTINGS)
                cursor = null
                api.settings(null)
            }
            page.settings.forEach { (key, setting) ->
                val local = dao.item(CloudEntity.SETTINGS, key)
                val modified = millis(setting.modifiedAt)
                if (local != null && local.modifiedAt > modified) return@forEach
                val value = setting.value ?: JsonNull
                dao.put(listOf(CloudItemEntity(CloudEntity.SETTINGS, key, settingFields(value), modifiedAt = modified, version = setting.version, pending = 0)))
                if (local?.settingValue() != value) came[key] = value
                pulled++
            }
            page.cursor?.let {
                dao.setState(cursorKey(CloudEntity.SETTINGS), it)
                cursor = it
            }
            if (!page.hasMore) {
                if (came.isNotEmpty()) preferences.applySettings(came)
                if (dao.pending(CloudEntity.SETTINGS, 1).isNotEmpty()) pushSettings()
                return
            }
        }
    }

    // MARK: Helpers

    private fun isMine(device: String) = device.lowercase() in mine

    private fun CloudItemEntity.loved(): Boolean =
        deletedAt == null && fields?.let { runCatching { CloudJson.parseToJsonElement(it).jsonObject["loved"] as? JsonPrimitive }.getOrNull()?.booleanOrNull } == true

    private fun CloudItemEntity.settingValue(): JsonElement =
        fields?.let { runCatching { CloudJson.parseToJsonElement(it).jsonObject["value"] }.getOrNull() } ?: JsonNull

    companion object {
        const val LAST_SYNCED = "cloud.lastSynced"
        private const val REGISTERED = "cloud.registered"
        private const val CAPTURED = "cloud.captured"
        private const val MAX_PAGES = 100_000
        private const val ENVELOPE_BYTES = 16
        private val LOVED = """{"loved":true}"""
        private val NOT_LOVED = """{"loved":false}"""

        fun cursorKey(entity: String) = "cloud.cursor.$entity"

        /** Everything sync keeps about a server, so choosing another one starts over. */
        const val STATE_PREFIX = "cloud."

        fun settingFields(value: JsonElement): String = JsonObject(mapOf("value" to value)).toString()

        fun time(ms: Long): String = Instant.ofEpochMilli(ms).toString()

        fun millis(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0)
    }
}
