package com.luke.tracks.net

import android.util.Log

/**
 * Takes credentials out of addresses before they're written anywhere: logs, error messages,
 * crash reports. A Subsonic address carries a replayable token (`t` and `s`), a password
 * (`p`) or an API key; a Trackside one a media token (`mt`); Last.fm's a session key (`sk`)
 * and signature (`api_sig`).
 */
object Redact {
    val SECRET_PARAMETERS = setOf(
        "u", "t", "s", "p", "token", "apikey", "api_key", "key", "mt", "password",
        "access_token", "sig", "signature", "api_sig", "sk",
    )

    private val urls = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]+""")

    /** The address without its credential parameters; anything else in it is kept. */
    fun url(address: String): String {
        val query = address.indexOf('?')
        if (query < 0) return address
        val fragment = address.indexOf('#', query).let { if (it < 0) address.length else it }
        val kept = address.substring(query + 1, fragment).split('&').filter { pair ->
            pair.isNotEmpty() && pair.substringBefore('=').lowercase() !in SECRET_PARAMETERS
        }
        val base = address.substring(0, query)
        val rest = address.substring(fragment)
        return if (kept.isEmpty()) base + rest else base + "?" + kept.joinToString("&") + rest
    }

    /** Every address in some text, such as an exception's message, redacted. */
    fun text(text: String?): String = text?.let { urls.replace(it) { match -> url(match.value) } }.orEmpty()

    /** What went wrong, without addresses' credentials or a stack trace that might hold one. */
    fun error(error: Throwable): String = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
        .take(4)
        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${text(it.message)}" }
}

/** Logging that can't leak a credential: messages and errors pass through [Redact]. */
object Logs {
    fun i(message: String) {
        runCatching { Log.i(TAG, Redact.text(message)) }
    }

    fun w(message: String, error: Throwable? = null) {
        runCatching { Log.w(TAG, line(message, error)) }
    }

    fun e(message: String, error: Throwable? = null) {
        runCatching { Log.e(TAG, line(message, error)) }
    }

    internal fun line(message: String, error: Throwable?): String =
        Redact.text(message) + (error?.let { ": " + Redact.error(it) } ?: "")

    private const val TAG = "Tracks"
}
