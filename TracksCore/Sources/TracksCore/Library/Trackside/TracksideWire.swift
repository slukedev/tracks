import Foundation

// What Trackside's native API sends and takes, as tracks-companion's `docs/api.md` writes it.
// Fields the server leaves out are nil; fields and values this build doesn't know are ignored,
// so a newer server still reads. The shapes that need nothing but Foundation are in
// TracksideWireCore.swift.

// MARK: - Shared objects

public struct TracksideSong: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var libraryID: String?
    public var title: String
    public var artist: String
    public var artistID: String?
    public var artists: [TracksideArtistCredit]?
    public var album: String?
    public var albumID: String?
    public var albumArtist: String?
    public var trackNumber: Int?
    public var discNumber: Int?
    public var year: Int?
    public var genres: [String]?
    public var durationMS: Int?
    public var format: TracksideFormat?
    public var artworkID: String?
    /// Exactly what Tracks computes as ``LocalTrack/identity`` for the same title and artist.
    public var songKey: String?
    public var replayGain: TracksideReplayGain?
    public var mbz: [String: String]?
    public var isrc: String?
    public var explicit: String?
    public var hasLyrics: Bool?
    public var addedAt: Date?
    public var updatedAt: Date?
    /// The file is gone but the server hasn't given up on it yet. It can't be played.
    public var missing: Bool?
    /// The caller's own stars, rating and plays, in browse answers.
    public var me: TracksideUserData?

    public init(id: String, title: String, artist: String, album: String? = nil, albumID: String? = nil, durationMS: Int? = nil, artworkID: String? = nil, addedAt: Date? = nil) {
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.albumID = albumID
        self.durationMS = durationMS
        self.artworkID = artworkID
        self.addedAt = addedAt
    }

    enum CodingKeys: String, CodingKey {
        case id, title, artist, artists, album, year, genres, format, mbz, isrc, explicit, missing, me
        case libraryID = "library_id"
        case artistID = "artist_id"
        case albumID = "album_id"
        case albumArtist = "album_artist"
        case trackNumber = "track_number"
        case discNumber = "disc_number"
        case durationMS = "duration_ms"
        case artworkID = "artwork_id"
        case songKey = "song_key"
        case replayGain = "replay_gain"
        case hasLyrics = "has_lyrics"
        case addedAt = "added_at"
        case updatedAt = "updated_at"
    }
}

public struct TracksideArtistCredit: Codable, Sendable, Hashable {
    public var id: String?
    public var name: String
    /// "main", "featured" and the like.
    public var role: String?
}

/// How a file is encoded, as the server read it.
public struct TracksideFormat: Codable, Sendable, Hashable {
    public var container: String
    public var codec: String
    public var contentType: String?
    public var lossless: Bool?
    public var bitrateKbps: Int?
    public var sampleRate: Int?
    public var bitDepth: Int?
    public var channels: Int?
    public var size: Int?

    public init(container: String, codec: String, lossless: Bool? = nil, bitrateKbps: Int? = nil, sampleRate: Int? = nil, bitDepth: Int? = nil) {
        self.container = container
        self.codec = codec
        self.lossless = lossless
        self.bitrateKbps = bitrateKbps
        self.sampleRate = sampleRate
        self.bitDepth = bitDepth
    }

    enum CodingKeys: String, CodingKey {
        case container, codec, lossless, channels, size
        case contentType = "content_type"
        case bitrateKbps = "bitrate_kbps"
        case sampleRate = "sample_rate"
        case bitDepth = "bit_depth"
    }
}

public struct TracksideReplayGain: Codable, Sendable, Hashable {
    public var trackGain: Double?
    public var trackPeak: Double?
    public var albumGain: Double?
    public var albumPeak: Double?

    enum CodingKeys: String, CodingKey {
        case trackGain = "track_gain"
        case trackPeak = "track_peak"
        case albumGain = "album_gain"
        case albumPeak = "album_peak"
    }
}

public struct TracksideUserData: Codable, Sendable, Hashable {
    public var starredAt: Date?
    public var rating: Int?
    public var playCount: Int?
    public var lastPlayedAt: Date?

