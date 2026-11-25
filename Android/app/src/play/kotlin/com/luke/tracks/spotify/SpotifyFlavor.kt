package com.luke.tracks.spotify

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Store builds have no Spotify. */
object SpotifyFlavor {
    val markRes: Int? = null

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, scope: CoroutineScope): SpotifyIntegration? = null
}
