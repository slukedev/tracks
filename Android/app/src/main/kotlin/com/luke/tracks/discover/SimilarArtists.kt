package com.luke.tracks.discover

import uniffi.tracks_engine.DeezerArtist
import uniffi.tracks_engine.deezerArtistMatch
import uniffi.tracks_engine.deezerParseArtists
import uniffi.tracks_engine.deezerRelated
import uniffi.tracks_engine.deezerSearchArtists

/**
 * Artists like one of yours, from Deezer's public API: no key, and no account. The engine
 * builds each request and reads each answer.
 */
class DeezerSource(private val transport: WebTransport) {
    /** Artists Deezer's listeners play alongside [name], closest first; empty if it doesn't know them. */
    suspend fun related(name: String, limit: Int = 20): List<DeezerArtist> {
        val found = deezerParseArtists(transport.send(deezerSearchArtists(name, 5u)))
        val artist = deezerArtistMatch(name, found)?.let { found[it.toInt()] } ?: return emptyList()
        return deezerParseArtists(transport.send(deezerRelated(artist.id, limit.toUInt())))
    }
}
