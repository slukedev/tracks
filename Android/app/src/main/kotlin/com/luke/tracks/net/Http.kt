package com.luke.tracks.net

import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import java.util.concurrent.TimeUnit

/**
 * Every HTTP client in the app comes from here, so all of them hold to the same rules:
 * [CleartextGuard] on every hop and timeouts that give up on a dead network in seconds rather
 * than minutes. These trust the system alone. A server's pinned certificates live in its own
 * client ([pinned]), so they can never vouch for Apple Music, Last.fm or another server.
 */
object Http {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(CleartextGuard)
            .addNetworkInterceptor(CleartextGuard)
            .build()
    }

    /** For API requests: idempotent ones are tried again on a dropped connection or a busy server. */
    val api: OkHttpClient by lazy { base.newBuilder().addInterceptor(RetryInterceptor()).build() }

    /**
     * For songs and covers, which the player and image loader retry themselves, resuming a
     * song from where it broke off with a range request. A server's links are sent with that
     * server's own client.
     */
    val media: OkHttpClient by lazy { base.newBuilder().addInterceptor(ServerMediaInterceptor()).build() }

    /** Clients for one server, or one add-a-server flow, that trust [pins] on top of the system. */
    fun pinned(pins: PinStore = PinStore()): PinnedHttp = PinnedHttp(pins, base)
}

/** One server's clients: the system's trust plus that server's pins, and nobody else's. */
class PinnedHttp(val pins: PinStore, from: OkHttpClient) {
    val base: OkHttpClient = run {
        val trust = PinningTrustManager(pins)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        from.newBuilder()
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier(PinningHostnameVerifier(pins))
            .build()
    }

    val api: OkHttpClient by lazy { base.newBuilder().addInterceptor(RetryInterceptor()).build() }

    /** For the server's event stream: keep-alives come every 20 s, so 60 s of quiet means it's gone. */
    val events: OkHttpClient by lazy { base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }
}
