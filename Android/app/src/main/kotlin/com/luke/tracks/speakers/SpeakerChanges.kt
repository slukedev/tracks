package com.luke.tracks.speakers

import com.luke.tracks.library.trackside.SpeakerCapability
import com.luke.tracks.library.trackside.TracksideSpeaker
import java.time.Instant

/** A speaker on one of your servers. */
data class SpeakerKey(val serverId: String, val speakerId: String)

/**
 * The part of a speaker one command changes. A newer command on the same control replaces
 * the older one's expectation, so a slider dragged quickly settles on the last value.
 */
enum class SpeakerControl {
    Volume, GroupVolume, Mute, Power, Transport, Seek, Shuffle, Repeat, Group, Play;

    /** [expected]'s fields for this control, laid over [base]: how a 202's expectation shows. */
    fun merge(expected: TracksideSpeaker, base: TracksideSpeaker): TracksideSpeaker = when (this) {
        Volume -> base.copy(volume = expected.volume)
        GroupVolume -> base.copy(groupVolume = expected.groupVolume)
        Mute -> base.copy(muted = expected.muted)
        Power -> base.copy(powered = expected.powered, availability = expected.availability, capabilities = expected.capabilities, playback = expected.playback)
        Transport -> base.copy(playback = expected.playback, nowPlaying = expected.nowPlaying)
        Seek -> base.copy(playback = expected.playback)
        Shuffle -> base.copy(playback = base.playback?.copy(shuffle = expected.playback?.shuffle) ?: expected.playback)
        Repeat -> base.copy(playback = base.playback?.copy(repeat = expected.playback?.repeat) ?: expected.playback)
        Group -> base.copy(group = expected.group, groupVolume = expected.groupVolume)
        Play -> base.copy(
            playback = expected.playback,
            nowPlaying = expected.nowPlaying,
            context = expected.context,
            powered = expected.powered,
            availability = expected.availability,
            capabilities = expected.capabilities,
        )
    }

    /**
     * Whether [actual], read after the command's time ran out, shows what was expected. The
     * position always moves on, so only what a person would notice is compared.
     */
    fun settled(expected: TracksideSpeaker, actual: TracksideSpeaker): Boolean = when (this) {
        Volume -> expected.volume == actual.volume
        GroupVolume -> expected.groupVolume == actual.groupVolume
        Mute -> expected.muted == actual.muted
        Power -> expected.powered == actual.powered
        Transport, Play -> expected.stateValue == actual.stateValue && expected.nowPlaying?.itemId == actual.nowPlaying?.itemId
        Seek -> true
        Shuffle -> expected.playback?.shuffle == actual.playback?.shuffle
        Repeat -> expected.playback?.repeat == actual.playback?.repeat
        Group -> expected.group?.memberIds.orEmpty().toSet() == actual.group?.memberIds.orEmpty().toSet()
    }

    /** How long the server takes at most, when its answer doesn't say. */
    val settleMs: Long get() = if (this == Play) 15_000 else 5_000
}

/** What a command will do, shown before the server has answered. */
sealed interface SpeakerChange {
    val control: SpeakerControl

    fun applyTo(speaker: TracksideSpeaker, nowServer: Instant): TracksideSpeaker

