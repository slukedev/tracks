package com.luke.tracks.ui.tv.link

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import okhttp3.HttpUrl
import java.time.Instant

/** A Trackside server a TV can link to: found on the network or typed in. */
data class LinkServer(val id: String?, val name: String, val url: HttpUrl)

/**
 * A link on offer: the code to show and the QR's link, until [expiresAt]. [deviceCode] is the
 * TV's own secret for asking how it's going; it never reaches the screen or a log.
 */
class LinkOffer(
    val server: LinkServer,
    val deviceCode: String,
    val userCode: String,
    val linkUrl: String,
    val intervalSeconds: Int,
    val expiresAt: Instant,
) {
    override fun toString() = "LinkOffer(server=${server.name}, expiresAt=$expiresAt)"
}

/** What the server said when asked how a link is going. */
sealed interface LinkPoll {
    data object Pending : LinkPoll
    /** Asking too often: wait longer between asks. */
    data object SlowDown : LinkPoll
    data object Expired : LinkPoll
    /** Someone turned it down on their phone. */
    data object Denied : LinkPoll
    /** Approved, and kept as a pairing: the server's name. */
    data class Linked(val serverName: String) : LinkPoll
}

/** Why linking can't go on, each with the one sentence the TV says. */
enum class LinkProblem(val message: String) {
    NeedsUpdate("Update Trackside to link this TV."),
    Offline("Can't reach the server. Check the connection."),
    NotTrackside("Couldn't find Trackside at that address."),
    Denied("Linking was turned down."),
    Insecure("Use your server's https address."),
    Failed("Linking didn't work. Try again."),
}

class LinkException(val problem: LinkProblem, cause: Throwable? = null) : Exception(problem.message, cause)

/**
 * Linking this TV to a Trackside server the way a phone approves it (RFC 8628's device flow):
 * the TV asks for a code, shows it, and asks how it's going until someone approves it on a
 * phone already paired with that server. Failures throw [LinkException].
 */
interface TvLinker {
    suspend fun start(server: LinkServer): LinkOffer

    suspend fun poll(offer: LinkOffer): LinkPoll

    /** The phone's half: approves the code a TV shows, on the paired server [serverId]; the TV's name. */
    suspend fun approve(serverId: String, userCode: String): String
}

/** The linker screens use; tests and screenshots provide a fake. */
val LocalTvLinker: ProvidableCompositionLocal<TvLinker> = staticCompositionLocalOf { TracksideTvLinker }
