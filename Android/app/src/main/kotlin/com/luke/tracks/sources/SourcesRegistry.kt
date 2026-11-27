package com.luke.tracks.sources

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import com.luke.tracks.AppGraph
import com.luke.tracks.BuildConfig
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.MusicLibrary
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.spotify.SpotifyFlavor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * One kind of source, as the registry hears about it. Files on the phone and servers are
 * built in; Spotify registers one of these at startup (`graph.sources.register(...)`), which
 * the registry takes only in builds with `BuildConfig.SPOTIFY`.
 */
interface SourceProvider {
    val kind: SourceKind

    /** Its sources as they stand, in the order they were added. */
    val sources: StateFlow<List<Source>>

    /** Whether Add Source offers it: true until it's added, for a source there's one of. */
    val offersAdd: StateFlow<Boolean>

    /** One short line under its name in Add Source ("Needs Premium"), or none. */
    val addSubtitle: String? get() = null

    /** Its brand mark, never recoloured (Spotify's icon). Null draws the kind's symbol. */
    @get:DrawableRes
    val iconRes: Int? get() = null

    /** The source a song of its own plays from, by the song's media id; null when it isn't one of its. */
    fun sourceOf(mediaId: String): SourceId? = null

    /** The confirmation before it goes. History always stays. */
    fun removal(source: Source): Removal = Removal.standard(source)

    suspend fun remove(id: SourceId)

    /**
     * Its page in Settings, Sources, for a source of a kind the app doesn't draw itself
     * (Spotify). Shown from Add Source before it's added too, with [source] null.
     */
    @Composable
    fun Page(source: Source?, onDone: () -> Unit) = Unit
}

/** The words of the confirmation before a source goes. */
data class Removal(val title: String, val message: String, val confirm: String) {
    companion object {
        fun standard(source: Source): Removal {
            val songs = source.songCount?.takeIf { it > 0 }?.let { if (it == 1) "1 song leaves your library. " else "${"%,d".format(it)} songs leave your library. " }.orEmpty()
            return Removal("Remove ${source.name}?", "${songs}Your plays stay in History.", "Remove")
        }
    }
}

