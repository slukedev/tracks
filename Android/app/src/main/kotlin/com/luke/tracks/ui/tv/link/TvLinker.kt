package com.luke.tracks.ui.tv.link

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import okhttp3.HttpUrl
import java.time.Instant

/**
 * A Trackside server a TV can link to: found on the network or typed in. [deviceLink] is what
 * its capabilities say about linking, null until they've been read.
 */
data class LinkServer(val id: String?, val name: String, val url: HttpUrl, val deviceLink: Boolean? = null)

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
    /** Asking too often: wait at least [intervalSeconds] between asks. */
    data class SlowDown(val intervalSeconds: Int) : LinkPoll
    data object Expired : LinkPoll
    /** Someone turned it down on their phone. */
    data object Denied : LinkPoll
    /** Approved, and kept as a pairing: the server's name. */
    data class Linked(val serverName: String) : LinkPoll
}

/** A TV asking to link, as the phone that approves it sees it. [nearby] is false when it isn't on the server's network. */
data class LinkRequest(val deviceName: String, val platform: String, val nearby: Boolean)

/** Why linking can't go on, each with the one sentence the TV or phone says. */
enum class LinkProblem(val message: String) {
    NeedsUpdate("Update Trackside to link this TV."),
    Offline("Can't reach the server. Check the connection."),
    NotTrackside("Couldn't find Trackside at that address."),
    Denied("Linking was turned down."),
    Insecure("Use your server's https address."),
    Failed("Linking didn't work. Try again."),

    // The phone's side.
    NotFound("No TV is showing that code. Check it and try again."),
    CodeExpired("That code ran out. The TV shows a new one."),
    Locked("Too many wrong tries. Get a new code on the TV."),
    Used("That code was already used."),
    RateLimited("Too many tries. Wait a minute and try again."),
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

    /** The phone's half, on the paired server [serverId]: which TV is asking with [userCode]. */
    suspend fun lookUp(serverId: String, userCode: String): LinkRequest

    suspend fun approve(serverId: String, userCode: String): LinkRequest

    suspend fun deny(serverId: String, userCode: String): LinkRequest
}

/**
 * A code as Trackside shows one ("Q7RM-4X2K"), from however it was typed: case, dashes and
 * spaces don't matter, and O, I and L read as 0, 1 and 1 (Crockford base32). Null until it's
 * eight characters of that alphabet.
 */
fun normalizedUserCode(text: String): String? {
    val plain = text.uppercase().filterNot { it == '-' || it.isWhitespace() }.map {
        when (it) {
            'O' -> '0'
            'I', 'L' -> '1'
            else -> it
        }
    }.joinToString("")
    if (plain.length != 8 || !plain.all { it in CrockfordAlphabet }) return null
    return plain.take(4) + "-" + plain.drop(4)
}

private const val CrockfordAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** Null uses [TracksideTvLinker] on the app's servers; tests and screenshots provide a fake. */
val LocalTvLinker: ProvidableCompositionLocal<TvLinker?> = staticCompositionLocalOf { null }

/** The linker screens use. */
@Composable
fun rememberTvLinker(): TvLinker {
    val context = LocalContext.current
    return LocalTvLinker.current ?: remember(context) {
        val app = context.applicationContext
        TracksideTvLinker { (app.graph.library as? CombinedLibrary)?.servers }
    }
}
