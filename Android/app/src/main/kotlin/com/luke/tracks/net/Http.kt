package com.luke.tracks.net

import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import java.util.concurrent.TimeUnit

/**
 * Every HTTP client in the app comes from here, so all of them hold to the same rules:
 * [CleartextGuard] on every hop, the system's trust plus your pins ([pins]), and timeouts
 * that give up on a dead network in seconds rather than minutes.
 */
object Http {
    val pins = PinStore()

    val base: OkHttpClient by lazy {
        val trust = PinningTrustManager(pins)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier(PinningHostnameVerifier(pins))
            .addInterceptor(CleartextGuard)
            .addNetworkInterceptor(CleartextGuard)
            .build()
    }

    /** For API requests: idempotent ones are tried again on a dropped connection or a busy server. */
    val api: OkHttpClient by lazy { base.newBuilder().addInterceptor(RetryInterceptor()).build() }

    /**
     * For songs and covers, which the player and image loader retry themselves, resuming a
     * song from where it broke off with a range request.
     */
    val media: OkHttpClient by lazy { base.newBuilder().addInterceptor(ServerMediaInterceptor()).build() }
}
