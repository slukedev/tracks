import Testing
import Foundation
@testable import TracksCore

/// api.md's own examples. Where api.md writes a placeholder ("[Song]", "..."), a real value
/// stands in for it.
enum APIExamples {
    static let song = #"""
    {
      "id": "s7m2k4q9x1v8b3n6c0de",
      "library_id": "l0a8f2c9e7d1b3k5m6n4",
      "title": "Halo",
      "artist": "Beyoncé",
      "artist_id": "r1q8z0c3m5n7b9v2x4w6",
      "artists": [{"id": "r1q8z0c3m5n7b9v2x4w6", "name": "Beyoncé", "role": "main"}],
      "album": "I Am... Sasha Fierce",
      "album_id": "a5c2v9b1n3m7q4w8e0r6",
      "album_artist": "Beyoncé",
      "track_number": 3,
      "disc_number": 1,
      "year": 2008,
      "genres": ["R&B", "Pop"],
      "duration_ms": 261640,
      "format": {
        "container": "flac",
        "codec": "flac",
        "content_type": "audio/flac",
        "lossless": true,
        "bitrate_kbps": 1012,
        "sample_rate": 44100,
        "bit_depth": 16,
        "channels": 2,
        "size": 33102456
      },
      "artwork_id": "c9d0e1f2a3b4c5d6e7f8",
      "song_key": "halo\u001fbeyonce",
      "replay_gain": {"track_gain": -8.21, "track_peak": 0.988, "album_gain": -8.9, "album_peak": 1.0},
      "mbz": {"recording": "e6a7...", "track": "1f2b...", "release": "9c8d..."},
      "isrc": "USSM10804555",
      "explicit": "clean",
      "has_lyrics": true,
      "added_at": "2026-03-02T18:04:11.512Z",
      "updated_at": "2026-03-02T18:04:11.512Z",
      "missing": false
    }
    """#

    static let album = #"""
    {
      "id": "a5c2v9b1n3m7q4w8e0r6",
      "library_id": "l0a8f2c9e7d1b3k5m6n4",
      "name": "I Am... Sasha Fierce",
      "album_artist": "Beyoncé",
      "artist_id": "r1q8z0c3m5n7b9v2x4w6",
      "year": 2008,
      "release_date": "2008-11-12",
      "genres": ["R&B"],
      "is_compilation": false,
      "release_type": "album",
      "song_count": 11,
      "disc_count": 1,
      "duration_ms": 2486000,
      "artwork_id": "c9d0e1f2a3b4c5d6e7f8",
      "album_key": "i am... sasha fierce\u001fbeyonce",
      "mbz": {"release": "9c8d...", "release_group": "77aa..."},
      "added_at": "2026-03-02T18:04:11.512Z",
      "updated_at": "2026-03-02T18:04:11.512Z"
    }
    """#

    static let artist = #"""
    {
      "id": "r1q8z0c3m5n7b9v2x4w6",
      "name": "Beyoncé",
      "identity": "beyonce",
      "album_count": 7,
      "song_count": 112,
      "artwork_id": "c0a1b2c3d4e5f6a7b8c9",
      "mbz": {"artist": "859d0860-d480-4efd-970c-c05d5f1776b8"}
    }
    """#

    static let playlist = #"""
    {
      "id": "p3k9m2x7c1v5b8n0q4w6",
      "name": "Late Drive",
      "comment": "",
      "owner": {"id": "u8b1n4m7q0w3e6r9t2y5", "display_name": "Luke"},
      "is_public": false,
      "song_count": 42,
      "duration_ms": 9876000,
      "revision": 12,
      "created_at": "2026-01-02T12:00:00.000Z",
      "updated_at": "2026-11-10T08:00:00.000Z"
    }
    """#

    static let library = #"{"id": "l0a8f2c9e7d1b3k5m6n4", "name": "Music", "state": "ok", "song_count": 48211}"#

    static let ping = #"{"server_id": "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", "time": "2026-11-14T09:30:00.000Z"}"#

    static let capabilities = #"""
    {
      "product": "trackside",
      "server_id": "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b",
      "name": "Home",
      "version": "1.0.0",
      "api": {"versions": ["v1"], "current": "v1", "deprecated": []},
      "setup_required": false,
      "auth": {"password_login": true, "pairing": true, "pair_requests": true},
      "media": {
        "transcoding": true,
        "profiles": ["aac-256", "aac-128", "mp3-320", "mp3-128", "opus-128", "flac"],
        "hls": true,
        "suggested_cellular_profile": "aac-256"
      },
      "features": {"lyrics": true, "uploads": false, "events": true, "animated_art": false},
      "subsonic": {"enabled": true, "path": "/rest"}
    }
    """#

    static let pairResult = #"""
    {
      "api_key": "tsk_9pQ...",
      "device": {"id": "d2c4...", "name": "Luke's iPhone"},
      "user": {"id": "u8b1...", "username": "luke", "display_name": "Luke", "role": "admin"},
      "media_token": {"token": "eyJ1Ijoi...", "expires_at": "2026-11-21T09:30:00.000Z"},
      "server": {"server_id": "5f0c...", "name": "Home", "urls": [
        {"url": "http://192.168.1.20:4747", "kind": "lan", "tls": false},
        {"url": "https://music.example.com", "kind": "public", "tls": true}]}
    }
    """#

    static let pairRequest = #"""
    {"id": "g9h2...", "code": "481 207", "poll_secret": "Zx81...", "expires_at": "2026-11-14T09:40:00.000Z",
     "poll_interval_ms": 2000}
    """#

    static let me = #"""
    {
      "user": {"id": "u8b1...", "username": "luke", "display_name": "Luke", "role": "member",
               "permissions": {"can_download": true, "can_upload": false, "legacy_subsonic": false}},
      "libraries": [{"id": "l0a8...", "name": "Music", "state": "ok", "song_count": 48211}],
      "device": {"id": "d2c4...", "name": "Luke's iPhone"},
      "server": {"server_id": "5f0c...", "name": "Home", "version": "1.0.0", "urls": [
        {"url": "http://192.168.1.20:4747", "kind": "lan", "tls": false}]},
      "features": {"acquisition": {"instant_play": true, "external_play": true}}
    }
    """#

    static let externalPlay = #"""
    {
      "title": "Halo",
      "artist": "Beyoncé",
      "duration_ms": 261000,
      "stream": {"hls": "/api/v1/external/plays/x3n8b2v6c1m9q4w7e0r5t8y2/stream.m3u8",
                 "progressive": "/api/v1/external/plays/x3n8b2v6c1m9q4w7e0r5t8y2/stream"}
    }
    """#

    static let meWithoutAcquisition = #"""
    {
      "user": {"id": "u8b1...", "username": "luke", "display_name": "Luke", "role": "member"},
      "libraries": [],
      "server": {"server_id": "5f0c...", "name": "Home"},
      "features": {}
    }
    """#

    static let snapshotPage = #"""
    {
      "mode": "snapshot",
      "snapshot": {"seq": 918274, "kind": "songs", "next": "c25hcDox..."},
      "songs": [\#(song)],
      "next_cursor": "c25hcDox..."
    }
    """#

    static let deltaPage = #"""
    {
      "mode": "delta",
      "upserts": {
        "songs": [\#(song)],
        "albums": [\#(album)],
        "artists": [\#(artist)],
        "libraries": [\#(library)],
        "playlists": [{"playlist": \#(playlist), "entries": ["s7m2...", "s1x9..."]}],
        "stars": [{"kind": "song", "id": "s7m2...", "starred_at": "2026-05-01T10:00:00.000Z"}],
        "ratings": [{"kind": "album", "id": "a5c2...", "rating": 5}],
        "play_stats": [{"id": "s7m2...", "play_count": 18, "last_played_at": "2026-11-13T21:14:09.000Z"}],
        "play_queue": {"entries": ["s7m2..."], "current": 3, "position_ms": 81000, "changed_by": "Tracks", "updated_at": "2026-11-13T21:14:09.000Z"}
      },
      "deletes": {
        "songs": ["s0dead..."], "albums": [], "artists": [], "libraries": [],
        "playlists": [], "stars": [{"kind": "song", "id": "s4..."}], "ratings": []
      },
      "next_cursor": "ZGVsdGE6...",
      "has_more": false
    }
    """#

    static let decision = #"""
    {
      "mode": "file",
      "url": "/api/v1/songs/s7m2.../stream?profile=aac-256",
      "delivered": {"container": "m4a", "codec": "aac", "bitrate_kbps": 256, "sample_rate": 44100,
                    "lossless": false, "size": 8413002, "duration_ms": 261640},
      "reason": "over_bitrate",
      "etag": "\"s7m2....3.aac-256.f3c1a9\""
    }
    """#

    static let lyrics = #"""
    {
      "lyrics": [
        {"kind": "synced", "lang": "eng", "source": "sidecar", "offset_ms": 0,
         "lines": [{"start_ms": 12340, "text": "Remember those walls I built?"}]},
        {"kind": "plain", "lang": "eng", "source": "embedded",
         "lines": [{"text": "Remember those walls I built?"}]}
      ]
    }
    """#

    static let error = #"""
    {
      "error": {
        "code": "not_found",
        "message": "No song with id s7m2k...",
        "details": {"id": "s7m2k4q9x1v8b3n6c0de"},
        "request_id": "01JD5Z..."
      }
    }
    """#

    static let candidate = #"""
    {
      "token": "x3n8b2v6c1m9q4w7e0r5t8y2",
      "title": "Halo",
      "artist": "Beyoncé",
      "album": "I Am... Sasha Fierce",
      "duration_ms": 261000,
      "quality": "lossless",
      "format": {"container": "flac", "codec": "flac", "bitrate_kbps": 1010, "sample_rate": 44100, "bit_depth": 16},
      "size": 33000000,
      "instant": false,
      "match": "exact"
    }
    """#

    static let acquisition = #"""
    {
      "id": "q8w1e4r7t0y3u6i9o2p5",
      "state": "downloading",
      "title": "Halo",
      "artist": "Beyoncé",
      "album": "I Am... Sasha Fierce",
      "progress": {"bytes": 12000000, "total": 33000000},
      "quality": "lossless",
      "song_id": null,
      "existing": false,
      "failure": null,
      "stream": null,
      "created_at": "2026-11-14T09:30:00.000Z",
      "updated_at": "2026-11-14T09:30:07.000Z"
    }
    """#

    static let acquisitionSearch = #"""
    {
      "search_id": "v2b5...",
      "complete": true,
      "in_library": [\#(song)],
      "candidates": [\#(candidate)]
    }
    """#
}

@Suite("Trackside's wire format")
struct TracksideWireTests {
    @Test("a song reads with every field, and its key is the one Tracks groups plays by")
    func song() throws {
        let song = try TracksideTesting.decode(TracksideSong.self, APIExamples.song)
        #expect(song.title == "Halo")
        #expect(song.format?.bitDepth == 16)
        #expect(song.addedAt == TracksideTesting.date("2026-03-02T18:04:11.512Z"))
        let track = song.track(on: "home")
        #expect(song.songKey == track.identity)
        #expect(track.origin == .server(serverID: "home", songID: "s7m2k4q9x1v8b3n6c0de"))
        #expect(track.artwork == .server(serverID: "home", coverID: "c9d0e1f2a3b4c5d6e7f8"))
        #expect(track.format?.detail == "16-bit/44.1 kHz")
        #expect(track.duration == 261.64)
        #expect(track.genre == "R&B")
        #expect(track.addedAt == song.addedAt)
    }

    @Test("a song in the shape the rest of Tracks reads server songs in")
    func asSubsonic() throws {
        let song = try TracksideTesting.decode(TracksideSong.self, APIExamples.song).subsonicSong
        #expect(song.id == "s7m2k4q9x1v8b3n6c0de")
        #expect(song.duration == 262)
        #expect(song.coverArt == "c9d0e1f2a3b4c5d6e7f8")
        #expect(song.artistId == "r1q8z0c3m5n7b9v2x4w6")
        // Subsonic dates are read to the second.
        let created = try #require(song.createdDate)
        #expect(abs(created.timeIntervalSince(TracksideTesting.date("2026-03-02T18:04:11.512Z"))) < 1)
        #expect(song.track(on: "home").identity == SongIdentity.key(title: "Halo", artistName: "Beyoncé"))
    }

    @Test("WAV and AIFF are named by their container, since what's in them is PCM")
    func pcm() {
        #expect(TracksideFormat(container: "wav", codec: "pcm_s16le").audioFormat.codec == "WAV")
        #expect(TracksideFormat(container: "m4a", codec: "alac").audioFormat.codec == "ALAC")
    }

    @Test("every shared object and answer api.md shows reads")
    func examples() throws {
        let album = try TracksideTesting.decode(TracksideAlbum.self, APIExamples.album)
        #expect(album.subsonicAlbum.coverArt == "c9d0e1f2a3b4c5d6e7f8")
        #expect(try TracksideTesting.decode(TracksideArtist.self, APIExamples.artist).identity == "beyonce")
        #expect(try TracksideTesting.decode(TracksidePlaylist.self, APIExamples.playlist).revision == 12)
        #expect(try TracksideTesting.decode(TracksideLibrary.self, APIExamples.library).state == .ok)
        #expect(try TracksideTesting.decode(TracksidePing.self, APIExamples.ping).serverID == TracksideTesting.serverUUID)
        let capabilities = try TracksideTesting.decode(TracksideCapabilities.self, APIExamples.capabilities)
        #expect(capabilities.isTrackside)
        #expect(capabilities.media?.suggestedCellularProfile == "aac-256")
        let paired = try TracksideTesting.decode(TracksidePairResult.self, APIExamples.pairResult)
        #expect(paired.server.urls?.map(\.kind) == [.lan, .public])
        #expect(paired.mediaToken?.expiresAt == TracksideTesting.date("2026-11-21T09:30:00.000Z"))
        #expect(try TracksideTesting.decode(TracksidePairRequest.self, APIExamples.pairRequest).pollIntervalMS == 2000)
        let me = try TracksideTesting.decode(TracksideMe.self, APIExamples.me)
        #expect(me.features?.acquisition?.instantPlay == true)
        #expect(me.user.permissions?.maxBitrateKbps == nil)
        #expect(try TracksideTesting.decode(TracksideMe.self, APIExamples.meWithoutAcquisition).features?.acquisition == nil)
        let decision = try TracksideTesting.decode(TracksidePlaybackDecision.self, APIExamples.decision)
        #expect(decision.mode == .file)
        #expect(decision.delivered?.audioFormat?.detail == "256 kbps")
        #expect(try TracksideTesting.decode(TracksideLyrics.self, APIExamples.lyrics).lyrics.map(\.kind) == ["synced", "plain"])
        let envelope = try TracksideTesting.decode(TracksideErrorEnvelope.self, APIExamples.error)
        #expect(envelope.error.code == "not_found")
        let search = try TracksideTesting.decode(TracksideAcquisitionSearch.self, APIExamples.acquisitionSearch)
        #expect(search.candidates.first?.quality == .lossless)
        #expect(search.inLibrary?.first?.songKey == "halo\u{1F}beyonce")
        let acquisition = try TracksideTesting.decode(TracksideAcquisition.self, APIExamples.acquisition)
        #expect(acquisition.songID == nil)
        #expect(acquisition.phase == .getting(12_000_000.0 / 33_000_000.0))
    }

    @Test("a snapshot page and a delta page read, every kind in them")
    func syncPages() throws {
        let snapshot = try TracksideTesting.decode(TracksideSyncPage.self, APIExamples.snapshotPage)
        #expect(snapshot.mode == .snapshot)
        #expect(snapshot.snapshot?.seq == 918274)
        #expect(snapshot.songs?.count == 1)
        let delta = try TracksideTesting.decode(TracksideSyncPage.self, APIExamples.deltaPage)
        #expect(delta.mode == .delta)
        #expect(delta.upserts?.playlists?.first?.entries?.count == 2)
        #expect(delta.upserts?.playQueue?.positionMS == 81000)
        #expect(delta.deletes?.stars?.first?.kind == .song)
        #expect(delta.hasMore == false)
    }

    @Test("values and fields a newer server adds are ignored, not refused")
    func newerServer() throws {
        let library = try TracksideTesting.decode(TracksideLibrary.self, #"{"id": "l1", "name": "Music", "state": "rescanning", "colour": "teal"}"#)
        #expect(library.state == .unknown)
        let page = try TracksideTesting.decode(TracksideSyncPage.self, #"{"mode": "compacted", "next_cursor": "x", "widgets": [1]}"#)
        #expect(page.mode == .unknown)
        let url = try TracksideTesting.decode(TracksideServerURL.self, #"{"url": "http://h:4747", "kind": "mesh"}"#)
        #expect(url.kind == .unknown)
    }

    @Test("times read with or without milliseconds, and are written with them")
    func times() throws {
        #expect(TracksideJSON.date(from: "2026-11-14T09:30:00Z") == TracksideJSON.date(from: "2026-11-14T09:30:00.000Z"))
        #expect(TracksideJSON.date(from: "yesterday") == nil)
        let written = try TracksideJSON.encoder.encode(TracksideStarRequest(at: TracksideTesting.date("2026-11-14T09:30:00.250Z")))
        #expect(String(decoding: written, as: UTF8.self) == #"{"at":"2026-11-14T09:30:00.250Z"}"#)
    }
}

/// The server's fixtures, once they exist. Each file is read as the shape its fields say it
/// is; shapes Tracks never reads, such as the dashboard's, are left alone.
@Suite("Trackside's fixtures", .enabled(if: TracksideFixtures.directory != nil, "The server's fixtures aren't in this checkout yet"))
struct TracksideFixtureTests {
    @Test("every fixture Tracks reads decodes, and each song's key is the one Tracks makes")
    func fixtures() throws {
        var read = 0
        for file in TracksideFixtures.files {
            let data = try Data(contentsOf: file)
            let object = try JSONSerialization.jsonObject(with: data)
            guard let fields = object as? [String: Any], let decode = Self.reader(for: fields) else { continue }
            do {
                let songs = try decode(data)
                for song in songs {
                    #expect(song.songKey == nil || song.songKey == song.track(on: "fixture").identity, "\(file.lastPathComponent): \(song.title)")
                }
                read += 1
            } catch {
                Issue.record("\(file.lastPathComponent) doesn't decode: \(error)")
            }
        }
        #expect(read > 0)
    }

    typealias Reader = (Data) throws -> [TracksideSong]

    static func reader(for fields: [String: Any]) -> Reader? {
        func read<Value: Decodable>(_ type: Value.Type, songs: @escaping (Value) -> [TracksideSong] = { _ in [] }) -> Reader {
            { data in songs(try TracksideJSON.decoder.decode(type, from: data)) }
        }
        let keys = Set(fields.keys)
        let has = { (key: String) in keys.contains(key) }
        if has("error") { return read(TracksideErrorEnvelope.self) }
        if has("product") { return read(TracksideCapabilities.self) }
        if has("mode"), has("url") { return read(TracksidePlaybackDecision.self) }
        if has("mode") { return read(TracksideSyncPage.self) { ($0.songs ?? []) + ($0.upserts?.songs ?? []) } }
        if has("api_key") { return read(TracksidePairResult.self) }
        if has("poll_secret") { return read(TracksidePairRequest.self) }
        if has("user"), has("server") { return read(TracksideMe.self) }
        if has("search_id") { return read(TracksideAcquisitionSearch.self) { $0.inLibrary ?? [] } }
        if has("token"), has("title") { return read(TracksideCandidate.self) }
        if has("token"), has("expires_at") { return read(TracksideMediaToken.self) }
        if has("server_id"), has("urls") { return read(TracksideServerURLs.self) }
        if has("server_id"), has("time") { return read(TracksidePing.self) }
        if has("state"), has("title"), has("artist") { return read(TracksideAcquisition.self) }
        if has("stream"), has("title"), has("artist") { return read(TracksideExternalPlay.self) }
        if has("acquisitions") { return read(TracksideAcquisitionList.self) }
        if has("state"), keys.isSubset(of: ["state", "result"]) { return read(TracksidePairPoll.self) }
        if has("album"), has("songs") { return read(TracksideAlbumPage.self) { $0.songs } }
        if has("artist"), has("albums") || has("top_songs") { return read(TracksideArtistPage.self) { $0.topSongs ?? [] } }
        if has("lyrics") { return read(TracksideLyrics.self) }
        if has("jobs") { return read(TracksidePrepareResponse.self) }
        if has("results") { return read(TracksideListensResponse.self) }
        // A song has genres too, so its key is looked for first.
        if has("song_key") { return read(TracksideSong.self) { [$0] } }
        if has("genres"), fields["genres"] is [Any] { return read(TracksideGenres.self) }
        if let songs = fields["songs"] as? [String: Any], songs["items"] != nil || fields["albums"] is [String: Any] {
            return read(TracksideSearchResults.self) { $0.songs?.items ?? [] }
        }
        if fields["songs"] is [Any], keys.isSubset(of: ["songs"]) { return read(TracksideSongList.self) { $0.songs } }
        if fields["albums"] is [Any], keys.isSubset(of: ["albums", "next_cursor"]) { return read(TracksideAlbumList.self) }
        if let artists = fields["artists"] as? [[String: Any]], keys.isSubset(of: ["artists", "next_cursor"]) {
            return artists.contains { $0["score"] != nil || $0["in_library"] != nil } ? read(TracksideSimilarArtists.self) : read(TracksideArtistList.self)
        }
        if has("album_key") { return read(TracksideAlbum.self) }
        if has("revision"), has("owner") { return read(TracksidePlaylist.self) }
        if has("identity"), has("name"), has("id") { return read(TracksideArtist.self) }
        return nil
    }
}
