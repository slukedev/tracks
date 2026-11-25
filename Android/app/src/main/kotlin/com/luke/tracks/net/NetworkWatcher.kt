package com.luke.tracks.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
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

    private val switches = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Calls [listener] on the system's own thread the moment the default network is gained or
     * lost, before [changes] settles, so a proof made on the old network stops counting at once.
     */
    fun onSwitch(listener: () -> Unit) {
        val first = switches.isEmpty()
        switches += listener
        if (!first) return
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = switches.forEach { it() }
                override fun onLost(network: Network) = switches.forEach { it() }
            })
        }
    }

    /** The network in use now, compared by equality; a new one each time Wi-Fi is joined. */
    fun network(): Any? = connectivity?.activeNetwork

    /**
     * Which home network this is, as a hash, or null when it can't be told (cellular, no
     * router). Android shows the Wi-Fi name only to apps with location access, which Tracks
     * doesn't ask for, so it's the router as the network describes it: gateway, DHCP and DNS
     * servers and search domain, plus the Wi-Fi name when Android does show it. Behind a VPN,
     * the Wi-Fi or Ethernet network under it.
     */
    @Suppress("DEPRECATION")
    fun identity(): String? {
        val manager = connectivity ?: return null
        fun local(network: Network) = manager.getNetworkCapabilities(network)?.takeIf {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val active = manager.activeNetwork ?: return null
        val network = active.takeIf { local(it) != null } ?: manager.allNetworks.firstOrNull { local(it) != null } ?: return null
        val caps = local(network) ?: return null
        val link = manager.getLinkProperties(network) ?: return null
        val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
        return NetworkIdentity.of(
            ssid = ssid,
            gateways = link.routes.filter { it.isDefaultRoute && it.hasGateway() }.mapNotNull { it.gateway?.hostAddress },
            dhcpServer = link.dhcpServerAddress?.hostAddress,
            domains = link.domains,
            dns = link.dnsServers.mapNotNull { it.hostAddress },
        )
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

/** A home network's identity, as [NetworkWatcher.identity] makes it. */
object NetworkIdentity {
    /** A hash of what tells the network apart, or null without a gateway to go by. */
    fun of(ssid: String?, gateways: List<String>, dhcpServer: String?, domains: String?, dns: List<String>): String? {
        if (gateways.isEmpty()) return null
        val parts = listOf(
            ssid.orEmpty(),
            gateways.sorted().joinToString(","),
            dhcpServer.orEmpty(),
            domains.orEmpty().lowercase(),
            dns.sorted().joinToString(","),
        )
        return MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
