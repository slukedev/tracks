package com.luke.tracks.library.trackside

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A Trackside server found on this network. Its address still has to prove itself before anything is sent. */
data class NearbyServer(val serverId: String, val name: String, val url: HttpUrl)

/**
 * Finds Trackside servers on the home network as they advertise themselves:
 * `_trackside._tcp` with TXT records `id` (the server's id), `v` (1), `tls` (1 or 0) and
 * `path` (a base path such as `/music`). A server in Docker's default bridge network can't be
 * found this way; its address is typed instead.
 */
class NearbyServers(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val resolving = Executors.newSingleThreadExecutor()

    /** The servers found so far, while collected. */
    val found: Flow<List<NearbyServer>> = callbackFlow {
        val manager = nsd ?: run { close(); return@callbackFlow }
        val servers = ConcurrentHashMap<String, NearbyServer>()
        val callbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
        fun publish() { trySend(servers.values.sortedBy { it.name.lowercase() }) }

        fun take(info: NsdServiceInfo) {
            val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(@Suppress("DEPRECATION") info.host)
            val server = server(info.serviceName, addresses, info.port, info.attributes) ?: return
            servers[info.serviceName] = server
            publish()
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                        override fun onServiceUpdated(updated: NsdServiceInfo) = take(updated)
                        override fun onServiceLost() {
                            servers.remove(info.serviceName)
                            publish()
                        }
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    callbacks.put(info.serviceName, callback)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
                    runCatching { manager.registerServiceInfoCallback(info, resolving, callback) }
                } else {
                    @Suppress("DEPRECATION")
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) = take(info)
                    })
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                servers.remove(info.serviceName)
                callbacks.remove(info.serviceName)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
                publish()
            }
        }
        publish()
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.onFailure { close() }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(discovery) }
            if (Build.VERSION.SDK_INT >= 34) callbacks.values.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_trackside._tcp"

        /** The address a found service is at, from its TXT records; null for one Tracks can't use. */
        fun server(name: String, addresses: List<InetAddress>, port: Int, txt: Map<String, ByteArray?>): NearbyServer? {
            fun text(key: String) = txt[key]?.toString(Charsets.UTF_8)
            if (text("v") != "1") return null
            val id = text("id")?.takeIf { it.isNotBlank() } ?: return null
            val address = addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: addresses.firstOrNull { !it.isLoopbackAddress } ?: return null
            if (port !in 1..65535) return null
            val path = text("path").orEmpty().trim('/')
            val url = HttpUrl.Builder()
                .scheme(if (text("tls") == "1") "https" else "http")
                .host(address.hostAddress?.substringBefore('%') ?: return null)
                .port(port)
                .apply { if (path.isNotEmpty()) addPathSegments(path) }
                .build()
            return NearbyServer(id, name, url)
        }
    }
}
