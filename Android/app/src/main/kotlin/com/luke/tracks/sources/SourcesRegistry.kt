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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
) {
    private val providers = MutableStateFlow(builtIn)

    /** Every provider, the built-in ones first. */
    val registered: StateFlow<List<SourceProvider>> = providers.asStateFlow()

    /** Adds a kind of source. A second provider of the same kind replaces the first. */
    fun register(provider: SourceProvider) {
        if (provider.kind == SourceKind.Spotify && !spotifyAllowed) return
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

    fun arrangedPlayFrom(list: List<Source> = sources.value): List<PlayFromPlace> =
        order.value.arranged(list.serverIds(), list.any { it.kind == SourceKind.Spotify })

    /** Saves a new order of the places here. */
    fun setPlayFrom(places: List<PlayFromPlace>) {
        val list = sources.value
        val next = order.value.replacing(places, list.serverIds(), list.any { it.kind == SourceKind.Spotify })
        order.value = next
        prefs.edit().putString(ORDER_KEY, next.raw).apply()
    }

    /** The source a song plays from, by its media id. */
    fun sourceOf(mediaId: String): SourceId? =
        SourceId.ofMediaId(mediaId) ?: providers.value.firstNotNullOfOrNull { it.sourceOf(mediaId) }

    fun source(id: SourceId?): Source? = id?.let { wanted -> sources.value.firstOrNull { it.id == wanted } }

    /** Whether a song's source can play it now. A song whose source isn't known is given the benefit of the doubt. */
    fun canPlay(mediaId: String): Boolean = source(sourceOf(mediaId))?.status?.canPlay ?: true

    private val skipped = MutableSharedFlow<SkippedSongs>(extraBufferCapacity = 4)

    /** Each time the player skips songs it couldn't play, for the one quiet message. */
    val skips: SharedFlow<SkippedSongs> = skipped.asSharedFlow()

    /** Called by the player when it passes over songs whose source can't play them. */
    fun reportSkipped(songs: SkippedSongs) {
        if (songs.count > 0) skipped.tryEmit(songs)
    }

    private fun List<Source>.serverIds() = mapNotNull { (it.id as? SourceId.Server)?.id }

    companion object {
        private const val SELECTION_KEY = "sourceFilter"
        private const val ORDER_KEY = "playFromOrder"
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

/** Your Trackside and Subsonic servers, each a source by its own name. */
class ServerSourceProvider(
    entries: StateFlow<List<ServerEntry>>,
    scope: CoroutineScope,
    private val onRemove: suspend (String) -> Unit,
) : SourceProvider {
    override val kind = SourceKind.Server

    override val sources: StateFlow<List<Source>> = entries
        .map { list -> list.map(::source) }
        .stateIn(scope, SharingStarted.Eagerly, entries.value.map(::source))

    /** There can always be another server. */
    override val offersAdd: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun remove(id: SourceId) {
        if (id is SourceId.Server) onRemove(id.id)
    }

    companion object {
        fun source(entry: ServerEntry): Source {
            val status = when (val s = entry.status) {
                ServerStatus.Syncing -> SourceStatus.Syncing()
                is ServerStatus.Ready -> SourceStatus.Connected
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

private val instances = WeakHashMap<AppGraph, SourcesRegistry>()
private val libraries = WeakHashMap<AppGraph, MusicLibrary>()

/** Every source, for this process. Made again if the library is replaced (sample data, tests). */
val AppGraph.sources: SourcesRegistry
    get() = synchronized(instances) {
        val existing = instances[this]
        if (existing != null && libraries[this] === library) return existing
        val combined = library as? CombinedLibrary
        val device = DeviceSourceProvider(
            library = library,
            hasPermission = combined?.device?.hasPermission?.takeUnless { sampleData } ?: MutableStateFlow(true),
            loading = combined?.device?.loading?.takeUnless { sampleData } ?: MutableStateFlow(false),
            scope = scope,
            canAsk = !sampleData,
        )
        val servers = combined?.servers?.let { ServerSourceProvider(it.entries, scope, onRemove = it::remove) }
        val carried = existing?.registered?.value.orEmpty().filter { it.kind == SourceKind.Spotify }
        SourcesRegistry(SourcesRegistry.prefs(context), scope, listOfNotNull(device, servers) + carried).also {
            instances[this] = it
            libraries[this] = library
        }
    }