    enum CodingKeys: String, CodingKey {
        case rating
        case starredAt = "starred_at"
        case playCount = "play_count"
        case lastPlayedAt = "last_played_at"
    }
}

public struct TracksideAlbum: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var libraryID: String?
    public var name: String
    public var albumArtist: String?
    public var artistID: String?
    public var year: Int?
    /// "2008-11-12", or just the year or month where that's all the tags say.
    public var releaseDate: String?
    public var genres: [String]?
    public var isCompilation: Bool?
    public var releaseType: String?
    public var songCount: Int?
    public var discCount: Int?
    public var durationMS: Int?
    public var artworkID: String?
    public var albumKey: String?
    public var mbz: [String: String]?
    public var addedAt: Date?
    public var updatedAt: Date?

    public init(id: String, name: String, albumArtist: String? = nil, artistID: String? = nil, year: Int? = nil, songCount: Int? = nil, artworkID: String? = nil) {
        self.id = id
        self.name = name
        self.albumArtist = albumArtist
        self.artistID = artistID
        self.year = year
        self.songCount = songCount
        self.artworkID = artworkID
    }

    enum CodingKeys: String, CodingKey {
        case id, name, year, genres, mbz
        case libraryID = "library_id"
        case albumArtist = "album_artist"
        case artistID = "artist_id"
        case releaseDate = "release_date"
        case isCompilation = "is_compilation"
        case releaseType = "release_type"
        case songCount = "song_count"
        case discCount = "disc_count"
        case durationMS = "duration_ms"
        case artworkID = "artwork_id"
        case albumKey = "album_key"
        case addedAt = "added_at"
        case updatedAt = "updated_at"
    }
}

public struct TracksideArtist: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    /// The artist's name folded, as Tracks folds names.
    public var identity: String?
    public var albumCount: Int?
    public var songCount: Int?
    public var artworkID: String?
    public var mbz: [String: String]?

    public init(id: String, name: String, identity: String? = nil, albumCount: Int? = nil, artworkID: String? = nil) {
        self.id = id
        self.name = name
        self.identity = identity
        self.albumCount = albumCount
        self.artworkID = artworkID
    }

    enum CodingKeys: String, CodingKey {
        case id, name, identity, mbz
        case albumCount = "album_count"
        case songCount = "song_count"
        case artworkID = "artwork_id"
    }
}

public struct TracksidePlaylist: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var comment: String?
    public var owner: Owner?
    public var isPublic: Bool?
    public var songCount: Int?
    public var durationMS: Int?
    public var revision: Int?
    public var createdAt: Date?
    public var updatedAt: Date?

    public struct Owner: Codable, Sendable, Hashable {
        public var id: String
        public var displayName: String?

        enum CodingKeys: String, CodingKey {
            case id
            case displayName = "display_name"
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, name, comment, owner, revision
        case isPublic = "is_public"
        case songCount = "song_count"
        case durationMS = "duration_ms"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public struct TracksideLibrary: Codable, Sendable, Hashable, Identifiable {
    public enum State: String, TracksideOpenEnum {
        case ok, unavailable, unknown
    }

    public var id: String
    public var name: String
    public var state: State
    public var songCount: Int?

    enum CodingKeys: String, CodingKey {
        case id, name, state
        case songCount = "song_count"
    }
}

// MARK: - Signing in and pairing

public struct TracksideDevice: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
}

public struct TracksideUser: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var username: String
    public var displayName: String?
    /// "admin" or "member".
    public var role: String?
    public var permissions: Permissions?

    public struct Permissions: Codable, Sendable, Hashable {
        public var canDownload: Bool?
        public var canUpload: Bool?
        public var legacySubsonic: Bool?
        /// Nil when unlimited.
        public var maxBitrateKbps: Int?

        enum CodingKeys: String, CodingKey {
            case canDownload = "can_download"
            case canUpload = "can_upload"
            case legacySubsonic = "legacy_subsonic"
            case maxBitrateKbps = "max_bitrate_kbps"
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, username, role, permissions
        case displayName = "display_name"
    }
}

/// What media addresses carry as `mt`, since AVPlayer can't be relied on to send headers.
public struct TracksideMediaToken: Codable, Sendable, Hashable {
    public var token: String
    public var expiresAt: Date

    public init(token: String, expiresAt: Date) {
        self.token = token
        self.expiresAt = expiresAt
    }

    /// A day before it runs out, so a song started late at night doesn't stop partway.
    public func needsRenewal(at now: Date = .now) -> Bool {
        expiresAt.timeIntervalSince(now) < 24 * 60 * 60
    }

    enum CodingKeys: String, CodingKey {
        case token
        case expiresAt = "expires_at"
    }
}

/// What every way of signing in ends with: a key for this device, and where the server is.
public struct TracksidePairResult: Codable, Sendable, Hashable {
    public var apiKey: String
    public var device: TracksideDevice
    public var user: TracksideUser
    public var mediaToken: TracksideMediaToken?
    public var server: TracksideServerInfo

    enum CodingKeys: String, CodingKey {
        case device, user, server
        case apiKey = "api_key"
        case mediaToken = "media_token"
    }
}

struct TracksideLoginRequest: Encodable {
    let username: String
    let password: String
    let device: TracksideDeviceInfo
}

struct TracksideClaimRequest: Encodable {
    let code: String
    let device: TracksideDeviceInfo
}

struct TracksidePairRequestBody: Encodable {
    let device: TracksideDeviceInfo
}

/// A pairing this device asked for: show ``code`` and poll until someone approves it in the
/// server's dashboard.
public struct TracksidePairRequest: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    /// Six digits, as the dashboard asks for them: "481 207".
    public var code: String
    public var pollSecret: String
    public var expiresAt: Date
    public var pollIntervalMS: Int?

    enum CodingKeys: String, CodingKey {
        case id, code
        case pollSecret = "poll_secret"
        case expiresAt = "expires_at"
        case pollIntervalMS = "poll_interval_ms"
    }
}