    data class Volume(val volume: Int) : SpeakerChange {
        override val control get() = SpeakerControl.Volume
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) = speaker.copy(volume = volume)
    }

    data class GroupVolume(val volume: Int) : SpeakerChange {
        override val control get() = SpeakerControl.GroupVolume
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) = speaker.copy(groupVolume = volume)
    }

    data class Muted(val muted: Boolean) : SpeakerChange {
        override val control get() = SpeakerControl.Mute
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) = speaker.copy(muted = muted)
    }

    data class Powered(val powered: Boolean) : SpeakerChange {
        override val control get() = SpeakerControl.Power
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) = if (powered) {
            speaker.copy(powered = true, availability = "available")
        } else {
            // Turning off stops it first.
            speaker.copy(powered = false, availability = "powered_off", playback = speaker.playback?.let { stopped(it, speaker, nowServer) })
        }
    }

    /** play, pause, stop, next, previous or jump. */
    data class Transport(val action: String) : SpeakerChange {
        override val control get() = SpeakerControl.Transport
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant): TracksideSpeaker {
            val playback = speaker.playback ?: return speaker
            val at = SpeakerClock.position(speaker, nowServer)
            return when (action) {
                "play" -> speaker.copy(playback = playback.copy(state = "playing", positionMs = at, positionAt = nowServer.toString()))
                "pause" -> speaker.copy(playback = playback.copy(state = "paused", positionMs = at, positionAt = nowServer.toString()))
                "stop" -> speaker.copy(playback = stopped(playback, speaker, nowServer))
                // The next song is the server's to say; until then the old one rests at its start.
                "next", "previous", "jump" -> speaker.copy(playback = playback.copy(state = "buffering", positionMs = 0, positionAt = nowServer.toString()))
                else -> speaker
            }
        }
    }

    data class Seek(val positionMs: Long) : SpeakerChange {
        override val control get() = SpeakerControl.Seek
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant): TracksideSpeaker {
            val playback = speaker.playback ?: return speaker
            return speaker.copy(playback = playback.copy(positionMs = positionMs, positionAt = nowServer.toString()))
        }
    }

    data class Shuffle(val on: Boolean) : SpeakerChange {
        override val control get() = SpeakerControl.Shuffle
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) =
            speaker.copy(playback = (speaker.playback ?: TracksideSpeaker.Playback()).copy(shuffle = on))
    }

    data class Repeat(val repeat: String) : SpeakerChange {
        override val control get() = SpeakerControl.Repeat
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) =
            speaker.copy(playback = (speaker.playback ?: TracksideSpeaker.Playback()).copy(repeat = repeat))
    }

    /** The full list of other speakers to group with this one; empty ungroups. Members' own rows follow in [SpeakerDisplay]. */
    data class Members(val memberIds: List<String>) : SpeakerChange {
        override val control get() = SpeakerControl.Group
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) = if (memberIds.isEmpty()) {
            speaker.copy(group = null, groupVolume = null)
        } else {
            speaker.copy(
                group = TracksideSpeaker.Group(leaderId = speaker.id, memberIds = listOf(speaker.id) + memberIds, role = "leader"),
                groupVolume = speaker.groupVolume ?: speaker.volume,
            )
        }
    }

    data object LeaveGroup : SpeakerChange {
        override val control get() = SpeakerControl.Group
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant) = speaker.copy(group = null, groupVolume = null)
    }

    /** Your queue starting on the speaker: it shows the song at once, getting ready. */
    data class Play(val nowPlaying: TracksideSpeaker.NowPlaying?, val positionMs: Long, val shuffle: Boolean?, val repeat: String?) : SpeakerChange {
        override val control get() = SpeakerControl.Play
        override fun applyTo(speaker: TracksideSpeaker, nowServer: Instant): TracksideSpeaker {
            val base = speaker.playback ?: TracksideSpeaker.Playback()
            return speaker.copy(
                availability = if (speaker.availabilityValue == TracksideSpeaker.Availability.PoweredOff) "available" else speaker.availability,
                powered = speaker.powered?.let { true },
                nowPlaying = nowPlaying ?: speaker.nowPlaying,
                playback = base.copy(
                    state = "buffering",
                    positionMs = positionMs,
                    positionAt = nowServer.toString(),
                    shuffle = shuffle ?: base.shuffle,
                    repeat = repeat ?: base.repeat,
                ),
            )
        }
    }
}

private fun stopped(playback: TracksideSpeaker.Playback, speaker: TracksideSpeaker, nowServer: Instant) =
    playback.copy(state = "idle", positionMs = SpeakerClock.position(speaker, nowServer), positionAt = nowServer.toString())

/** Where a speaker is in its song, worked out from where it was and when, as the server's clock tells it. */
object SpeakerClock {
    fun position(speaker: TracksideSpeaker, nowServer: Instant): Long {
        val playback = speaker.playback ?: return 0
        val duration = speaker.nowPlaying?.durationMs
        if (playback.state != "playing") return playback.positionMs.clamp(duration)
        val at = playback.positionAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return playback.positionMs.clamp(duration)
        return (playback.positionMs + (nowServer.toEpochMilli() - at.toEpochMilli()).coerceAtLeast(0)).clamp(duration)
    }

    private fun Long.clamp(duration: Long?) = coerceAtLeast(0).let { if (duration != null && duration > 0) it.coerceAtMost(duration) else it }
}

/** The controls a speaker shows, from its capabilities: each only when it's listed. */
data class SpeakerControls(
    val playPause: Boolean,
    val nextPrevious: Boolean,
    val seek: Boolean,
    val shuffle: Boolean,
    val repeat: Boolean,
    val volume: Boolean,
    val mute: Boolean,
    val power: Boolean,
    val group: Boolean,
    val play: Boolean,
    val stop: Boolean,
    val queue: Boolean,
) {
    companion object {
        fun of(speaker: TracksideSpeaker) = SpeakerControls(
            playPause = speaker.can(SpeakerCapability.PAUSE),
            nextPrevious = speaker.can(SpeakerCapability.NEXT_PREVIOUS),
            seek = speaker.can(SpeakerCapability.SEEK),
            shuffle = speaker.can(SpeakerCapability.SHUFFLE),
            repeat = speaker.can(SpeakerCapability.REPEAT),
            volume = speaker.can(SpeakerCapability.VOLUME) && speaker.volume != null,
            mute = speaker.can(SpeakerCapability.MUTE) && speaker.muted != null,
            power = speaker.can(SpeakerCapability.POWER) && speaker.powered != null,
            group = speaker.can(SpeakerCapability.GROUP) && speaker.group?.isStatic != true,
            play = speaker.can(SpeakerCapability.PLAY),
            stop = speaker.can(SpeakerCapability.STOP),
            queue = speaker.can(SpeakerCapability.QUEUE),
        )
    }
}
