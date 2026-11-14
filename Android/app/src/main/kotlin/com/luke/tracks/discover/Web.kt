package com.luke.tracks.discover

import com.luke.tracks.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.tracks_engine.HttpResponse
import uniffi.tracks_engine.WebRequest
import java.util.concurrent.TimeUnit

/** Sends a request the engine built. OkHttp in the app; recorded answers in tests. */
fun interface WebTransport {
    suspend fun send(request: WebRequest): HttpResponse
}

class OkHttpWebTransport(private val http: OkHttpClient = defaultHttp) : WebTransport {
    override suspend fun send(request: WebRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        var type: String? = null
        request.headers.forEach {
            if (it.name.equals("Content-Type", ignoreCase = true)) type = it.value
            builder.header(it.name, it.value)
        }
        if (request.headers.none { it.name.equals("User-Agent", ignoreCase = true) }) builder.header("User-Agent", USER_AGENT)
        val body = request.body.takeIf { request.method != "GET" }?.toRequestBody(type?.toMediaTypeOrNull())
        builder.method(request.method, body)
        http.newCall(builder.build()).execute().use { HttpResponse(it.code.toUShort(), it.body.bytes()) }
    }

    companion object {
        /** Names the app, as MusicBrainz and others ask of every client. */
        val USER_AGENT = "Tracks/${BuildConfig.VERSION_NAME} (https://github.com/slukedev/tracks)"

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
