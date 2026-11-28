package com.luke.tracks.sources

import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** A song to play, wherever it ends up coming from. */
data class WantedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    /** The engine's song identity, as library songs have: how copies and services' songs are matched. */
    val identity: String,
    /** Copies the caller already holds that the library may not: a server's stream of a song not synced yet. */
    val known: List<PlayableSong> = emptyList(),
    /** A service's song you picked on that service's own shelf or page. */
    val picked: PickedTrack? = null,
    /** How Get would get it: a song queued to be got as it's reached. Null when nothing here can. */
    val get: (() -> PlayableSong?)? = null,
)

/** A service's song as it was picked, with the kind of service it's on. */
data class PickedTrack(val serverId: String, val track: TracksideCatalogTrack, val kind: String)

/** One service on one server that plays here, through that server's relay. */
data class Streamer(val serverId: String, val serviceId: String, val kind: String)

/** Where a song plays from. */
sealed interface Resolution {
    val song: PlayableSong?

    data class Plays(override val song: PlayableSong, val place: PlayFromPlace) : Resolution

    /** Nothing in the order can play it now. */
    data object Nowhere : Resolution {
        override val song: PlayableSong? = null
    }
}

/**
 * Where each song plays from: the first place in the Play From order that can play it now.
 * Your own copies by their source; a streaming service by a song of its that is the same song,
 * looked up on each server that plays it here; Get only for a song you have no copy of.
 *
 * The order holds for songs you have too: a service above your copy's source plays its own
 * copy of the song, and your copy follows when the service has no match or can't play now.
 * With your sources first, a song you have plays at once, without a lookup. A service's song
 * you picked starts at that service's place: only your copies ranked above it come first, and
 * the places after it follow if it can't play here.
 *
 * Lookups go off the caller's thread to the servers, each given [lookupMs] before the place is
 * passed over as not playing now.
 */
