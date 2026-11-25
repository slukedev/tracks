package com.luke.tracks.sync

import android.content.Context
import android.content.SharedPreferences
import com.luke.tracks.AppGraph
import com.luke.tracks.data.CloudDao
import com.luke.tracks.library.KeystoreSecretBox
import com.luke.tracks.library.SecretBox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.WeakHashMap

/**
 * This install's id, as Trackside servers and Trackside Cloud know it: a random UUID sealed
 * with a Keystore key, so it never moves to another phone. A phone restored from a backup of
 * this one gets an id of its own and names this one as its predecessor, which it learns from
 * the history database that the backup carries.
 */
class DeviceIdentity(
    private val prefs: SharedPreferences,
    private val box: SecretBox,
    private val state: CloudDao,
    private val legacy: SharedPreferences? = null,
    private val mint: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    @Volatile private var known: String? = null

    suspend fun id(): String = known ?: lock.withLock {
        known ?: resolve().also { known = it }
    }

    /** The ids this phone took over from: older installs restored onto it. */
    suspend fun predecessors(): List<String> {
        id()
        return state.state(PREDECESSORS)?.let { runCatching { Json.decodeFromString(ids, it) }.getOrNull() }.orEmpty()
    }

    private suspend fun resolve(): String {
        val sealed = prefs.getString(SEALED, null)?.let(box::open)
        val id = (sealed ?: legacy?.getString("deviceId", null) ?: mint()).lowercase()
        if (sealed == null) prefs.edit().putString(SEALED, box.seal(id)).apply()
        val recorded = state.state(DEVICE)
        if (recorded != null && recorded != id) {
            val before = state.state(PREDECESSORS)?.let { runCatching { Json.decodeFromString(ids, it) }.getOrNull() }.orEmpty()
            state.setState(PREDECESSORS, Json.encodeToString(ids, (before + recorded).distinct().filter { it != id }.takeLast(32)))
        }
        if (recorded != id) state.setState(DEVICE, id)
        return id
    }

    companion object {
        private const val SEALED = "sealed"
        private const val DEVICE = "device.id"
        private const val PREDECESSORS = "device.predecessors"
        private val ids = ListSerializer(String.serializer())

        fun forApp(context: Context, state: CloudDao) = DeviceIdentity(
            prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE),
            box = KeystoreSecretBox("tracks.device"),
            state = state,
            legacy = context.getSharedPreferences("trackside", Context.MODE_PRIVATE),
        )
    }
}

private val identities = WeakHashMap<AppGraph, DeviceIdentity>()

/** This install's device id, for every server and the cloud. */
val AppGraph.deviceIdentity: DeviceIdentity
    get() = synchronized(identities) { identities.getOrPut(this) { DeviceIdentity.forApp(context, history.database.cloud()) } }
