package com.luke.tracks.library.trackside

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Speakers on a Trackside server (the wire contract's section 1). Every enum is open: the
// field stays a String, with a typed reading that turns a value this build doesn't know into
// Unknown. Capability lists keep unknown names and the app only looks for the ones it knows.

/** One speaker the server can play on, as `GET /speakers/{id}` and the speaker events send it. */
@Serializable
data class TracksideSpeaker(
    val id: String,
    val name: String = "",
    /** speaker, group, stereo_pair, phone, or one this build doesn't know. */
    val kind: String = "unknown",
    val protocol: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    /** available, unavailable, powered_off. */
    val availability: String = "unavailable",
    val capabilities: List<String> = emptyList(),
    val powered: Boolean? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val group: Group? = null,
    @SerialName("group_volume") val groupVolume: Int? = null,
    @SerialName("can_group_with") val canGroupWith: List<String>? = null,
    val playback: Playback? = null,
    @SerialName("now_playing") val nowPlaying: NowPlaying? = null,
    val context: Context? = null,
    val relay: Relay? = null,
    val revision: Long = 0,
) {
    @Serializable
    data class Group(
        @SerialName("leader_id") val leaderId: String? = null,
        @SerialName("member_ids") val memberIds: List<String> = emptyList(),
        /** leader or member. */
        val role: String = "member",
        /** A group made in the speakers' own app, whose members can't change here. */
        @SerialName("static") val isStatic: Boolean? = null,
    )

    @Serializable
    data class Playback(
        /** playing, paused, idle, buffering, unknown. */
        val state: String = "unknown",
        /** Where it was at [positionAt] on the server's clock. */
        @SerialName("position_ms") val positionMs: Long = 0,
        @SerialName("position_at") val positionAt: String? = null,
        val shuffle: Boolean? = null,
        /** off, one, all. */
        val repeat: String? = null,
    )

    @Serializable
    data class NowPlaying(
        val title: String = "",
        val artist: String? = null,
        val album: String? = null,
        @SerialName("artwork_id") val artworkId: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        @SerialName("item_id") val itemId: String? = null,
        /** queue, or other: a TV input, another app casting. */
        val origin: String? = null,
        @SerialName("song_id") val songId: String? = null,
        val ref: String? = null,
        @SerialName("service_id") val serviceId: String? = null,
        @SerialName("song_key") val songKey: String? = null,
    )

    @Serializable
    data class Context(val kind: String = "other", val id: String? = null, val name: String? = null)

    @Serializable
    data class Relay(
        @SerialName("session_id") val sessionId: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
        @SerialName("this_device") val thisDevice: Boolean? = null,
    )

    enum class Kind { Speaker, Group, StereoPair, Phone, Unknown }
    enum class Availability { Available, Unavailable, PoweredOff }
    enum class State { Playing, Paused, Idle, Buffering, Unknown }

    val kindValue: Kind get() = when (kind) {
        "speaker" -> Kind.Speaker
        "group" -> Kind.Group
        "stereo_pair" -> Kind.StereoPair
        "phone" -> Kind.Phone
        else -> Kind.Unknown
    }

    /** Anything this build doesn't know reads as unreachable, so no control is offered for it. */
    val availabilityValue: Availability get() = when (availability) {
        "available" -> Availability.Available
        "powered_off" -> Availability.PoweredOff
        else -> Availability.Unavailable
    }

    val stateValue: State get() = when (playback?.state) {
        "playing" -> State.Playing
        "paused" -> State.Paused
        "idle" -> State.Idle
        "buffering" -> State.Buffering
        else -> State.Unknown
    }

    fun can(capability: String): Boolean = capability in capabilities

    val isLeader: Boolean get() = group?.role == "leader"
    val isMember: Boolean get() = group?.role == "member"
}

/** The names in a speaker's `capabilities` that unlock a control. */
object SpeakerCapability {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val NEXT_PREVIOUS = "next_previous"
    const val SEEK = "seek"
    const val QUEUE = "queue"
    const val SHUFFLE = "shuffle"
    const val REPEAT = "repeat"
    const val VOLUME = "volume"
    const val MUTE = "mute"
    const val POWER = "power"
    const val GROUP = "group"
    const val TRANSFER = "transfer"
    const val STOP = "stop"
}

/** `GET /speakers`: the list and whether the server can reach its speakers now. */
@Serializable
data class TracksideSpeakerList(
    val speakers: List<TracksideSpeaker> = emptyList(),
    /** ok, connecting, unavailable. */
    val state: String = "ok",
    val since: String? = null,
)

/** The `202` every speaker command answers with: what the server expects the speaker to be once it's done. */
@Serializable
data class TracksideSpeakerCommand(
    val action: String = "",
    @SerialName("command_id") val commandId: String,
    @SerialName("settle_by") val settleBy: String? = null,
    val speaker: TracksideSpeaker,
)

/** `POST /speakers/{id}/playback`. */
@Serializable
data class TracksideSpeakerPlayback(
    /** play, pause, toggle, stop, next, previous, seek, jump. */
    val action: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("item_id") val itemId: String? = null,
)

/** `PATCH /speakers/{id}`: only the fields given change. */
@Serializable
data class TracksideSpeakerPatch(
    val volume: Int? = null,
    val muted: Boolean? = null,
    val powered: Boolean? = null,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
    @SerialName("group_volume") val groupVolume: Int? = null,
)

@Serializable
internal data class TracksideSpeakerMembers(@SerialName("member_ids") val memberIds: List<String>)

/** `POST /speakers/{id}/play`: what to play there, from where. */
@Serializable
data class TracksideSpeakerPlayRequest(
    val items: List<Item>,
    /** replace, next, add. */
    val mode: String = "replace",
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
    val context: TracksideSpeaker.Context? = null,
) {
    /** Exactly one of [songId] and [ref]. */
    @Serializable
    data class Item(@SerialName("song_id") val songId: String? = null, val ref: String? = null)

    companion object {
        const val MAX_ITEMS = 1_000
    }
}

/** `POST /speakers/{id}/transfer`. */
@Serializable
data class TracksideSpeakerTransfer(
    val to: String,
    @SerialName("keep_playing_here") val keepPlayingHere: Boolean = false,
)

/** `GET /speakers/{id}/queue`, starting at the current item. */
@Serializable
data class TracksideSpeakerQueue(
    val id: String,
    @SerialName("current_index") val currentIndex: Int? = null,
    val items: List<Item> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
    val revision: Long = 0,
    val total: Int? = null,
) {
    @Serializable
    data class Item(
        val index: Int = 0,
        @SerialName("item_id") val itemId: String = "",
        val title: String = "",
        val artist: String? = null,
        val album: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        @SerialName("artwork_id") val artworkId: String? = null,
        @SerialName("song_id") val songId: String? = null,
        val ref: String? = null,
        @SerialName("service_id") val serviceId: String? = null,
        @SerialName("song_key") val songKey: String? = null,
    )
}

/** The data of each speaker event on `GET /events`. */
object SpeakerEvents {
    const val ADDED = "speaker_added"
    const val UPDATED = "speaker_updated"
    const val REMOVED = "speaker_removed"
    const val PROGRESS = "speaker_progress"
    const val QUEUE = "speaker_queue"
    const val COMMAND = "speaker_command"
    const val STATE = "speakers"

    val all = setOf(ADDED, UPDATED, REMOVED, PROGRESS, QUEUE, COMMAND, STATE)

    @Serializable
    data class Removed(val id: String)

    @Serializable
    data class Progress(
        val id: String,
        @SerialName("position_ms") val positionMs: Long = 0,
        @SerialName("position_at") val positionAt: String? = null,
        val revision: Long = 0,
        val state: String = "unknown",
    )

    @Serializable
    data class Queue(val id: String, val revision: Long = 0)

    @Serializable
    data class Command(
        @SerialName("command_id") val commandId: String,
        @SerialName("speaker_id") val speakerId: String? = null,
        /** done or failed. */
        val outcome: String = "",
        val error: Error? = null,
    ) {
        @Serializable
        data class Error(val code: String = "", val message: String? = null)
    }

    @Serializable
    data class State(val state: String = "ok", val since: String? = null)
}