/**
 * Every source Tracks plays from, merged: the phone's files, each server and, where the build
 * has it, Spotify. Also what Play and Library are filtered to, the Play From order, and the
 * player's word when it skips songs it couldn't play.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesRegistry(
    private val prefs: SharedPreferences,
    scope: CoroutineScope,
    builtIn: List<SourceProvider>,
    private val spotifyAllowed: Boolean = BuildConfig.SPOTIFY,
    /** The servers once they're read, for writing out an order saved before; null until then. */
    loadedServers: Flow<List<String>?>? = null,
    /** The kinds this device offers at all; on TV only servers (Trackside). Others are never registered. */
    private val allowedKinds: Set<SourceKind> = SourceKind.entries.toSet(),
) {
    private val providers = MutableStateFlow(builtIn.filter { allows(it.kind) })

    private fun allows(kind: SourceKind) = kind in allowedKinds && (kind != SourceKind.Spotify || spotifyAllowed)

    /** Every provider, the built-in ones first. */
    val registered: StateFlow<List<SourceProvider>> = providers.asStateFlow()

    /** Adds a kind of source. A second provider of the same kind replaces the first. */
    fun register(provider: SourceProvider) {
        if (!allows(provider.kind)) return
        providers.update { list -> list.filter { it.kind != provider.kind || it.kind == SourceKind.Server } + provider }
    }

    fun provider(kind: SourceKind): SourceProvider? = providers.value.lastOrNull { it.kind == kind }

    val sources: StateFlow<List<Source>> = providers
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.sources }) { all -> all.flatMap { it } }
        }
        .map { all -> all.sortedBy { it.kind.ordinal } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val chosen = MutableStateFlow(SourceSelection.parse(prefs.getString(SELECTION_KEY, null)))

    /** The filter as chosen, even when it can't apply right now. */
    val chosenSelection: StateFlow<SourceSelection> = chosen.asStateFlow()

    /** The filter that applies: one source only while it's here among two or more. */
    val selection: StateFlow<SourceSelection> = combine(chosen, sources) { choice, list -> choice.effective(list.map { it.id }) }
        .stateIn(scope, SharingStarted.Eagerly, SourceSelection.All)

    fun select(selection: SourceSelection) {
        chosen.value = selection
        prefs.edit().putString(SELECTION_KEY, selection.raw).apply()
    }

    private val order = MutableStateFlow(PlayFromOrder.parse(prefs.getString(ORDER_KEY, null)))

    /** The Play From order as stored; [arrangedPlayFrom] for the places here. */
    val playFrom: StateFlow<PlayFromOrder> = order.asStateFlow()

    init {
        scope.launch {
            if (!prefs.getBoolean(MIGRATED_KEY, false)) {
                val servers = loadedServers?.filterNotNull()?.first() ?: sources.value.serverIds()
                save(order.value.migrated(servers))
                prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
            }
            // A source added later goes last.
            sources.collect { list -> save(order.value.including(list.places())) }
        }
    }

    fun arrangedPlayFrom(list: List<Source> = sources.value): List<PlayFromPlace> = order.value.arranged(list.places())

    /** Saves a new order of the places shown; the rest keep their positions. */
    fun setPlayFrom(places: List<PlayFromPlace>) = save(order.value.reordered(places))

    private fun save(next: PlayFromOrder) {
        if (next == order.value && prefs.contains(ORDER_KEY)) return
        order.value = next
        prefs.edit().putString(ORDER_KEY, next.raw).apply()
    }

    /** The source a song plays from, by its media id. */
    fun sourceOf(mediaId: String): SourceId? =
        SourceId.ofMediaId(mediaId) ?: providers.value.firstNotNullOfOrNull { it.sourceOf(mediaId) }

    fun source(id: SourceId?): Source? = id?.let { wanted -> sources.value.firstOrNull { it.id == wanted } }

    /** Whether a song's source can play it now. A song whose source isn't known is given the benefit of the doubt. */
    fun canPlay(mediaId: String): Boolean = source(sourceOf(mediaId))?.status?.canPlay ?: true

    /** Why a song's source can't play it now, in a few words ("Home is offline"), where there's one to give. */
    fun whyUnavailable(mediaId: String): String? {
        val source = source(sourceOf(mediaId)) ?: return null
        return when (source.status) {
            is SourceStatus.Offline -> "${source.name} is offline"
            SourceStatus.SignIn -> "${source.name} is signed out"
            else -> null
        }
    }

    /** Copies of a song, keyed by their media ids, best first in the Play From order. */
    fun <T> ranked(copies: List<T>, mediaId: (T) -> String): List<T> =
        order.value.ranked(copies) { copy ->
            PlayFromPlace.From(sourceOf(mediaId(copy)) ?: SourceId.Unknown(mediaId(copy)))
        }

    /** A kind's brand mark: its provider's, or Spotify's even before Spotify registers. */
    @DrawableRes
    fun iconRes(kind: SourceKind): Int? =
        provider(kind)?.iconRes ?: if (kind == SourceKind.Spotify) SpotifyFlavor.markRes else null

    private val skipped = MutableSharedFlow<SkippedSongs>(extraBufferCapacity = 4)

    /** Each time the player skips songs it couldn't play, for the one quiet message. */
    val skips: SharedFlow<SkippedSongs> = skipped.asSharedFlow()

    /** Called by the player when it passes over songs whose source can't play them. */
    fun reportSkipped(songs: SkippedSongs) {
        if (songs.count > 0 || songs.nothingLeft) skipped.tryEmit(songs)
    }

    private fun List<Source>.serverIds() = mapNotNull { (it.id as? SourceId.Server)?.id }

    private fun List<Source>.places() = map { PlayFromPlace.From(it.id) }

    companion object {
        private const val SELECTION_KEY = "sourceFilter"
        private const val ORDER_KEY = "playFromOrder"
        private const val MIGRATED_KEY = "playFromOrderMigrated"
        const val PREFS = "sources"

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/** The phone's music files: there once access is allowed or songs are there (the sample library). */
class DeviceSourceProvider(
    library: MusicLibrary,
    hasPermission: StateFlow<Boolean>,
    loading: StateFlow<Boolean>,
    scope: CoroutineScope,
    private val canAsk: Boolean = true,
) : SourceProvider {
    override val kind = SourceKind.Device

    override val sources: StateFlow<List<Source>> = combine(library.snapshot, hasPermission, loading) { snapshot, allowed, busy ->
        val count = snapshot.songs.count { SourceId.ofMediaId(it.mediaId) == SourceId.Device }
        if (!allowed && count == 0) {
            emptyList()
        } else {
            val status = when {
                !allowed -> SourceStatus.Off
                busy && count == 0 -> SourceStatus.Syncing()
                else -> SourceStatus.Songs(count)
            }
            listOf(Source(SourceId.Device, DEVICE_NAME, SourceKind.Device, status, songCount = count))
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val offersAdd: StateFlow<Boolean> =
        sources.map { it.isEmpty() && canAsk }.stateIn(scope, SharingStarted.Eagerly, canAsk)

    /** The phone's files go when access is turned off in the system; nothing to do here. */
    override suspend fun remove(id: SourceId) = Unit

    companion object {
        const val DEVICE_NAME = "On This Device"
    }
}

/**
 * Your Trackside and Subsonic servers, each a source by its own name: Away when reached from
 * outside home, and how far a first sync has got.
 */
class ServerSourceProvider(
    entries: StateFlow<List<ServerEntry>>,
    scope: CoroutineScope,
    away: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    progress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap()),
    private val onRemove: suspend (String) -> Unit,
) : SourceProvider {
    override val kind = SourceKind.Server

    override val sources: StateFlow<List<Source>> = combine(entries, away, progress) { list, remote, fractions ->
        list.map { source(it, it.server.id in remote, fractions[it.server.id]) }
    }.stateIn(scope, SharingStarted.Eagerly, entries.value.map { source(it, it.server.id in away.value, progress.value[it.server.id]) })

    /** There can always be another server. */
    override val offersAdd: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun remove(id: SourceId) {
        if (id is SourceId.Server) onRemove(id.id)
    }

    companion object {
        fun source(entry: ServerEntry, away: Boolean = false, syncing: Float? = null): Source {
            val status = when (val s = entry.status) {
                ServerStatus.Syncing -> SourceStatus.Syncing(syncing)
                is ServerStatus.Ready -> if (away) SourceStatus.Away else SourceStatus.Connected
                is ServerStatus.Failed -> SourceStatus.Offline(s.message)
                ServerStatus.NeedsPassword -> SourceStatus.SignIn
                is ServerStatus.NeedsTrust -> SourceStatus.CheckCertificate
            }
            return Source(
                id = SourceId.Server(entry.server.id),
                name = entry.server.name,
                kind = SourceKind.Server,
                status = status,
                songCount = (entry.status as? ServerStatus.Ready)?.songCount,
                address = entry.server.url,
            )
        }
    }
}

/** TV plays from Trackside only (Apple Music reaches it through Trackside); see [ServerLibrary]'s tracksideOnly. */
val TV_SOURCE_KINDS: Set<SourceKind> = setOf(SourceKind.Server)

private val instances = WeakHashMap<AppGraph, SourcesRegistry>()
private val libraries = WeakHashMap<AppGraph, MusicLibrary>()

/** Every source, for this process. Made again if the library is replaced (sample data, tests). */
val AppGraph.sources: SourcesRegistry
    get() = synchronized(instances) {
        val existing = instances[this]
        if (existing != null && libraries[this] === library) return existing
        val combined = library as? CombinedLibrary
        val allowed = if (formFactor.tv) TV_SOURCE_KINDS else SourceKind.entries.toSet()
        val device = DeviceSourceProvider(
            library = library,
            hasPermission = combined?.device?.hasPermission?.takeUnless { sampleData } ?: MutableStateFlow(true),
            loading = combined?.device?.loading?.takeUnless { sampleData } ?: MutableStateFlow(false),
            scope = scope,
            canAsk = !sampleData,
        )
        val servers = combined?.servers?.let { ServerSourceProvider(it.entries, scope, it.away, it.syncProgress, onRemove = it::remove) }
        val carried = existing?.registered?.value.orEmpty().filter { it.kind == SourceKind.Spotify || it.kind == SourceKind.AppleMusic }
        val loadedServers = combined?.servers?.let { library ->
            combine(library.loading, library.entries) { loading, entries -> if (loading) null else entries.map { it.server.id } }
        } ?: flowOf(emptyList())
        SourcesRegistry(SourcesRegistry.prefs(context), scope, listOfNotNull(device, servers) + carried, loadedServers = loadedServers, allowedKinds = allowed).also {
            instances[this] = it
            libraries[this] = library
        }
    }
