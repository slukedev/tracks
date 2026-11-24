package com.luke.tracks.library.trackside

import com.luke.tracks.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** One server-sent event. */
data class TracksideEvent(val type: String, val data: String, val id: String? = null) {
    val json: JsonObject? get() = runCatching { TracksideJson.parseToJsonElement(data).jsonObject }.getOrNull()

    /** The `cloud` event's entity: what to pull. */
    val cloudEntity: String? get() = json?.get("entity")?.jsonPrimitive?.contentOrNull
}

/** Reads `text/event-stream` a line at a time, as the HTML standard says, dispatching on each blank line. */
class SseReader {
    private var type = ""
    private val data = StringBuilder()
    private var id: String? = null
    var lastEventId: String? = null
        private set

    /** The event a line completes, if it does. */
    fun line(line: String): TracksideEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty() && type.isEmpty()) return null
            val event = TracksideEvent(type.ifEmpty { "message" }, data.toString().removeSuffix("\n"), id)
            id?.let { lastEventId = it }
            type = ""
            data.clear()
            return event
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> type = value
            "data" -> data.append(value).append('\n')
            "id" -> if (!value.contains('\u0000')) id = value
        }
        return null
    }
}

/**
 * A Trackside server's live events, while the app is open: library changes, new addresses,
 * Trackside Cloud changes and a revoked key. Reconnects after a dropped connection, waiting
 * longer each time (up to a minute, with jitter) and resetting once the server says hello;
 * every reconnect calls [onConnected], since events missed while away aren't replayed.
 */
class TracksideEvents(
    private val client: TracksideClient,
    private val http: OkHttpClient = streaming,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    sealed interface Ending {
        /** The key was refused or revoked: pairing again is the way back. */
        data object SignedOut : Ending
        /** This server has no events. */
        data object Unsupported : Ending
    }

    suspend fun run(onConnected: suspend () -> Unit, onEvent: suspend (TracksideEvent) -> Unit): Ending {
        var failures = 0
        var lastId: String? = null
        while (coroutineContext.isActive) {
            var retryAfterMs: Long? = null
            try {
                val request = client.eventsRequest(lastId)
                val call = http.newCall(request)
                val response = withContext(Dispatchers.IO) { call.execute() }
                response.use {
                    when {
                        it.code == 401 -> return Ending.SignedOut
                        it.code == 404 -> return Ending.Unsupported
                        it.code == 429 || it.code >= 500 -> retryAfterMs = it.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                        !it.isSuccessful -> retryAfterMs = 60_000
                        else -> {
                            val reader = SseReader()
                            val source = it.body.source()
                            readEvents(source, call, reader) { event ->
                                if (event.type == "hello") {
                                    failures = 0
                                    onConnected()
                                }
                                if (event.type == "session") return Ending.SignedOut
                                onEvent(event)
                            }
                            lastId = reader.lastEventId ?: lastId
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: TracksideError.Unauthenticated) {
                return Ending.SignedOut
            } catch (_: Exception) {
            }
            failures++
            pause(retryAfterMs ?: backoff(failures))
        }
        return Ending.Unsupported
    }

    private suspend inline fun readEvents(source: BufferedSource, call: okhttp3.Call, reader: SseReader, handle: (TracksideEvent) -> Unit) {
        // Closing the call is what interrupts a blocking read when the stream is cancelled.
        val cancelled = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                reader.line(line)?.let(handle)
            }
        } catch (error: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("stopped")
        } finally {
            cancelled?.dispose()
        }
    }

    companion object {
        /** 1 s, 2 s, 4 s and so on to a minute, each with up to a quarter more at random. */
        fun backoff(failures: Int, random: Random = Random.Default): Long {
            val base = (1_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(60_000)
            return base + random.nextLong(base / 4 + 1)
        }

        /** Keep-alives come every 20 s, so a read that waits 60 s means the connection is gone. */
        private val streaming: OkHttpClient by lazy { Http.base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }
    }
}
