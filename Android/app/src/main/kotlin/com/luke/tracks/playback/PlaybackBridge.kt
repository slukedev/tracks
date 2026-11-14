package com.luke.tracks.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the service knows that Media3 doesn't carry: how close the song is to being kept, the
 * sleep timer, and why the queue is playing. The service and the app's controller share the
 * process, so they share this.
 */
object PlaybackBridge {
    data class Keep(val mediaId: String? = null, val progress: Float = 0f, val kept: Boolean = false)

    val keep = MutableStateFlow(Keep())
    val sleepAtMs = MutableStateFlow<Long?>(null)
    val origin = MutableStateFlow<QueueOrigin?>(null)
}