public struct TracksidePairPoll: Codable, Sendable, Hashable {
    public enum State: String, TracksideOpenEnum {
        case pending, approved, expired, unknown
    }

    public var state: State
    /// Given once, with `approved`.
    public var result: TracksidePairResult?
}

// MARK: - Linking a TV

struct TracksideLinkStartBody: Encodable {
    let device: TracksideDeviceInfo
}

struct TracksideLinkPollBody: Encodable {
    let deviceCode: String

    enum CodingKeys: String, CodingKey {
        case deviceCode = "device_code"
    }
}

struct TracksideLinkCodeBody: Encodable {
    let userCode: String

    enum CodingKeys: String, CodingKey {
        case userCode = "user_code"
    }
}

/// A link a TV asked for. Only the TV holds ``deviceCode``.
public struct TracksideLinkStart: Codable, Sendable, Hashable {
    public var deviceCode: String
    /// "WDJB-MJHT".
    public var userCode: String
    /// Usually an https address, but a bare path (`/link?code=...`) when the server knows
    /// none of its own: see ``verificationURL(against:)``.
    public var verificationURL: String
    /// `tracks://link?server=<server_id>&code=<user_code>`, for the QR code.
    public var linkURL: String
    public var intervalS: Int
    public var expiresAt: Date

    enum CodingKeys: String, CodingKey {
        case deviceCode = "device_code"
        case userCode = "user_code"
        case verificationURL = "verification_url"
        case linkURL = "link_url"
        case intervalS = "interval_s"
        case expiresAt = "expires_at"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceCode = try container.decode(String.self, forKey: .deviceCode)
        userCode = try container.decode(String.self, forKey: .userCode)
        verificationURL = (try? container.decodeIfPresent(String.self, forKey: .verificationURL)) ?? ""
        linkURL = (try? container.decodeIfPresent(String.self, forKey: .linkURL)) ?? ""
        intervalS = (try? container.decodeIfPresent(Int.self, forKey: .intervalS)) ?? 5
        expiresAt = try container.decode(Date.self, forKey: .expiresAt)
    }

    /// The verification address, resolved against the address the link was started at when
    /// the server gave only a path.
    public func verificationURL(against base: URL) -> URL? {
        verificationURL.isEmpty ? nil : TracksideClient.resolve(verificationURL, against: base)
    }
}

extension TracksideLinkStart: CustomStringConvertible {
    /// Without the device code, which is the TV's secret.
    public var description: String { "TracksideLinkStart(userCode: \(userCode), expiresAt: \(expiresAt))" }
}

