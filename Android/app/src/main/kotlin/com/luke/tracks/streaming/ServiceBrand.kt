package com.luke.tracks.streaming

import androidx.annotation.DrawableRes
import com.luke.tracks.R
import com.luke.tracks.library.trackside.TracksideCatalogService

/**
 * How a service is named and marked to a person: by what it is, never by the server or the
 * account it plays through. Spotify carries its own logo, as its guidelines ask; every other
 * service, Apple Music too, has no mark Tracks may ship, and gets the neutral one.
 */
object ServiceBrand {
    const val SPOTIFY = "spotify"
    const val APPLE_MUSIC = "apple_music"

    /** "Spotify", "Apple Music", or the name the server gives a service Tracks doesn't know. */
    fun name(logoKey: String?, fallback: String): String = when (logoKey) {
        SPOTIFY -> "Spotify"
        APPLE_MUSIC -> "Apple Music"
        else -> fallback.ifBlank { "Music Service" }
    }

    fun name(service: TracksideCatalogService): String = name(service.logoKey, service.name)

    /**
     * The best it plays through the server, in a word: Lossless only when the server says so.
     * Spotify always has one, as its every tier is at least High; a service that plays at its
     * standard quality says nothing.
     */
    fun quality(service: TracksideCatalogService): String? = when {
        service.quality == "lossless" -> "Lossless"
        service.quality == "high" || service.logoKey == SPOTIFY -> "High"
        else -> null
    }

    @DrawableRes
    fun logo(logoKey: String?): Int? = when (logoKey) {
        SPOTIFY -> R.drawable.ic_service_spotify
        else -> null
    }
}

/**
 * The service a song on a server's relay plays from. The relay's queue names each item's
 * service; before it has answered, a server with just one service can only mean that one.
 */
fun StreamingState.serviceOf(mediaId: String, relay: RelayStatus): TracksideCatalogService? {
    val (serverId, _) = CatalogMedia.parse(mediaId) ?: return null
    val server = server(serverId) ?: return null
    val named = relay.items.takeIf { relay.serverId == serverId }?.firstOrNull { it.mediaId == mediaId }?.serviceId
    if (named != null) return server.service(named)
    return server.services.singleOrNull()
}

/** Where a service stands for playing here, for Play From and Sources. */
enum class ServiceStanding {
    Ready,
    /** Its account needs signing in again, on the server. */
    SignedOut,
    /** The server can't reach it, or Tracks can't reach the server. */
    Unavailable,
    ;

    /** The few words under its name; none while it's ready. */
    val word: String? get() = when (this) {
        Ready -> null
        SignedOut -> "Signed Out"
        Unavailable -> "Unavailable"
    }

    /** What to do about it, in one line, for the panel a tap opens. */
    fun help(name: String): String? = when (this) {
        Ready -> null
        SignedOut -> "Sign in to $name again on your server. It plays from here once it's back."
        Unavailable -> "Your server can't reach $name right now. It plays from here once it's back."
    }

    companion object {
        fun of(server: StreamingServer, service: TracksideCatalogService): ServiceStanding = when {
            server.status == StreamingServer.Status.Unavailable || server.status == StreamingServer.Status.Unreachable -> Unavailable
            service.account.state == "signed_out" -> SignedOut
            service.isSignedIn -> Ready
            else -> Unavailable
        }
    }
}
