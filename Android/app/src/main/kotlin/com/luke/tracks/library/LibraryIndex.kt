package com.luke.tracks.library

import com.luke.tracks.playback.PlayableSong
import uniffi.tracks_engine.foldedName

/** One song as a source reads it, before the sources are merged. */
data class LibraryTrack(
    val song: PlayableSong,
    val source: LibrarySourceKind,
    /** Groups the songs of one album within its source. */
    val albumKey: String,
    val albumArtist: String? = null,
    val disc: Int? = null,
    /** When it arrived on the phone or the server, for "recently added". */
    val addedAtMs: Long? = null,
)

/**
 * The merged library with the lookups the rest of the app needs: by identity (to tie the
 * history to something playable), by media id (for the player and the browse tree), and from
 * a song to its album and artist.
 */
class LibraryIndex(
    val snapshot: LibrarySnapshot,
    private val byIdentity: Map<String, PlayableSong>,
    private val byMediaId: Map<String, PlayableSong>,
    private val albumOfSong: Map<String, String>,
    private val albumsById: Map<String, LibraryAlbum>,
    private val artistsById: Map<String, LibraryArtist>,
    private val searchKeys: Map<String, String>,
) {
    fun find(identity: String): PlayableSong? = byIdentity[identity]
    fun song(mediaId: String): PlayableSong? = byMediaId[mediaId]
    fun album(id: String): LibraryAlbum? = albumsById[id]
    fun artist(id: String): LibraryArtist? = artistsById[id]
    fun albumIdOf(song: PlayableSong): String? = albumOfSong[song.mediaId]
    fun artistIdOf(name: String): String? = artistId(name).takeIf { it in artistsById }

    /** An artist's songs, by album then track. */
    fun songsBy(artist: LibraryArtist): List<PlayableSong> {
        val key = foldedName(artist.name)
        val fromAlbums = artist.albums.flatMap { it.songs }
        val onAlbums = fromAlbums.mapTo(HashSet()) { it.mediaId }
        val others = snapshot.songs.filter { foldedName(it.artistName) == key && it.mediaId !in onAlbums }
        return fromAlbums + others
    }

    fun search(text: String, limit: Int = 60): List<PlayableSong> {
        val words = foldedName(text).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return snapshot.songs.asSequence()
            .filter { song -> val key = searchKeys[song.mediaId].orEmpty(); words.all { key.contains(it) } }
            .take(limit)
            .toList()
    }

    fun searchAlbums(text: String, limit: Int = 20): List<LibraryAlbum> {
        val words = foldedName(text).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return snapshot.albums.asSequence()
            .filter { album -> val key = foldedName(album.title + " " + album.artistName); words.all { key.contains(it) } }
            .take(limit).toList()
    }

    fun searchArtists(text: String, limit: Int = 20): List<LibraryArtist> {
        val folded = foldedName(text)
        if (folded.isBlank()) return emptyList()
        return snapshot.artists.asSequence().filter { foldedName(it.name).contains(folded) }.take(limit).toList()
    }

    companion object {
        val Empty = build(emptyList(), hasPermission = true, isLoading = true)

        fun artistId(name: String) = "artist:" + foldedName(name)

        /**
         * Merges [tracks], earlier sources first: a song on the phone and on a server shows once,
         * and plays from the phone.
         */
        fun build(
            tracks: List<LibraryTrack>,
            hasPermission: Boolean,
            isLoading: Boolean = false,
        ): LibraryIndex {
            val seenSongs = HashSet<String>()
            val kept = ArrayList<LibraryTrack>(tracks.size)
            for (track in tracks) {
                if (seenSongs.add(track.song.identity)) kept += track
            }

            val albumGroups = LinkedHashMap<String, MutableList<LibraryTrack>>()
            kept.forEach { albumGroups.getOrPut(it.albumKey) { mutableListOf() } += it }

            val albums = ArrayList<LibraryAlbum>()
            val albumOfSong = HashMap<String, String>()
            val seenAlbums = HashSet<String>()
            for ((key, group) in albumGroups) {
                val first = group.first()
                val title = first.song.albumTitle?.takeIf { it.isNotBlank() } ?: continue
                val artist = first.albumArtist?.takeIf { it.isNotBlank() } ?: mostCommon(group.map { it.song.artistName })
                if (!seenAlbums.add(foldedName(title) + "\u0000" + foldedName(artist))) continue
                val songs = group.sortedWith(compareBy<LibraryTrack>({ it.disc ?: 1 }, { it.song.trackNumber ?: Int.MAX_VALUE }, { foldedName(it.song.title) })).map { it.song }
                songs.forEach { albumOfSong[it.mediaId] = key }
                albums += LibraryAlbum(
                    id = key,
                    title = title,
                    artistName = artist,
                    artworkUri = group.firstNotNullOfOrNull { it.song.artworkUri },
                    year = group.mapNotNull { it.song.year }.maxOrNull(),
                    songs = songs,
                )
            }
            albums.sortWith(compareBy { foldedName(it.title) })

            // Artists from album artists and song artists alike, so a guest spot still has a page.
            val names = LinkedHashMap<String, String>()
            kept.forEach { names.putIfAbsent(foldedName(it.song.artistName), it.song.artistName) }
            albums.forEach { names.putIfAbsent(foldedName(it.artistName), it.artistName) }
            val albumsByArtist = albums.groupBy { foldedName(it.artistName) }
            val songCounts = kept.groupingBy { foldedName(it.song.artistName) }.eachCount()
            val artists = names.filterKeys { it.isNotBlank() }.map { (folded, name) ->
                LibraryArtist(
                    id = "artist:$folded",
                    name = name,
                    albums = albumsByArtist[folded].orEmpty().sortedByDescending { it.year ?: 0 },
                    songCount = songCounts[folded] ?: 0,
                )
            }.sortedBy { foldedName(it.name) }

            val songs = kept.map { it.song }.sortedBy { foldedName(it.title) }
            return LibraryIndex(
                snapshot = LibrarySnapshot(songs, albums, artists, isLoading = isLoading, hasPermission = hasPermission),
                byIdentity = songs.associateBy { it.identity },
                byMediaId = songs.associateBy { it.mediaId },
                albumOfSong = albumOfSong,
                albumsById = albums.associateBy { it.id },
                artistsById = artists.associateBy { it.id },
                searchKeys = songs.associate { it.mediaId to foldedName("${it.title} ${it.artistName} ${it.albumTitle.orEmpty()}") },
            )
        }

        private fun mostCommon(names: List<String>): String =
            names.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    }
}
