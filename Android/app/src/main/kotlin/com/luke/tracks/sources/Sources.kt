package com.luke.tracks.sources

import uniffi.tracks_engine.PlaySource

/**
 * Where a song can come from, as the iPhone's `SourceID` names it: files on this device or a
 * server by its id. Anything else, Apple Music from an iPhone's synced Play From order or a
 * source an earlier version had, is kept as it was stored.
 */
sealed interface SourceId {
    /** The stored form, the same on every platform: "device", "server:<id>". */
    val raw: String

    data object Device : SourceId {
        override val raw = "device"
    }

    data class Server(val id: String) : SourceId {
        override val raw get() = SERVER_PREFIX + id
    }

    data class Unknown(val stored: String) : SourceId {
        override val raw get() = stored
    }

    /** How a play from this source is kept in the history: the engine's [PlaySource]. */
    val playSource: PlaySource
        get() = when (this) {
            Device, is Server -> PlaySource.YourMusic
            is Unknown -> PlaySource.Unknown(stored)
        }

    companion object {
        private const val SERVER_PREFIX = "server:"

        fun parse(raw: String): SourceId = when {
            raw == Device.raw -> Device
            raw.startsWith(SERVER_PREFIX) && raw.length > SERVER_PREFIX.length -> Server(raw.removePrefix(SERVER_PREFIX))
            else -> Unknown(raw)
        }

        /**
         * The source of one of the library's own media ids: the phone's files ("local:", and
         * the sample library's "sample:") or a server's ("subsonic:<server>:<song>"). Null for
         * ids another source owns; ask the registry for those.
         */
        fun ofMediaId(mediaId: String): SourceId? = when {
            mediaId.startsWith("local:") || mediaId.startsWith("sample:") -> Device
            mediaId.startsWith("subsonic:") -> mediaId.removePrefix("subsonic:").substringBefore(':', "").takeIf { it.isNotEmpty() }?.let(::Server)
            else -> null
        }
    }
}

/** What kind of place a source is, for its glyph and its words. */
enum class SourceKind { Device, Server }

/**
 * Where a source stands, worst first, as one word for its row. Only [needsAction] ones are
 * tinted, since only they ask for something.
 */
sealed interface SourceStatus {
    /** Signed out, or the server no longer knows this phone. */
    data object SignIn : SourceStatus

    data object CheckCertificate : SourceStatus

    data class Offline(val reason: String? = null) : SourceStatus

    /** Getting its songs; [fraction] when the source can tell how far along. */
    data class Syncing(val fraction: Float? = null) : SourceStatus

    /** Reached through an address away from home. */
    data object Away : SourceStatus

    data object Connected : SourceStatus

    /** The phone's music files, with access allowed. */
    data class Songs(val count: Int) : SourceStatus

    /** Access to the phone's music is off. */
    data object Off : SourceStatus

    val needsAction: Boolean get() = this is SignIn || this is CheckCertificate || this is Offline

    /** Whether songs from it can play now. */
    val canPlay: Boolean get() = this is Connected || this is Away || this is Songs || this is Syncing
}

/** A source as the Sources page lists it. */
data class Source(
    val id: SourceId,
    val name: String,
    val kind: SourceKind,
    val status: SourceStatus,
    /** How many of its songs are in the library, where it's known. */
    val songCount: Int? = null,
    /** The address it was added with, for a server. */
    val address: String? = null,
)

/**
 * What Play, Library and Tracks Radio draw from: every source, or one. A filter only, never
 * what's queued.
 */
sealed interface SourceSelection {
    val raw: String

    data object All : SourceSelection {
        override val raw = "all"
    }

    data class Only(val source: SourceId) : SourceSelection {
        override val raw get() = source.raw
    }

    fun includes(source: SourceId?): Boolean = this is All || (this is Only && this.source == source)

    /** One source only while it's still there among two or more; otherwise all. */
    fun effective(among: List<SourceId>): SourceSelection = when (this) {
        All -> All
        is Only -> if (source in among && among.size > 1) this else All
    }