/// What a code would link, for the person to confirm.
public struct TracksideLinkInfo: Codable, Sendable, Hashable {
    public enum State: String, TracksideOpenEnum {
        case pending, approved, denied, unknown
    }

    public struct Device: Codable, Sendable, Hashable {
        public var name: String
        /// `android_tv`, `ios`, `macos`, `android`, `web` or `other`.
        public var platform: String
        public var model: String?
        public var appName: String?

        enum CodingKeys: String, CodingKey {
            case name, platform, model
            case appName = "app_name"
        }

        public init(name: String, platform: String = "other", model: String? = nil, appName: String? = nil) {
            self.name = name
            self.platform = platform
            self.model = model
            self.appName = appName
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
            platform = (try? container.decodeIfPresent(String.self, forKey: .platform)) ?? "other"
            model = try? container.decodeIfPresent(String.self, forKey: .model)
            appName = try? container.decodeIfPresent(String.self, forKey: .appName)
        }
    }

    public var state: State
    public var device: Device
    /// False: asked from outside the home network. Warn before approving.
    public var nearby: Bool
    public var requestedAt: Date?
    public var expiresAt: Date?

    enum CodingKeys: String, CodingKey {
        case state, device, nearby
        case requestedAt = "requested_at"
        case expiresAt = "expires_at"
    }

    public init(state: State, device: Device, nearby: Bool, requestedAt: Date? = nil, expiresAt: Date? = nil) {
        self.state = state
        self.device = device
        self.nearby = nearby
        self.requestedAt = requestedAt
        self.expiresAt = expiresAt
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = (try? container.decodeIfPresent(State.self, forKey: .state)) ?? .unknown
        device = try container.decode(Device.self, forKey: .device)
        // Not said is not nearby: the warning shows rather than goes missing.
        nearby = (try? container.decodeIfPresent(Bool.self, forKey: .nearby)) ?? false
        requestedAt = try? container.decodeIfPresent(Date.self, forKey: .requestedAt)
        expiresAt = try? container.decodeIfPresent(Date.self, forKey: .expiresAt)
    }
}

/// One poll's answer. Only ``linked(_:)`` carries credentials, and only once.
public enum TracksideLinkPoll: Sendable, Hashable {
    case linked(TracksidePairResult)
    case pending
    /// Poll at `interval` from now on.
    case slowDown(interval: Duration)
    case denied
    /// Expired, already collected or unknown: start a new link.
    case expired
}

/// The `device_linked` event: a TV this person approved collected its key.
public struct TracksideDeviceLinked: Codable, Sendable, Hashable {
    public var id: String
    public var name: String
    public var platform: String

    enum CodingKeys: String, CodingKey {
        case id, name, platform
    }

    public init(id: String, name: String, platform: String) {
        self.id = id
        self.name = name
        self.platform = platform
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        platform = (try? container.decodeIfPresent(String.self, forKey: .platform)) ?? "other"
    }
}

// MARK: - The signed-in user

/// `GET /api/v1/me`.
public struct TracksideMe: Codable, Sendable, Hashable {
    public var user: TracksideUser
    public var libraries: [TracksideLibrary]?
    public var device: TracksideDevice?
    public var server: TracksideServerInfo
    public var features: Features?

    public struct Features: Codable, Sendable, Hashable {
        /// Present only when this user can ask the server to get songs it doesn't have.
        public var acquisition: Acquisition?
        /// Present when this user and key may see the server's speakers.
        public var speakers: Speakers?
        /// Present when this user may play from the server's music services.
        public var catalog: Catalog?

        public init(acquisition: Acquisition? = nil, speakers: Speakers? = nil, catalog: Catalog? = nil) {
            self.acquisition = acquisition
            self.speakers = speakers
            self.catalog = catalog
        }

        enum CodingKeys: String, CodingKey {
            case acquisition, speakers, catalog
        }

