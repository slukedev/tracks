package com.luke.tracks

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/** What an intent from outside asks Tracks to do, read once and checked before anything acts on it. */
sealed interface LaunchRequest {
    /** Search with this text filled in; blank opens Search empty. */
    data class Search(val query: String) : LaunchRequest

    /** Play what this text names; blank plays Tracks Radio. */
    data class PlayFromSearch(val query: String) : LaunchRequest

    /** A tracks:// link from a shortcut, widget, notification or another app. */
    data class Link(val uri: Uri) : LaunchRequest

    /** tracks://link?server=<id>&code=<user code>: approve linking a TV to that Trackside server. */
    data class LinkApproval(val serverId: String, val userCode: String) : LaunchRequest

    /** A trackside://pair link from a Trackside dashboard. */
    data class Pairing(val link: String) : LaunchRequest

    companion object {
        /** Longest search taken from another app; anything past it is cut. */
        const val MAX_QUERY = 200

        fun of(intent: Intent?): LaunchRequest? {
            intent ?: return null
            // Reopened from Recents: the intent was handled when it first came in.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return when (intent.action) {
                Intent.ACTION_SEARCH -> Search(query(intent))
                MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> PlayFromSearch(query(intent))
                else -> {
                    val data = intent.data ?: return null
                    when (data.scheme) {
                        "tracks" -> if (data.host == "link") linkApproval(data) else Link(data)
                        "trackside" -> Pairing(data.toString())
                        else -> null
                    }
                }
            }
        }

        private val serverIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val userCodePattern = Regex("([A-Z0-9]{4})-?([A-Z0-9]{4})")

        /** Both parts or nothing: a server id (a UUID, as Trackside gives) and a code shaped as Trackside shows it. */
        private fun linkApproval(data: Uri): LinkApproval? {
            val server = runCatching { data.getQueryParameter("server") }.getOrNull() ?: return null
            val code = runCatching { data.getQueryParameter("code") }.getOrNull() ?: return null
            if (!serverIdPattern.matches(server)) return null
            val parts = userCodePattern.matchEntire(code) ?: return null
            return LinkApproval(server.lowercase(), "${parts.groupValues[1]}-${parts.groupValues[2]}")
        }

        /** The query as plain text: control characters gone, spaces trimmed, capped in length. */
        private fun query(intent: Intent): String {
            val raw = runCatching { intent.getStringExtra(SearchManager.QUERY) }.getOrNull().orEmpty()
            val text = raw.filterNot { it.isISOControl() }.trim().take(MAX_QUERY)
            return (if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text).trim()
        }
    }
}
