package com.luke.tracks.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.random.Random

/** Plain http to an address beyond the home network: refused before anything is sent. */
class ExposedAddress(host: String) : UnknownServiceException("Plain http to $host isn't allowed. Use https.")

/**
 * The app's own cleartext rule. Android's network security config can't name private address
 * ranges, so it allows cleartext and this decides, on every hop including redirects: plain
 * http only to a nearby address ([Nearby]), https everywhere else.
 */
object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (Nearby.isExposed(url)) throw ExposedAddress(url.host)
        return chain.proceed(chain.request())
    }
}

/**
 * When an idempotent request is worth trying again: a dropped connection or a server that
 * says it's busy, a couple of times, backing off with jitter. Never a POST, never a
 * certificate problem or a refused address, never a wait longer than [maxDelayMs].
 */
class RetryPolicy(
    val maxRetries: Int = 2,
    private val baseDelayMs: Long = 400,
    val maxDelayMs: Long = 4_000,
    private val random: Random = Random.Default,
) {
    fun isIdempotent(method: String) = method.uppercase() in IDEMPOTENT

    /** The wait before retry number [attempt] (from 1), or null for no retry. */
    fun delayAfter(attempt: Int, method: String, status: Int? = null, error: IOException? = null, retryAfter: String? = null): Long? {
        if (attempt > maxRetries || !isIdempotent(method)) return null
        if (error != null && !isTransient(error)) return null
        if (error == null && status !in RETRY_STATUSES) return null
        retryAfter?.trim()?.toLongOrNull()?.let { seconds ->
            val ms = seconds * 1_000
            return if (ms in 0..maxDelayMs) ms else null
        }
        val ceiling = (baseDelayMs shl (attempt - 1)).coerceAtMost(maxDelayMs)
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }

    private fun isTransient(error: IOException): Boolean = when (error) {
        is UnknownServiceException -> false
        is SSLException -> error.certificateNeedingTrust() == null && error.cause !is java.security.cert.CertificateException
        is java.net.SocketTimeoutException -> true
        // The whole call's time is up, or it was cancelled.
        is InterruptedIOException -> false
        else -> true
    }

    private companion object {
        val IDEMPOTENT = setOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS")
        val RETRY_STATUSES = setOf(408, 425, 429, 502, 503, 504)
    }
}

class RetryInterceptor(private val policy: RetryPolicy = RetryPolicy(), private val sleep: (Long) -> Unit = Thread::sleep) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                chain.proceed(request)
            } catch (error: IOException) {
                val wait = policy.delayAfter(attempt, request.method, error = error)
                if (wait == null || chain.call().isCanceled()) throw error
                sleep(wait)
                continue
            }
            val wait = policy.delayAfter(attempt, request.method, status = response.code, retryAfter = response.header("Retry-After"))
            if (wait == null || chain.call().isCanceled()) return response
            response.close()
            sleep(wait)
        }
    }
}