        /// One feature in a shape this build doesn't expect never hides the others.
        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            acquisition = try? container.decodeIfPresent(Acquisition.self, forKey: .acquisition)
            speakers = try? container.decodeIfPresent(Speakers.self, forKey: .speakers)
            catalog = try? container.decodeIfPresent(Catalog.self, forKey: .catalog)
        }
    }

    public struct Speakers: Codable, Sendable, Hashable {
        /// The key may control them too, not just see them.
        public var control: Bool?

        public init(control: Bool? = nil) {
            self.control = control
        }
    }

    public struct Catalog: Codable, Sendable, Hashable {
        /// This device can play the services itself, through the server.
        public var relay: Bool?

        public init(relay: Bool? = nil) {
            self.relay = relay
        }
    }

    public struct Acquisition: Codable, Sendable, Hashable {
        public var instantPlay: Bool?
        /// A song the server doesn't have can be played without being kept.
        public var externalPlay: Bool?

        public init(instantPlay: Bool? = nil, externalPlay: Bool? = nil) {
            self.instantPlay = instantPlay
            self.externalPlay = externalPlay
        }

        enum CodingKeys: String, CodingKey {
            case instantPlay = "instant_play"
            case externalPlay = "external_play"
        }
    }
}

// MARK: - Browsing and search

public struct TracksideAlbumPage: Codable, Sendable, Hashable {
    public var album: TracksideAlbum
    /// By disc and track.
    public var songs: [TracksideSong]
}

public struct TracksideArtistPage: Codable, Sendable, Hashable {
    public var artist: TracksideArtist
    public var albums: [TracksideAlbum]?
    public var appearsOn: [TracksideAlbum]?
    public var topSongs: [TracksideSong]?

    enum CodingKeys: String, CodingKey {
        case artist, albums
        case appearsOn = "appears_on"
        case topSongs = "top_songs"
    }
}

struct TracksideSongList: Codable, Sendable {
    let songs: [TracksideSong]
}

/// A song Last.fm suggests that the server doesn't have.
public struct TracksideSuggestion: Codable, Sendable, Hashable {
    public var title: String
    public var artist: String
}

/// `GET /me/picks` and `GET /me/get-more`: songs the server has, and ones it suggests that it doesn't.
public struct TracksideSuggestedSongs: Codable, Sendable, Hashable {
    public var songs: [TracksideSong]
    public var external: [TracksideSuggestion]?
}

struct TracksideAlbumList: Codable, Sendable {
    let albums: [TracksideAlbum]
    let nextCursor: String?

    enum CodingKeys: String, CodingKey {
        case albums
        case nextCursor = "next_cursor"
    }
}

struct TracksideArtistList: Codable, Sendable {
    let artists: [TracksideArtist]
    let nextCursor: String?

    enum CodingKeys: String, CodingKey {
        case artists
        case nextCursor = "next_cursor"
    }
}

/// One page of a list, and where the next starts.
public struct TracksidePage<Item: Sendable & Hashable>: Sendable, Hashable {
    public var items: [Item]
    public var nextCursor: String?
}

/// An artist like another: one the server has, or one only known by name.
public struct TracksideSimilarArtist: Codable, Sendable, Hashable {
    public var artist: TracksideArtist?
    public var name: String
    public var identity: String?
    public var score: Double?
    public var inLibrary: Bool?

    enum CodingKeys: String, CodingKey {
        case artist, name, identity, score
        case inLibrary = "in_library"
    }
}

struct TracksideSimilarArtists: Codable, Sendable {
    let artists: [TracksideSimilarArtist]
}

public struct TracksideGenre: Codable, Sendable, Hashable {
    public var name: String
    public var songCount: Int?
    public var albumCount: Int?

    enum CodingKeys: String, CodingKey {
        case name
        case songCount = "song_count"
        case albumCount = "album_count"
    }
}

struct TracksideGenres: Codable, Sendable {
    let genres: [TracksideGenre]
}

/// `GET /api/v1/search`.
public struct TracksideSearchResults: Codable, Sendable, Hashable {
    public var songs: Section<TracksideSong>?
    public var albums: Section<TracksideAlbum>?
    public var artists: Section<TracksideArtist>?

    public struct Section<Item: Codable & Sendable & Hashable>: Codable, Sendable, Hashable {
        public var items: [Item]
        public var nextCursor: String?

        enum CodingKeys: String, CodingKey {
            case items
            case nextCursor = "next_cursor"
        }
    }
}

