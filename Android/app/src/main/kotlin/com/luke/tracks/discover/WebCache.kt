package com.luke.tracks.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.HttpResponse
import uniffi.tracks_engine.WebRequest
import java.io.File
import java.security.MessageDigest

/**
 * Keeps answers to GET requests on disk, so suggestions open without the network and the
 * services behind them are asked a few times a day at most. A fresh answer is used as is;
 * past [maxAgeMs] it asks again, and if that fails (offline, a server error) an older answer
 * of up to [staleMs] stands in.
 */
class CachedTransport(
    private val inner: WebTransport,
    private val dir: File,
    private val maxAgeMs: Long,
    private val staleMs: Long = 14 * DAY,
    private val now: () -> Long = System::currentTimeMillis,
) : WebTransport {
    override suspend fun send(request: WebRequest): HttpResponse {
        if (request.method != "GET") return inner.send(request)
        val file = File(dir, key(request.url))
        val kept = withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.let { it.lastModified() to it.readBytes() } }
        val age = kept?.let { now() - it.first }
        if (kept != null && age!! < maxAgeMs) return HttpResponse(200u, kept.second)
        val fresh = runCatching { inner.send(request) }
        val answer = fresh.getOrNull()
        if (answer != null && answer.status.toInt() in 200..299 && !isFailure(answer.body)) {
            withContext(Dispatchers.IO) { keep(file, answer.body) }
            return answer
        }
        if (kept != null && age!! < staleMs) return HttpResponse(200u, kept.second)
        return fresh.getOrThrow()
    }

    private fun keep(file: File, body: ByteArray) {
        runCatching {
            dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeBytes(body)
            temp.renameTo(file)
            file.setLastModified(now())
            val files = dir.listFiles().orEmpty()
            if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
        }
    }

    companion object {
        private const val DAY = 24 * 3600_000L
        private const val MAX_FILES = 400

        private fun key(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Deezer says it failed in a 200 (`{"error": ...}`), the rate limit included: never kept. */
        private fun isFailure(body: ByteArray): Boolean =
            body.size >= 8 && String(body, 0, minOf(body.size, 16)).trimStart().startsWith("{\"error\"")
    }
}