    companion object {
        fun parse(raw: String?): SourceSelection =
            if (raw.isNullOrEmpty() || raw == All.raw) All else Only(SourceId.parse(raw))
    }
}

/** A place a copy of a song can play from, in the order Play From ranks them. */
sealed interface PlayFromPlace {
    val raw: String

    /** A server's song kept on this device. Android has no downloads yet; kept for synced orders. */
    data object Downloads : PlayFromPlace {
        override val raw = "downloads"
    }

    data class From(val source: SourceId) : PlayFromPlace {
        override val raw get() = source.raw
    }

    companion object {
        fun parse(raw: String): PlayFromPlace = if (raw == Downloads.raw) Downloads else From(SourceId.parse(raw))
    }
}

/**
 * Which copy plays when a song is in several sources: the first that can play, in this
 * order. Entirely the person's to arrange: nothing is pinned, and a place it doesn't name yet
 * goes last. The same stored form as the iPhone's `PlayFromOrder`, so an order can travel
 * between them: places this phone doesn't have are kept and skipped.
 */
data class PlayFromOrder(val places: List<PlayFromPlace>) {
    val raw: String get() = places.joinToString("\n") { it.raw }

    /** This order with the places it doesn't name yet added at the end, in the order given. */
    fun including(new: List<PlayFromPlace>): PlayFromOrder = PlayFromOrder((places + new).distinct())

    /** The order of these places, the ones it doesn't name yet last. */
    fun arranged(present: List<PlayFromPlace>): List<PlayFromPlace> {
        val here = present.toSet()
        return including(present).places.filter { it in here }
    }

    /** This order with [shown] in their new order; every other place keeps its position. */
    fun reordered(shown: List<PlayFromPlace>): PlayFromOrder {
        val full = including(shown).places.toMutableList()
        val moved = shown.distinct()
        val set = moved.toSet()
        full.indices.filter { full[it] in set }.zip(moved).forEach { (slot, place) -> full[slot] = place }
        return PlayFromOrder(full)
    }

    /** Copies best first; copies from places it doesn't name come last; same place keeps its order. */
    fun <T> ranked(copies: List<T>, place: (T) -> PlayFromPlace): List<T> {
        val rank = places.withIndex().associate { (i, p) -> p to i }
        return copies.withIndex()
            .sortedWith(compareBy({ rank[place(it.value)] ?: places.size }, { it.index }))
            .map { it.value }
    }

    /**
     * The order as it played before any place could go anywhere, written out in full for
     * these servers: what it didn't name sat after what comes before it in the old default.
     */
    fun migrated(servers: List<String>): PlayFromOrder {
        val defaults = listOf<PlayFromPlace>(PlayFromPlace.From(SourceId.Device)) +
            servers.map { PlayFromPlace.From(SourceId.Server(it)) }
        val current = defaults.toSet()
        val arranged = places.filter { it in current }.distinct().toMutableList()
        defaults.forEachIndexed { offset, place ->
            if (place in arranged) return@forEachIndexed
            val before = defaults.subList(0, offset).lastOrNull { it in arranged }
            val index = before?.let { arranged.indexOf(it) + 1 } ?: 0
            arranged.add(index, place)
        }
        return PlayFromOrder((arranged + places.filter { it !in current }).distinct())
    }

    companion object {
        val Standard = PlayFromOrder(emptyList())

        fun parse(raw: String?): PlayFromOrder =
            PlayFromOrder(raw.orEmpty().split('\n').filter { it.isNotBlank() }.map(PlayFromPlace::parse).distinct())
    }
}

/**
 * Songs the player passed over because their source couldn't play them, for the one quiet
 * message: "Skipped 3 songs", and why when there's a single reason ("Home is offline").
 * [nothingLeft] once the music stopped because nothing in Up Next could play.
 */
data class SkippedSongs(val count: Int, val reason: String? = null, val nothingLeft: Boolean = false) {
    val message: String
        get() = when {
            nothingLeft -> "Nothing in Up Next can play right now."
            count == 1 -> "Skipped 1 song"
            else -> "Skipped $count songs"
        }
}