public struct TracksideLyrics: Codable, Sendable, Hashable {
    public var lyrics: [Entry]

    /// One telling of the words: synced to the song, or plain.
    public struct Entry: Codable, Sendable, Hashable {
        /// "synced" or "plain".
        public var kind: String
        public var lang: String?
        public var source: String?
        public var offsetMS: Int?
        public var lines: [Line]

        enum CodingKeys: String, CodingKey {
            case kind, lang, source, lines
            case offsetMS = "offset_ms"
        }
    }

    public struct Line: Codable, Sendable, Hashable {
        public var startMS: Int?
        public var text: String

        enum CodingKeys: String, CodingKey {
            case text
            case startMS = "start_ms"
        }
    }
}

// MARK: - Sync

/// One page of `GET /api/v1/sync`: part of a snapshot, or the changes since a cursor.
public struct TracksideSyncPage: Codable, Sendable, Hashable {
    public enum Mode: String, TracksideOpenEnum {
        case snapshot, delta, unknown
    }

    public var mode: Mode
    public var snapshot: Snapshot?
    /// The last page of a snapshot. Its ``nextCursor`` is the cursor for changes after it.
    public var complete: Bool?

    // A snapshot page holds one kind of these.
    public var libraries: [TracksideLibrary]?
    public var artists: [TracksideArtist]?
    public var albums: [TracksideAlbum]?
    public var songs: [TracksideSong]?
    public var playlists: [TracksidePlaylistWithEntries]?
    public var stars: [TracksideStar]?
    public var ratings: [TracksideRating]?
    public var playStats: [TracksidePlayStat]?
    public var playQueue: TracksidePlayQueue?

    // A delta page holds these.
    public var upserts: Upserts?
    public var deletes: Deletes?
    public var hasMore: Bool?

    public var nextCursor: String?

    public init(mode: Mode, nextCursor: String?) {
        self.mode = mode
        self.nextCursor = nextCursor
    }

    public struct Snapshot: Codable, Sendable, Hashable {
        public var seq: Int64?
        public var kind: String?
        public var next: String?
    }

    public struct Upserts: Codable, Sendable, Hashable {
        public var songs: [TracksideSong]?
        public var albums: [TracksideAlbum]?
        public var artists: [TracksideArtist]?
        public var libraries: [TracksideLibrary]?
        public var playlists: [TracksidePlaylistWithEntries]?
        public var stars: [TracksideStar]?
        public var ratings: [TracksideRating]?
        public var playStats: [TracksidePlayStat]?
        public var playQueue: TracksidePlayQueue?

        public init(songs: [TracksideSong]? = nil, artists: [TracksideArtist]? = nil) {
            self.songs = songs
            self.artists = artists
        }

        enum CodingKeys: String, CodingKey {
            case songs, albums, artists, libraries, playlists, stars, ratings
            case playStats = "play_stats"
            case playQueue = "play_queue"
        }
    }

    public struct Deletes: Codable, Sendable, Hashable {
        public var songs: [String]?
        public var albums: [String]?
        public var artists: [String]?
        public var libraries: [String]?
        public var playlists: [String]?
        public var stars: [TracksideItemRef]?
        public var ratings: [TracksideItemRef]?

        public init(songs: [String]? = nil, artists: [String]? = nil) {
            self.songs = songs
            self.artists = artists
        }
    }

    enum CodingKeys: String, CodingKey {
        case mode, snapshot, complete, libraries, artists, albums, songs, playlists, stars, ratings, upserts, deletes
        case playStats = "play_stats"
        case playQueue = "play_queue"
        case hasMore = "has_more"
        case nextCursor = "next_cursor"
    }
}

public struct TracksidePlaylistWithEntries: Codable, Sendable, Hashable {
    public var playlist: TracksidePlaylist
    /// Song ids, in order.
    public var entries: [String]?
}

public enum TracksideItemKind: String, TracksideOpenEnum {
    case song, album, artist, unknown
}

public struct TracksideItemRef: Codable, Sendable, Hashable {
    public var kind: TracksideItemKind
    public var id: String
}

public struct TracksideStar: Codable, Sendable, Hashable {
    public var kind: TracksideItemKind
    public var id: String
    public var starredAt: Date?

