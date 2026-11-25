package com.luke.tracks.library

import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LibraryAlbum(
    val id: String,
    val title: String,
    val artistName: String,
    val artworkUri: android.net.Uri?,
    val year: Int?,
    val songs: List<PlayableSong>,
)

data class LibraryArtist(
    val id: String,
    val name: String,
    val albums: List<LibraryAlbum>,
    val songCount: Int,
)

/** Where a song comes from. */
enum class LibrarySourceKind { Device, Server, AppleMusic }

data class LibrarySnapshot(
    val songs: List<PlayableSong> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val artists: List<LibraryArtist> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the person allows access to music files. */
    val hasPermission: Boolean = true,
)

/**
 * Your Music: the audio files on the phone (MediaStore) and your own Subsonic-compatible
 * servers, merged, with lookups that tie the history to something playable.
 */
interface MusicLibrary {
    val snapshot: StateFlow<LibrarySnapshot>

    /** Lookups from a song to its album and artist, where the library groups them. */
    val index: StateFlow<LibraryIndex> get() = NoIndex

    /** Re-reads the phone's files and the servers. */
    suspend fun refresh()

    /** A playable song for a history identity (folded title and artist), if you have it. */
    fun find(identity: String): PlayableSong?

    /** Playable songs for many identities, in order, skipping the ones you don't have. */
    fun findAll(identities: List<String>): List<PlayableSong> = identities.mapNotNull(::find)

    suspend fun search(text: String): List<PlayableSong>
}

private val NoIndex: StateFlow<LibraryIndex> = MutableStateFlow(LibraryIndex.Empty)
