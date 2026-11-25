package com.luke.tracks.net

import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Addresses for a server's songs and covers that carry no credentials and never change:
 * `https://media.tracks-server.invalid/<server>/stream/<song>`. They're what the queue, the
 * history, Media3's items, other apps' controllers and image caches see. Only the moment a
 * request goes out does [ServerMediaInterceptor] swap one for the server's real, signed
 * address at whichever of its addresses works now. The `.invalid` domain can never resolve,
 * so one that somehow skips that step fails rather than going anywhere.
 */
object ServerLinks {
    const val HOST = "media.tracks-server.invalid"

    /**
     * A song's stream, a cover, or a stream the server named for a song it doesn't keep
     * (Trackside's external and instant plays), whose id is the server's path for it.
     */
    enum class Kind(val path: String) { Stream("stream"), Cover("cover"), Play("play") }

    data class Link(val serverId: String, val kind: Kind, val id: String, val size: Int? = null)

    fun url(link: Link): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(HOST)
        .addPathSegment(link.serverId)
        .addPathSegment(link.kind.path)
        .addPathSegment(link.id)
        .apply { link.size?.let { addQueryParameter("size", "$it") } }
        .build()

    fun stream(serverId: String, songId: String): Uri = Uri.parse(url(Link(serverId, Kind.Stream, songId)).toString())

    /** A stream at a path the server gave, such as `/api/v1/external/plays/<id>/stream`; the media token is added as it goes out. */
    fun play(serverId: String, path: String): Uri = Uri.parse(url(Link(serverId, Kind.Play, path)).toString())

    fun cover(serverId: String, coverId: String, size: Int? = 600): Uri = Uri.parse(coverUrl(serverId, coverId, size))

    fun coverUrl(serverId: String, coverId: String, size: Int? = 600): String = url(Link(serverId, Kind.Cover, coverId, size)).toString()

    fun parse(url: HttpUrl): Link? {
        if (url.host != HOST) return null
        val segments = url.pathSegments
        if (segments.size != 3) return null
        val kind = Kind.entries.firstOrNull { it.path == segments[1] } ?: return null
        return Link(segments[0], kind, segments[2], url.queryParameter("size")?.toIntOrNull())
    }

    fun parse(text: String?): Link? = text?.toHttpUrlOrNull()?.let(::parse)

    fun isLink(text: String?) = parse(text) != null

    /** Who turns links into real addresses, by server id. Kept by the server library. */
    val resolvers = ConcurrentHashMap<String, MediaResolver>()
}

/** A link's real address now, any headers it needs, and the server's own client to fetch it with. */
data class ResolvedMedia(val url: HttpUrl, val headers: Map<String, String> = emptyMap(), val client: okhttp3.OkHttpClient? = null)

fun interface MediaResolver {
    /**
     * Blocking: called on a network thread as the request goes out. [fresh] asks again from
     * scratch, after the server turned the last answer down (an expired media token).
     */
    fun resolve(link: ServerLinks.Link, fresh: Boolean): ResolvedMedia
}

class ServerMediaInterceptor(private val resolverFor: (String) -> MediaResolver? = { ServerLinks.resolvers[it] }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val link = ServerLinks.parse(request.url) ?: return chain.proceed(request)
        val resolver = resolverFor(link.serverId) ?: throw IOException("That server isn't set up in Tracks anymore.")
        fun send(fresh: Boolean): Response {
            val resolved = try {
                resolver.resolve(link, fresh)
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException(Redact.text(error.message ?: "Couldn't reach the server."), error)
            }
            val builder = request.newBuilder().url(resolved.url)
            resolved.headers.forEach { (name, value) -> builder.header(name, value) }
            val outgoing = builder.build()
            // The server's own client, the only one that holds its pinned certificates.
            return resolved.client?.newCall(outgoing)?.execute() ?: chain.proceed(outgoing)
        }
        val response = send(fresh = false)
        if (response.code != 401 && response.code != 403) return response
        response.close()
        return send(fresh = true)
    }
}
