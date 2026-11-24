package com.luke.tracks.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** The kind of network in use, as Trackside's playback decisions ask for it. */
enum class NetworkKind(val wire: String) { Wifi("wifi"), Wired("wired"), Cellular("cellular"), Unknown("unknown") }

/**
 * Tells the servers when the network under them changed: Wi-Fi joined or left, a VPN such as
 * Tailscale came up, a captive portal let you through. Each change is a reason to ask every
 * server's addresses again, since the one that worked a moment ago may not now.
 */
class NetworkWatcher(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private data class State(val network: Network?, val transports: Set<Int>, val validated: Boolean)

    /**
     * One value per real change, settled for a second so a flurry of callbacks is one change.
     * The network in use when it starts isn't a change.
     */
    @OptIn(FlowPreview::class)
    val changes: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(state(network, null)) }
            override fun onLost(network: Network) { trySend(State(null, emptySet(), false)) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(state(network, capabilities)) }
        }
        connectivity?.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().debounce(1_000).drop(1).map { }

    private fun state(network: Network, capabilities: NetworkCapabilities?): State {
        val caps = capabilities ?: connectivity?.getNetworkCapabilities(network)
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
        ).filter { caps?.hasTransport(it) == true }.toSet()
        return State(network, transports, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    val kind: NetworkKind
        get() {
            val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return NetworkKind.Unknown
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Wired
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
                else -> NetworkKind.Unknown
            }
        }
}
