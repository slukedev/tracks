package com.luke.tracks.ui.tv.link

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.Inspection
import com.luke.tracks.library.ServerAddress
import com.luke.tracks.library.trackside.NearbyServers
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.net.Nearby
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Where a TV finds Trackside servers to link to: the home network, or an address typed in. */
interface LinkDirectory {
    /** Servers advertising themselves nearby, while collected. */
    val nearby: Flow<List<LinkServer>>

    /** The server at a typed address; throws [LinkException] when there isn't one. */
    suspend fun lookUp(address: String): LinkServer
}

/** The app's directory: mDNS through [NearbyServers], and the server's own capabilities for a typed address. */
class AppLinkDirectory(private val context: Context) : LinkDirectory {
    override val nearby: Flow<List<LinkServer>> = NearbyServers(context.applicationContext).found
        .map { found -> found.map { LinkServer(it.serverId, it.name.take(MaxName), it.url) } }
        .catch { emit(emptyList()) }

    override suspend fun lookUp(address: String): LinkServer {
        val url = ServerAddress.url(address.take(MaxAddress)) ?: throw LinkException(LinkProblem.NotTrackside)
        if (Nearby.isExposed(url)) throw LinkException(LinkProblem.Insecure)
        val servers = (context.graph.library as? CombinedLibrary)?.servers
        val capabilities = if (servers != null) {
            when (val found = servers.inspect(url)) {
                is Inspection.Trackside -> found.capabilities
                else -> throw LinkException(LinkProblem.NotTrackside)
            }
        } else {
            try {
                TracksideClient.detect(url)
            } catch (error: IOException) {
                throw LinkException(LinkProblem.Offline, error)
            } ?: throw LinkException(LinkProblem.NotTrackside)
        }
        val name = capabilities.name?.trim()?.take(MaxName)?.ifEmpty { null } ?: ServerAddress.display(url.toString())
        return LinkServer(capabilities.serverId, name, url)
    }
}

/** A directory with nothing in it, for previews and before the app is up. */
object EmptyLinkDirectory : LinkDirectory {
    override val nearby: Flow<List<LinkServer>> = flowOf(emptyList())
    override suspend fun lookUp(address: String): LinkServer = throw LinkException(LinkProblem.NotTrackside)
}

/** Null uses [AppLinkDirectory]; tests and screenshots provide their own. */
val LocalLinkDirectory: ProvidableCompositionLocal<LinkDirectory?> = staticCompositionLocalOf { null }

private const val MaxName = 80
private const val MaxAddress = 512
