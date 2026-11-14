package com.luke.tracks.ui.insights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.luke.tracks.AppGraph
import com.luke.tracks.engine.Engine
import com.luke.tracks.graph
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.tracks_engine.AlbumStats
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.Credit
import uniffi.tracks_engine.SongStats
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.credits
import uniffi.tracks_engine.foldedName
import uniffi.tracks_engine.songIdentity
import java.text.Normalizer

@Composable
fun rememberGraph(): AppGraph = LocalContext.current.graph

/**
 * A value read from the engine off the main thread, read again whenever the history changes
 * or a key does. Keeps showing the last value while the next one loads, so nothing flashes.
 */
@Composable
fun <T> engineState(vararg keys: Any?, read: suspend (Engine) -> T): State<T?> {
    val engine = rememberGraph().engine
    val revision by engine.revision.collectAsState()
    return produceState<T?>(null, revision, *keys) { value = read(engine) }
}

/** How the engine names songs, artists and albums, worked out the same way it does. */
object Identities {
    fun song(title: String, artist: String): String = songIdentity(title, artist)

    /** Everyone credited on a song, main artists first. */
    fun credits(artist: String, title: String): List<Credit> =
        uniffi.tracks_engine.credits(artist, title)

    fun leadArtist(artist: String, title: String): Credit =
        credits(artist, title).firstOrNull() ?: Credit(artist, foldedName(artist))

    /** The album's identity: folded title and its lead artist's identity. */
    fun album(albumTitle: String?, artist: String, title: String): String? {
        val folded = foldedName(albumTitle ?: return null)
        if (folded.isEmpty()) return null
        return folded + "\u001F" + leadArtist(artist, title).identity
    }
}

/** Loose folding for quick prefilters before asking the engine. */
internal fun loose(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

/**
 * Whole-history lists, read once per history revision and shared by every detail page, so
 * opening an artist doesn't read the history again.
 */
object AllTime {
    private val mutex = Mutex()
    private var revision = -1L
    private var songs: List<SongStats> = emptyList()
    private var artists: List<ArtistStats> = emptyList()
    private var albums: List<AlbumStats> = emptyList()

    private suspend fun fill(engine: Engine) = mutex.withLock {
        val now = engine.revision.value
        if (now == revision) return@withLock
        engine.read {
            songs = songChart(Engine.allTime, null, SourceFilter.ALL, 100_000u).rows.map { it.song }
            artists = artistChart(Engine.allTime, null, SourceFilter.ALL, 100_000u).rows.map { it.artist }
            albums = albumChart(Engine.allTime, null, SourceFilter.ALL, 100_000u).rows.map { it.album }
        }
        revision = now
    }

    suspend fun songs(engine: Engine): List<SongStats> { fill(engine); return songs }
    suspend fun artists(engine: Engine): List<ArtistStats> { fill(engine); return artists }
    suspend fun albums(engine: Engine): List<AlbumStats> { fill(engine); return albums }

    /** When the first play was, or null for an empty history. */
    suspend fun firstPlayMs(engine: Engine): Long? = artists(engine).minOfOrNull { it.firstPlayedMs }

    /** An artist's songs, most played first, including the ones they're featured on. */
    suspend fun songsBy(engine: Engine, artistIdentity: String, artistName: String): List<SongStats> {
        val all = songs(engine)
        val name = loose(artistName)
        return engine.read {
            all.filter { song ->
                loose(song.artistName).contains(name) &&
                    credits(song.artistName, song.title).any { it.identity == artistIdentity }
            }
        }
    }

    /** An album's songs, most played first. */
    suspend fun songsOn(engine: Engine, albumIdentity: String, albumTitle: String): List<SongStats> {
        val all = songs(engine)
        val title = loose(albumTitle)
        return engine.read {
            all.filter { song ->
                song.albumTitle != null && loose(song.albumTitle!!) == title &&
                    Identities.album(song.albumTitle, song.artistName, song.title) == albumIdentity
            }
        }
    }

    /** An artist's albums, most played first. */
    suspend fun albumsBy(engine: Engine, artistIdentity: String): List<AlbumStats> {
        val suffix = "\u001F" + artistIdentity
        return albums(engine).filter { it.identity.endsWith(suffix) }
    }
}
