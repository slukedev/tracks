package com.luke.tracks.discover

import android.content.SharedPreferences
import com.luke.tracks.library.SecretBox
import uniffi.tracks_engine.HttpResponse
import uniffi.tracks_engine.WebRequest
import java.io.File

/** The answers the engine's own tests read, shared so both sides test the same traffic. */
object Fixtures {
    private val dir = File("../../Engine/crates/tracks-catalog/tests/fixtures")
    fun bytes(name: String): ByteArray = File(dir, "$name.json").readBytes()
    fun ok(name: String) = HttpResponse(200u, bytes(name))
}

/** Answers by the first rule whose text the request's method and address contain, and keeps every request. */
class RecordingTransport(private vararg val rules: Pair<String, () -> HttpResponse>) : WebTransport {
    val sent = mutableListOf<WebRequest>()
    override suspend fun send(request: WebRequest): HttpResponse {
        sent += request
        val line = "${request.method} ${request.url}"
        return rules.firstOrNull { line.contains(it.first) }?.second?.invoke() ?: HttpResponse(404u, ByteArray(0))
    }
    fun sent(method: String, path: String) = sent.filter { it.method == method && it.url.contains(path) }
}

/** Seals by reversing, so tests can see that what's stored isn't the plain key. */
class ReversingBox : SecretBox {
    override fun seal(plain: String) = "sealed:" + plain.reversed()
    override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed().takeIf { sealed.startsWith("sealed:") }
}

class MemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}