    enum CodingKeys: String, CodingKey {
        case kind, id
        case starredAt = "starred_at"
    }
}

public struct TracksideRating: Codable, Sendable, Hashable {
    public var kind: TracksideItemKind
    public var id: String
    public var rating: Int
}

public struct TracksidePlayStat: Codable, Sendable, Hashable {
    public var id: String
    public var playCount: Int?
    public var lastPlayedAt: Date?

    enum CodingKeys: String, CodingKey {
        case id
        case playCount = "play_count"
        case lastPlayedAt = "last_played_at"
    }
}

public struct TracksidePlayQueue: Codable, Sendable, Hashable {
    public var entries: [String]
    public var current: Int?
    public var positionMS: Int?
    public var changedBy: String?
    public var deviceID: String?
    public var updatedAt: Date?

    enum CodingKeys: String, CodingKey {
        case entries, current
        case positionMS = "position_ms"
        case changedBy = "changed_by"
        case deviceID = "device_id"
        case updatedAt = "updated_at"
    }
}

// MARK: - Playback

/// `POST /api/v1/playback/decisions`: what this device can play, so the server picks the
/// original, a cached transcode or HLS.
public struct TracksidePlaybackRequest: Codable, Sendable, Hashable {
    public enum Network: String, TracksideOpenEnum {
        case wifi, wired, cellular, unknown
    }

    public var songID: String
    public var network: Network
    public var client: Client

    public init(songID: String, network: Network, client: Client) {
        self.songID = songID
        self.network = network
        self.client = client
    }

    public struct Client: Codable, Sendable, Hashable {
        public var direct: [Direct]
        public var hls: Bool
        public var maxBitrateKbps: Int?
        public var preferredProfile: String?
        public var allowOriginalFallback: Bool

        public init(direct: [Direct], hls: Bool, maxBitrateKbps: Int?, preferredProfile: String?, allowOriginalFallback: Bool) {
            self.direct = direct
            self.hls = hls
            self.maxBitrateKbps = maxBitrateKbps
            self.preferredProfile = preferredProfile
            self.allowOriginalFallback = allowOriginalFallback
        }

        enum CodingKeys: String, CodingKey {
            case direct, hls
            case maxBitrateKbps = "max_bitrate_kbps"
            case preferredProfile = "preferred_profile"
            case allowOriginalFallback = "allow_original_fallback"
        }
    }

    public struct Direct: Codable, Sendable, Hashable {
        public var container: String
        public var codecs: [String]

        public init(container: String, codecs: [String]) {
            self.container = container
            self.codecs = codecs
        }
    }

    enum CodingKeys: String, CodingKey {
        case network, client
        case songID = "song_id"
    }
}

public struct TracksidePlaybackDecision: Codable, Sendable, Hashable {
    public enum Mode: String, TracksideOpenEnum {
        case direct, file, hls, unknown
    }

    public var mode: Mode
    /// Relative to the address the request went to. ``TracksideClient/mediaURL(_:)`` resolves it.
    public var url: String
    public var delivered: Delivered?
    /// Why it isn't the original: "over_bitrate", "transcoder_busy" and the like.
    public var reason: String?
    public var etag: String?

    public struct Delivered: Codable, Sendable, Hashable {
        public var container: String?
        public var codec: String?
        public var bitrateKbps: Int?
        public var sampleRate: Int?
        public var bitDepth: Int?
        public var lossless: Bool?
        public var size: Int?
        public var durationMS: Int?

        enum CodingKeys: String, CodingKey {
            case container, codec, lossless, size
            case bitrateKbps = "bitrate_kbps"
            case sampleRate = "sample_rate"
            case bitDepth = "bit_depth"
            case durationMS = "duration_ms"
        }
    }
}

struct TracksidePrepareRequest: Codable, Sendable {
    let songs: [String]
    let profile: String
}

public struct TracksidePreparedJob: Codable, Sendable, Hashable {
    public enum State: String, TracksideOpenEnum {
        case cached, queued, running, unknown
    }

    public var songID: String
    public var state: State

    enum CodingKeys: String, CodingKey {
        case state
        case songID = "song_id"
    }
}

