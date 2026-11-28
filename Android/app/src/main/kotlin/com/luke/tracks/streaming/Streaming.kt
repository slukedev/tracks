package com.luke.tracks.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.luke.tracks.AppGraph
import com.luke.tracks.library.ServerLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Your servers' streaming services and this phone's relay, for this process, once the Trackside services start. */
class Streaming private constructor(
    val repository: StreamingRepository,
    val relay: RelayEngine,
    val servers: ServerLibrary,
    /** The services' own picks, for Play. */
    val recommendations: CatalogRecommendations,
    /** The services' songs that are songs asked for elsewhere, for Play From. */
    val matches: ServiceMatches,
) {

    companion object {
        private val installed = java.util.WeakHashMap<AppGraph, Streaming>()

        fun install(graph: AppGraph, servers: ServerLibrary): Streaming = synchronized(installed) {
            installed.getOrPut(graph) {
                val repository = StreamingRepository(
                    scope = graph.scope,
                    clientFor = { id -> servers.tracksideClient(id)?.let(::TracksideCatalogClient) },
                )
                val relay = RelayEngine(
                    scope = graph.scope,
                    clientFor = { id -> servers.tracksideClient(id)?.let(::TracksideRelayClient) },
                    deviceName = { deviceName(graph.context) },
                )
                repository.watchServers(
                    servers.entries
                        .map { list -> list.filter { it.server.isTrackside }.map { it.server.id to it.server.name } }
                        .distinctUntilChanged(),
                )
                // A server removed, or one whose services went away, takes the relay with it.
                graph.scope.launch {
                    repository.state.collect { state ->
                        val serverId = relay.status.value.serverId ?: return@collect
                        if (state.server(serverId)?.plays != true && servers.entries.value.none { it.server.id == serverId }) {
                            relay.forget(serverId, "That server isn't in Tracks anymore.")
                        }
                    }
                }
                // The relay's tier follows the network: cellular when it's metered and the server can send it.
                val network = MeteredNetwork(graph.context)
                graph.scope.launch {
                    combine(network.metered, repository.state, relay.status.map { it.serverId }.distinctUntilChanged()) { metered, state, serverId ->
                        metered to (serverId?.let { state.server(it)?.relayCellular } ?: false)
                    }.distinctUntilChanged().collect { (metered, cellular) -> relay.preferTier(metered, cellular) }
                }
                val recommendations = CatalogRecommendations(
                    scope = graph.scope,
                    streaming = repository.state,
                    clientFor = { id -> servers.tracksideClient(id)?.let(::TracksideCatalogClient) },
                )
                Streaming(repository, relay, servers, recommendations, ServiceMatches(repository, java.io.File(graph.context.cacheDir, "service-matches.json"))).also { it.offerPlaces(graph) }
            }
        }

        fun of(graph: AppGraph): Streaming? = synchronized(installed) { installed[graph] }

        /** The phone's name, as you set it in Settings, for the server's music system to show. */
        fun deviceName(context: Context): String =
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}

/**
 * Whether the network in use costs by the byte: cellular, a metered hotspot, or Data Saver
 * on. Kept from the system's default-network callback, so it's current without polling.
 */
class MeteredNetwork(context: Context) {
    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(read(current()))
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _metered.value = read(capabilities)
                }

                override fun onLost(network: Network) {
                    _metered.value = read(current())
                }
            })
        }
    }

    private fun current(): NetworkCapabilities? = manager?.let { it.getNetworkCapabilities(it.activeNetwork) }

    private fun read(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val saving = manager?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return cellular || metered || saving
    }
}
