package com.luke.tracks.data

import uniffi.tracks_engine.songIdentity
import java.security.MessageDigest
import java.util.UUID

/**
 * Lasting ids for plays, the same on every device: Room's key, the history index's, the
 * listen id a server dedupes by, and Trackside Cloud's. The Apple apps' `PlayID` makes the
 * same ones.
 */
object PlayIds {
    /** Every derived id is made in this namespace, as on iPhone and Mac. Never change it. */
    val namespace: UUID = UUID.fromString("8F6B1D3C-2A47-5E90-B1C8-4D2E7F9A0B36")

    /** What the history index has known a play by: when, how, from where and what. */
    fun contentKey(playedAtMs: Long, kind: String, source: String, songIdentity: String): String =
        "$playedAtMs\u001F$kind\u001F$source\u001F$songIdentity"

    fun derived(key: String): String = uuidV5(namespace, key).toString()

    /**
     * A scrobble brought in from Last.fm: the id an iPhone gives a Last.fm play kept before
     * ids existed, so the same scrobble imported anywhere is one play.
     */
    fun lastFm(playedAtSeconds: Long, title: String, artist: String): String =
        derived(contentKey(playedAtSeconds * 1000, "lastFM", "appleMusic", songIdentity(title, artist)))

    /** Ids older versions gave imported scrobbles: `lastfm-<seconds>-<hash>`. */
    fun isOldLastFm(id: String) = id.startsWith("lastfm-")

    /** RFC 9562 version 5: SHA-1 of the namespace's bytes and the name's UTF-8. */
    fun uuidV5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(ByteArray(16) { i ->
            val word = if (i < 8) namespace.mostSignificantBits else namespace.leastSignificantBits
            (word ushr (8 * (7 - i % 8))).toByte()
        })
        val hash = digest.digest(name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (i in 0 until 8) most = (most shl 8) or (hash[i].toLong() and 0xFF)
        for (i in 8 until 16) least = (least shl 8) or (hash[i].toLong() and 0xFF)
        return UUID(most, least)
    }
}
