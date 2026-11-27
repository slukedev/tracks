package com.luke.tracks.library.trackside

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The phone playback relay (the wire contract's section 3): the server plays a streaming
// service's audio for this phone and serves it as live HLS, a new playlist per generation.

/** One relay session, as `POST /relay/sessions`, `GET`, `PATCH` and the `relay` event send it. */
@Serializable
data class TracksideRelaySession(
    val id: String,
    @SerialName("device_id") val deviceId: String? = null,
    /** The relay as a speaker: every control goes through the speaker routes with this id. */
    @SerialName("speaker_id") val speakerId: String = "",
    /** starting, ready, playing, paused, ended. */
    val state: String = "starting",
    /** idle, closed, replaced, assistant_unavailable, server_restart, revoked, failed. With ended only. */
    @SerialName("ended_reason") val endedReason: String? = null,
    val detail: String? = null,
    /** The current epoch, from 1; 0 before the first audio. */
    val generation: Long = 0,
    /** lossless or cellular. Open. */
    val tier: String = Tier.LOSSLESS,
    val stream: Stream? = null,
    @SerialName("latency_ms") val latencyMs: Long = 4_000,
    @SerialName("idle_timeout_s") val idleTimeoutS: Long = 600,
    @SerialName("fetch_timeout_s") val fetchTimeoutS: Long = 30,
    @SerialName("created_at") val createdAt: String? = null,
) {
    @Serializable
    data class Stream(
        /** The live (or next) generation's multivariant playlist: an absolute path that takes the media token. */
        val hls: String? = null,
        val format: TracksideFormat? = null,
    )

    enum class State { Starting, Ready, Playing, Paused, Ended, Unknown }

    val stateValue: State get() = when (state) {
        "starting" -> State.Starting
        "ready" -> State.Ready
        "playing" -> State.Playing
        "paused" -> State.Paused
        "ended" -> State.Ended
        else -> State.Unknown
    }

    val isEnded: Boolean get() = stateValue == State.Ended

    /** The playlist to play now, or null for an ended session. */
    val hls: String? get() = stream?.hls?.takeIf { !isEnded && it.isNotBlank() }

    object Tier {
        const val LOSSLESS = "lossless"
        const val CELLULAR = "cellular"
    }
}

/** `POST /relay/sessions`: start or reuse this phone's session, playing [play] when given. */
@Serializable
data class TracksideRelayRequest(
    val play: TracksideSpeakerPlayRequest? = null,
    val name: String? = null,
    val tier: String = TracksideRelaySession.Tier.LOSSLESS,
)

@Serializable
internal data class TracksideRelayPatch(val tier: String)

@Serializable
data class TracksideRelaySessions(val sessions: List<TracksideRelaySession> = emptyList())

object RelayEvents {
    /** The data is a [TracksideRelaySession], on every change of state, generation, tier or stream. */
    const val RELAY = "relay"
}