class PlayResolver(
    private val order: () -> PlayFromOrder,
    private val getOn: () -> Boolean,
    /** Every copy of a song in your library, by its identity. */
    private val copies: (identity: String) -> List<PlayableSong>,
    private val placeOf: (mediaId: String) -> PlayFromPlace,
    private val canPlay: (mediaId: String) -> Boolean,
    private val streamers: () -> List<Streamer>,
    /** The service's song that is this song, on one server's service; null when it has none. */
    private val find: suspend (Streamer, WantedSong) -> TracksideCatalogTrack?,
    /** A service's song as the player carries it. */
    private val streamed: (serverId: String, track: TracksideCatalogTrack, identity: String) -> PlayableSong,
    private val lookupMs: Long = LOOKUP_MS,
    private val parallel: Int = PARALLEL,
) {
    /** One thing to try, in order. */
    private sealed interface Step {
        val place: PlayFromPlace

        data class Copy(val song: PlayableSong, override val place: PlayFromPlace) : Step
        data class Picked(val streamer: Streamer, val track: TracksideCatalogTrack, override val place: PlayFromPlace) : Step
        data class Look(val streamer: Streamer, override val place: PlayFromPlace) : Step
        data class Get(override val place: PlayFromPlace) : Step
    }

    /** Whether a streaming service plays here now. */
    fun streams(): Boolean = streamers().isNotEmpty()

    /** Whether Get is in the order. */
    fun gets(): Boolean = getOn()

    /**
     * The song, looking it up on the services where the order comes to them ([lookups] off
     * passes them over). Only places whose song [accept] takes count: where the player can play it.
     */
    suspend fun resolve(wanted: WantedSong, lookups: Boolean = true, accept: (PlayableSong) -> Boolean = { true }): Resolution {
        for (step in steps(wanted)) {
            val song = when (step) {
                is Step.Copy -> step.song.takeIf { canPlay(it.mediaId) }
                is Step.Picked -> streamed(step.streamer.serverId, step.track, wanted.identity)
                is Step.Look -> if (lookups) look(step.streamer, wanted) else null
                is Step.Get -> wanted.get?.invoke()
            }
            if (song != null && accept(song)) return Resolution.Plays(song, step.place)
        }
        return Resolution.Nowhere
    }

    /**
     * The song without asking any server: your copies, a service's song you picked, and Get. For
     * the player's own choice between copies, and for rows drawn before anything is pressed.
     */
    fun resolveNow(wanted: WantedSong): Resolution {
        for (step in steps(wanted)) {
            val song = when (step) {
                is Step.Copy -> step.song.takeIf { canPlay(it.mediaId) }
                is Step.Picked -> streamed(step.streamer.serverId, step.track, wanted.identity)
                is Step.Look -> null
                is Step.Get -> wanted.get?.invoke()
            }
            if (song != null) return Resolution.Plays(song, step.place)
        }
        return Resolution.Nowhere
    }

    /**
     * Whether the order asks a service before any copy of yours that can play: then [resolve]
     * waits on a lookup. False when one of your copies comes first, so it plays at once.
     */
    fun looksUp(wanted: WantedSong): Boolean {
        for (step in steps(wanted)) {
            when (step) {
                is Step.Copy -> if (canPlay(step.song.mediaId)) return false
                is Step.Picked -> return false
                is Step.Look -> return true
                is Step.Get -> return false
            }
        }
        return false
    }

    /**
     * Each song in turn, a few looked up at once; [first] goes first, so it's ready soonest. Past
     * the first, a song you have that would wait on a lookup comes as your copy for now: the
     * player looks it up as it comes near ([resolve]), so the queue starts without waiting on all of it.
     */
    suspend fun resolveAll(wanted: List<WantedSong>, first: Int = 0): List<Resolution> = coroutineScope {
        val gate = Semaphore(parallel)
        val start = first.coerceIn(0, (wanted.size - 1).coerceAtLeast(0))
        val order = wanted.indices.sortedBy { if (it == start) -1 else it }
        val answers = order.associateWith { index ->
            async {
                val song = wanted[index]
                val now = if (index != start && looksUp(song)) resolveNow(song).takeIf { it is Resolution.Plays && it.place is PlayFromPlace.From } else null
                now ?: gate.withPermit { resolve(song) }
            }
        }
        wanted.indices.map { answers.getValue(it) }.awaitAll()
    }

    private suspend fun look(streamer: Streamer, wanted: WantedSong): PlayableSong? {
        val track = try {
            withTimeoutOrNull(lookupMs) { find(streamer, wanted) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return track?.takeIf { it.canPlay }?.let { streamed(streamer.serverId, it, wanted.identity) }
    }

    private fun steps(wanted: WantedSong): List<Step> {
        val owned = (wanted.known + copies(wanted.identity)).distinctBy { it.mediaId }
        val byPlace = owned.groupBy { placeOf(it.mediaId) }
        val streaming = streamers().groupBy { it.kind }
        val picked = wanted.picked
        val pickedPlace = picked?.let { PlayFromPlace.Service(it.kind) }
        val present = buildList {
            addAll(byPlace.keys)
            streaming.keys.forEach { add(PlayFromPlace.Service(it)) }
            pickedPlace?.let(::add)
            if (owned.isEmpty() && getOn() && wanted.get != null) add(PlayFromPlace.Get)
        }.distinct()
        var ranked = order().arranged(present)
        // A pick starts at its own service: other services above it don't come first.
        if (pickedPlace != null) {
            val at = ranked.indexOf(pickedPlace)
            ranked = ranked.filterIndexed { i, place -> i >= at || place !is PlayFromPlace.Service }
        }
        return ranked.flatMap { place ->
            when (place) {
                is PlayFromPlace.Service -> buildList {
                    val servers = streaming[place.kind].orEmpty()
                    if (picked != null && place == pickedPlace) {
                        servers.firstOrNull { it.serverId == picked.serverId && it.serviceId == picked.track.serviceId }
                            ?.takeIf { picked.track.canPlay }
                            ?.let { add(Step.Picked(it, picked.track, place)) }
                        servers.filter { it.serverId != picked.serverId || it.serviceId != picked.track.serviceId }.forEach { add(Step.Look(it, place)) }
                    } else {
                        servers.forEach { add(Step.Look(it, place)) }
                    }
                }
                PlayFromPlace.Get -> listOf(Step.Get(place))
                else -> byPlace[place].orEmpty().map { Step.Copy(it, place) }
            }
        }
    }

    companion object {
        /** How long a service is given to say it has a song before it's passed over. */
        const val LOOKUP_MS = 2_500L
        const val PARALLEL = 4

        /** The one line when nothing in the order can play a song. */
        fun cantPlay(title: String?): String = title?.takeIf { it.isNotBlank() }?.let { "Can't play “$it” here." } ?: "Can't play this here."
    }
}
