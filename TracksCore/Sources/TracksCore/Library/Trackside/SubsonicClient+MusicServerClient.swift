import Foundation

extension SubsonicClient: MusicServerClient {
    public var serverID: String { server.id }

    /// How many of the library's own matches a server that finds songs elsewhere puts on a
    /// search's first page before the songs it found. A first page holding that many may have
    /// more behind it.
    static let foundElsewhereLibraryShare = 12

    public func search(_ query: String, artists: Int, albums: Int, songs: Int, after: MusicSearchPosition?) async throws -> MusicSearchResult {
        let offset = if case .offset(let offset) = after { offset } else { 0 }
        let found = try await search(query, artists: artists, albums: albums, songs: songs, songOffset: offset)
        let library = found.songs.count(where: { $0.isExternal != true })
        let foundElsewhere = library < found.songs.count
        let mayHaveMore = songs > 0 && (library == songs || (offset == 0 && foundElsewhere && library >= Self.foundElsewhereLibraryShare))
        return MusicSearchResult(artists: found.artists, albums: found.albums, songs: found.songs, next: mayHaveMore ? .offset(offset + library) : nil)
    }

    public func topSongs(artist name: String, artistID: String?, count: Int) async throws -> [SubsonicSong] {
        try await topSongs(artist: name, count: count)
    }

    public func albumList(_ list: MusicAlbumList, count: Int) async throws -> [SubsonicAlbum] {
        let type: AlbumListType = switch list {
        case .newest: .newest
        case .recent: .recent
        case .frequent: .frequent
        case .random: .random
        case .highest: .highest
        case .alphabeticalByName: .alphabeticalByName
        case .starred: .starred
        }
        return try await albums(type, size: count)
    }

    public func stream(songID: String, maxBitRate: Int?, network: MusicNetwork) async throws -> MusicServerStream {
        MusicServerStream(url: streamURL(songID: songID, maxBitRate: maxBitRate))
    }

    public func downloadRequest(songID: String, maxBitRate: Int?) async throws -> URLRequest {
        URLRequest(url: maxBitRate.map { streamURL(songID: songID, maxBitRate: $0) } ?? downloadURL(songID: songID))
    }

    public func artworkURL(id: String, size: Int?) -> URL {
        coverArtURL(id: id, size: size)
    }

    public func nowPlaying(songID: String) async throws {
        try await scrobble(songID: songID, submission: false)
    }

    /// Subsonic takes one play at a time, and has no way to send one without its song. Plays
    /// sent before one fails are answered for, so they aren't sent twice. A song the server
    /// no longer has is let go of, so it can't hold back the plays behind it for good.
    public func submitListens(_ listens: [ServerListen]) async throws -> [String: ServerListenStatus] {
        var results: [String: ServerListenStatus] = [:]
        for listen in listens {
            guard let songID = listen.songID else {
                results[listen.id] = .rejected
                continue
            }
            do {
                try await scrobble(songID: songID, at: listen.startedAt, submission: true)
            } catch SubsonicError.notFound {
                results[listen.id] = .rejected
                continue
            } catch {
                if results.isEmpty { throw error }
                break
            }
            results[listen.id] = .accepted
        }
        return results
    }
}
