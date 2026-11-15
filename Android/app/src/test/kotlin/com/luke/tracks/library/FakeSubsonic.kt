package com.luke.tracks.library

import okhttp3.HttpUrl

/**
 * A small Subsonic server that answers from memory: one artist, Harbor Static, with two
 * albums. [octo] answers as Octo does for an artist it found elsewhere: their page has no
 * albums, so they come from search. Every request is kept in [asked].
 */
class FakeSubsonic(private val octo: Boolean = false, private val down: Boolean = false) : SubsonicTransport {
    val server = SubsonicServer("s1", "Octo at home", "http://192.168.1.20:4533", "luke", "salt")
    val asked = mutableListOf<HttpUrl>()

    fun browse() = ServerBrowse(server, SubsonicClient(server, "sesame", this))

    private val slowSignal = listOf("Low Lamps", "Slow Signal", "Static Bloom", "Breakwater", "Lighthouse Keeper", "Night Ferry", "Weather Report", "Tidewater")
    private val paperCoast = listOf("Paper Coast", "Salt Air", "Gulls", "Shoreline")

    private fun song(id: String, title: String, album: String, albumId: String, track: Int) =
        """{"id":"$id","title":"$title","artist":"Harbor Static","artistId":"ar-1","album":"$album","albumId":"$albumId","track":$track,"duration":214,"coverArt":"$albumId","year":2024}"""

    private val albums = """[{"id":"al-10","name":"Slow Signal","artist":"Harbor Static","artistId":"ar-1","year":2024,"coverArt":"al-10","songCount":8},
        {"id":"al-11","name":"Paper Coast","artist":"Harbor Static","artistId":"ar-1","year":2021,"coverArt":"al-11","songCount":4}]"""

    private fun ok(body: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1",$body}}"""

    override suspend fun get(url: HttpUrl): Pair<Int, String> {
        asked += url
        if (down) throw java.io.IOException("Connection refused")
        return 200 to when (url.pathSegments.last()) {
            "getSong" -> ok(""""song":${song(url.queryParameter("id")!!, "Static Bloom", "Slow Signal", "al-10", 3)}""")
            "getAlbum" -> when (url.queryParameter("id")) {
                "al-10" -> ok(""""album":{"id":"al-10","name":"Slow Signal","artist":"Harbor Static","artistId":"ar-1","year":2024,"coverArt":"al-10","song":[${slowSignal.mapIndexed { i, t -> song("t$i", t, "Slow Signal", "al-10", i + 1) }.reversed().joinToString(",")}]}""")
                "al-11" -> ok(""""album":{"id":"al-11","name":"Paper Coast","artist":"Harbor Static","artistId":"ar-1","year":2021,"song":[${paperCoast.mapIndexed { i, t -> song("p$i", t, "Paper Coast", "al-11", i + 1) }.joinToString(",")}]}""")
                else -> """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}"""
            }
            "getArtist" -> ok(""""artist":{"id":"ar-1","name":"Harbor Static","album":${if (octo) "[]" else albums}}""")
            "getTopSongs" -> ok(""""topSongs":{"song":[${slowSignal.take(5).mapIndexed { i, t -> song("t$i", t, "Slow Signal", "al-10", i + 1) }.joinToString(",")}]}""")
            "search3" -> ok(""""searchResult3":{"artist":[{"id":"ar-1","name":"Harbor Static"}],"album":$albums,"song":[]}""")
            else -> ok(""""x":1""")
        }
    }
}