struct TracksidePrepareResponse: Codable, Sendable {
    let jobs: [TracksidePreparedJob]
}

// MARK: - Listens, now playing, stars

struct TracksideListensRequest: Codable, Sendable {
    let clientTime: Date
    let listens: [Item]

    struct Item: Codable, Sendable {
        let clientListenID: String
        let songID: String?
        let startedAt: Date
        let playedMS: Int
        let title: String
        let artist: String
        let album: String?
        let durationMS: Int?

        enum CodingKeys: String, CodingKey {
            case title, artist, album
            case clientListenID = "client_listen_id"
            case songID = "song_id"
            case startedAt = "started_at"
            case playedMS = "played_ms"
            case durationMS = "duration_ms"
        }
    }

    enum CodingKeys: String, CodingKey {
        case listens
        case clientTime = "client_time"
    }
}

struct TracksideListensResponse: Codable, Sendable {
    let results: [Result]

    struct Result: Codable, Sendable {
        let clientListenID: String
        let status: ServerListenStatus
        let reason: String?

        enum CodingKeys: String, CodingKey {
            case status, reason
            case clientListenID = "client_listen_id"
        }
    }
}

struct TracksideNowPlayingRequest: Codable, Sendable {
    let songID: String
    let positionMS: Int
    let state: String

    enum CodingKeys: String, CodingKey {
        case state
        case songID = "song_id"
        case positionMS = "position_ms"
    }
}

struct TracksideStarRequest: Codable, Sendable {
    let at: Date
}

struct TracksideRatingRequest: Codable, Sendable {
    let rating: Int
}

// MARK: - As the rest of Tracks knows songs

extension TracksideFormat {
    /// "FLAC · 16-bit/44.1 kHz" and the like. WAV and AIFF carry PCM, so those are named by
    /// their container.
    public var audioFormat: AudioFormat {
        let name = codec.lowercased().hasPrefix("pcm") ? container : codec
        return AudioFormat(codec: AudioFormat.codec(forExtension: name), sampleRate: sampleRate, bitDepth: bitDepth, bitRate: bitrateKbps)
    }
}

extension TracksideSong {
    /// The song as one of your own, on the Tracks server entry `serverID`.
    public func track(on serverID: String) -> LocalTrack {
        LocalTrack(
            origin: .server(serverID: serverID, songID: id),
            title: title,
            artist: artist,
            albumArtist: albumArtist,
            album: album,
            trackNumber: trackNumber,
            discNumber: discNumber,
            year: year,
            genre: genres?.first,
            duration: durationMS.map { TimeInterval($0) / 1_000 },
            format: format?.audioFormat,
            artwork: artworkID.map { .server(serverID: serverID, coverID: $0) },
            addedAt: addedAt ?? .distantPast
        )
    }

    /// The song in the shape the rest of Tracks reads server songs in.
    public var subsonicSong: SubsonicSong {
        var song = SubsonicSong(
            id: id,
            title: title,
            artist: artist,
            album: album,
            albumId: albumID,
            track: trackNumber,
            duration: durationMS.map { Int(($0 + 500) / 1_000) },
            suffix: format?.container,
            coverArt: artworkID
        )
        song.artistId = artistID
        song.discNumber = discNumber
        song.year = year
        song.genre = genres?.first
        song.contentType = format?.contentType
        song.bitRate = format?.bitrateKbps
        song.size = format?.size
        song.samplingRate = format?.sampleRate
        song.bitDepth = format?.bitDepth
        song.displayAlbumArtist = albumArtist
        song.created = addedAt.map(TracksideJSON.string(from:))
        return song
    }
}

extension TracksideAlbum {
    public var subsonicAlbum: SubsonicAlbum {
        var album = SubsonicAlbum(id: id, name: name, artist: albumArtist, artistId: artistID, coverArt: artworkID, songCount: songCount, year: year, genre: genres?.first)
        album.duration = durationMS.map { Int(($0 + 500) / 1_000) }
        album.created = addedAt.map(TracksideJSON.string(from:))
        return album
    }
}

extension TracksideArtist {
    public var subsonicArtist: SubsonicArtist {
        SubsonicArtist(id: id, name: name, albumCount: albumCount, coverArt: artworkID)
    }
}
