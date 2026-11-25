import Foundation

/// What Tracks asks of any music server it's connected to, Subsonic or Trackside, so the
/// screens and the player don't need to know which one it is.
///
/// Answers come in the Subsonic shapes the rest of Tracks already reads server songs in.
public protocol MusicServerClient: Sendable {
    /// The Tracks server entry's id, which song and cover ids are filed under.
    var serverID: String { get }

    /// Checks the server can be reached and still knows this device.
    func ping() async throws

    /// Artists, albums and songs matching a query. `after` continues the songs from where an
    /// earlier page's ``MusicSearchResult/next`` left off.
    func search(_ query: String, artists: Int, albums: Int, songs: Int, after: MusicSearchPosition?) async throws -> MusicSearchResult

    func album(id: String) async throws -> (album: SubsonicAlbum, songs: [SubsonicSong])
    func artist(id: String) async throws -> (artist: SubsonicArtist, albums: [SubsonicAlbum])

    /// An artist's best known songs. Subsonic asks by name; Trackside by id where it's known.
    func topSongs(artist name: String, artistID: String?, count: Int) async throws -> [SubsonicSong]

    /// Songs like this one, from across the server.
    func similarSongs(to songID: String, count: Int) async throws -> [SubsonicSong]

    /// Artists like this one that the server has.
    func similarArtists(to artistID: String, count: Int) async throws -> [SubsonicArtist]

    func randomSongs(count: Int, genre: String?) async throws -> [SubsonicSong]

    /// The server's own picks for you, songs like the ones you play, or nil when it doesn't
    /// make any (Subsonic, or a Trackside server from before it did).
    func serverPicks(count: Int) async throws -> [SubsonicSong]?

    /// The first albums of a list, for shelves.
    func albumList(_ list: MusicAlbumList, count: Int) async throws -> [SubsonicAlbum]

    /// Where to play a song from.
    /// - Parameter maxBitRate: kilobits a second, or nil for the original file.
    func stream(songID: String, maxBitRate: Int?, network: MusicNetwork) async throws -> MusicServerStream

    /// A request for a background download, credentials included.
    /// - Parameter maxBitRate: kilobits a second, or nil for the original file.
    func downloadRequest(songID: String, maxBitRate: Int?) async throws -> URLRequest

    /// A cover's address, the same every time it's asked for while the connection stays the
    /// same, and without anything that signs in: it's handed to caches and the system.
    func artworkURL(id: String, size: Int?) -> URL

    /// The address signed for one fetch, if it's one of this server's; nil for any other.
    func authorize(_ url: URL) -> URL?

    func star(songID: String) async throws
    func unstar(songID: String) async throws

    /// Tells the server a song has started.
    func nowPlaying(songID: String) async throws

    /// Tells the server about songs that played. The answer says, by listen id, which it took.
    func submitListens(_ listens: [ServerListen]) async throws -> [String: ServerListenStatus]
}

extension MusicServerClient {
    public func serverPicks(count: Int) async throws -> [SubsonicSong]? { nil }
}

/// Where the next page of a search's songs starts: an offset on Subsonic, a cursor on
/// Trackside.
public enum MusicSearchPosition: Codable, Sendable, Hashable {
    case offset(Int)
    case cursor(String)
}

public struct MusicSearchResult: Sendable, Equatable {
    public var artists: [SubsonicArtist]
    public var albums: [SubsonicAlbum]
    public var songs: [SubsonicSong]
    /// Where more songs start, when there may be more.
    public var next: MusicSearchPosition?

    public init(artists: [SubsonicArtist] = [], albums: [SubsonicAlbum] = [], songs: [SubsonicSong] = [], next: MusicSearchPosition? = nil) {
        self.artists = artists
        self.albums = albums
        self.songs = songs
        self.next = next
    }
}

public enum MusicAlbumList: String, Sendable, CaseIterable {
    case newest, recent, frequent, random, highest, alphabeticalByName, starred
}

/// The connection a song will play over, which decides how much the server sends.
public enum MusicNetwork: String, Sendable, CaseIterable {
    case wifi, wired, cellular, unknown
}

/// A song's address to play, and what will come down it where the server says.
public struct MusicServerStream: Sendable, Equatable {
    public var url: URL
    /// What the server will send. Nil when it doesn't say, as Subsonic servers don't.
    public var delivered: AudioFormat?
    /// The server sends a copy made for this device rather than the file itself.
    public var isTranscoded: Bool?

    public init(url: URL, delivered: AudioFormat? = nil, isTranscoded: Bool? = nil) {
        self.url = url
        self.delivered = delivered
        self.isTranscoded = isTranscoded
    }
}
