import Foundation

// The music services a Trackside server plays from (contract section 2): what each is, what
// searching them finds, and the albums, artists and playlists behind a result. A ref names an
// item there; it's opaque, case-sensitive, and never built, parsed or changed here. Every
// field but a ref is read on its own, so a value in a shape this build doesn't know drops that
// value, never the item or the list.

/// One music service connected on the server: Spotify, Tidal and the like. Its name is the
/// server's to give; nothing here names a service.
public struct TracksideCatalogService: Codable, Sendable, Hashable, Identifiable {
    public enum Capability: String, TracksideOpenEnum {
        case search, library, playlists, similar, radio, unknown
        case topTracks = "top_tracks"
    }

    public enum Quality: String, TracksideOpenEnum {
        case lossless, high, standard, unknown
    }

    public struct Account: Codable, Sendable, Hashable {
        public enum State: String, TracksideOpenEnum {
            case ok, error, unavailable, unknown
            /// Signed in once, and has to be again where the services are set up.
            case signedOut = "signed_out"
        }

        public var state: State
        /// English, for logs only.
        public var detail: String?

        public init(state: State, detail: String? = nil) {
            self.state = state
            self.detail = detail
        }

        enum CodingKeys: String, CodingKey {
            case state, detail
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            state = (try? container.decodeIfPresent(State.self, forKey: .state)) ?? .unknown
            detail = try? container.decodeIfPresent(String.self, forKey: .detail)
        }
    }

    public var id: String
    public var name: String
    /// Which glyph to draw: the service's own key, an open string.
    public var logoKey: String?
    public var capabilities: Set<Capability>
    public var account: Account
    public var quality: Quality?

    /// The key Apple's own apps play natively, so its service is never offered twice.
    public static let nativeOnAppleKey = "apple_music"

    public init(id: String, name: String, logoKey: String? = nil, capabilities: Set<Capability> = [], account: Account = Account(state: .ok), quality: Quality? = nil) {
        self.id = id
        self.name = name
        self.logoKey = logoKey
        self.capabilities = capabilities
        self.account = account
        self.quality = quality
    }

    enum CodingKeys: String, CodingKey {
        case id, name, capabilities, account, quality
        case logoKey = "logo_key"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id).lowercased()
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        logoKey = try? container.decodeIfPresent(String.self, forKey: .logoKey)
        let listed = (try? container.decodeIfPresent([TracksideLenientElement<Capability>].self, forKey: .capabilities))?.compactMap(\.value) ?? []
        capabilities = Set(listed.filter { $0 != .unknown })
        account = (try? container.decodeIfPresent(Account.self, forKey: .account)) ?? Account(state: .unknown)
        quality = try? container.decodeIfPresent(Quality.self, forKey: .quality)
    }

    public func can(_ capability: Capability) -> Bool { capabilities.contains(capability) }

    public var isSignedIn: Bool { account.state == .ok }

    /// Searchable now: it searches, and its account works.
    public var isSearchable: Bool { isSignedIn && can(.search) }

    /// Apple Music, which iPhone and Mac play through MusicKit rather than the server.
    public var isNativeOnApple: Bool { logoKey?.lowercased() == Self.nativeOnAppleKey }
}

/// `GET /catalog/services`. Never refused for the services being out of reach: `state` says.
public struct TracksideCatalogServiceList: Codable, Sendable, Hashable {
    public var services: [TracksideCatalogService]
    public var state: TracksideSpeakerList.State
    public var since: Date?

    public init(services: [TracksideCatalogService], state: TracksideSpeakerList.State = .ok, since: Date? = nil) {
        self.services = services
        self.state = state
        self.since = since
    }

    enum CodingKeys: String, CodingKey {
        case services, state, since
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        services = (try? container.decodeIfPresent([TracksideLenientElement<TracksideCatalogService>].self, forKey: .services))?.compactMap(\.value) ?? []
        state = (try? container.decodeIfPresent(TracksideSpeakerList.State.self, forKey: .state)) ?? .unknown
        since = try? container.decodeIfPresent(Date.self, forKey: .since)
    }
}

/// An artist named on a catalog item, with their ref where the service gives one.
public struct TracksideCatalogCredit: Codable, Sendable, Hashable {
    public var name: String
    public var ref: String?

    public init(name: String, ref: String? = nil) {
        self.name = name
        self.ref = ref
    }

    enum CodingKeys: String, CodingKey {
        case name, ref
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        ref = try? container.decodeIfPresent(String.self, forKey: .ref)
    }
}

public struct TracksideCatalogTrack: Codable, Sendable, Hashable, Identifiable {
    public var ref: String
    public var serviceID: String
    public var title: String
    public var artist: String?
    public var artists: [TracksideCatalogCredit]
    public var album: String?
    public var albumRef: String?
    public var artworkID: String?
    public var durationMS: Int?
    public var explicit: String?
    public var isrc: String?
    public var trackNumber: Int?
    public var discNumber: Int?
    public var quality: TracksideCatalogService.Quality?
    /// The library song with the same key, when the caller can see one: the owned copy.
    public var songID: String?
    /// Byte for byte what Tracks makes of the title and artist.
    public var songKey: String?
    /// False only when the service says it can't play; absent means it can.
    public var playable: Bool?

    public var id: String { ref }

    public init(ref: String, serviceID: String, title: String, artist: String? = nil, artists: [TracksideCatalogCredit] = [], album: String? = nil, albumRef: String? = nil, artworkID: String? = nil, durationMS: Int? = nil, songID: String? = nil, songKey: String? = nil, playable: Bool? = nil) {
        self.ref = ref
        self.serviceID = serviceID
        self.title = title
        self.artist = artist
        self.artists = artists
        self.album = album
        self.albumRef = albumRef
        self.artworkID = artworkID
        self.durationMS = durationMS
        self.songID = songID
        self.songKey = songKey
        self.playable = playable
    }

    enum CodingKeys: String, CodingKey {
        case ref, title, artist, artists, album, explicit, isrc, quality, playable
        case serviceID = "service_id"
        case albumRef = "album_ref"
        case artworkID = "artwork_id"
        case durationMS = "duration_ms"
        case trackNumber = "track_number"
        case discNumber = "disc_number"
        case songID = "song_id"
        case songKey = "song_key"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        serviceID = ((try? container.decodeIfPresent(String.self, forKey: .serviceID)) ?? "").lowercased()
        title = (try? container.decodeIfPresent(String.self, forKey: .title)) ?? ""
        artist = try? container.decodeIfPresent(String.self, forKey: .artist)
        artists = (try? container.decodeIfPresent([TracksideLenientElement<TracksideCatalogCredit>].self, forKey: .artists))?.compactMap(\.value) ?? []
        album = try? container.decodeIfPresent(String.self, forKey: .album)
        albumRef = try? container.decodeIfPresent(String.self, forKey: .albumRef)
        artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
        durationMS = try? container.decodeIfPresent(Int.self, forKey: .durationMS)
        explicit = try? container.decodeIfPresent(String.self, forKey: .explicit)
        isrc = try? container.decodeIfPresent(String.self, forKey: .isrc)
        trackNumber = try? container.decodeIfPresent(Int.self, forKey: .trackNumber)
        discNumber = try? container.decodeIfPresent(Int.self, forKey: .discNumber)
        quality = try? container.decodeIfPresent(TracksideCatalogService.Quality.self, forKey: .quality)
        songID = (try? container.decodeIfPresent(String.self, forKey: .songID))?.lowercased()
        songKey = try? container.decodeIfPresent(String.self, forKey: .songKey)
        playable = try? container.decodeIfPresent(Bool.self, forKey: .playable)
    }

    public var isPlayable: Bool { playable != false }

    public var isExplicit: Bool { explicit == "explicit" }

    public var duration: TimeInterval? {
        durationMS.flatMap { $0 > 0 ? TimeInterval($0) / 1_000 : nil }
    }

    /// The name to show: the credit line, or the artists joined.
    public var artistLine: String {
        if let artist, !artist.isEmpty { return artist }
        return artists.map(\.name).filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

public struct TracksideCatalogAlbum: Codable, Sendable, Hashable, Identifiable {
    public var ref: String
    public var serviceID: String
    public var name: String
    public var artist: String?
    public var artists: [TracksideCatalogCredit]
    public var year: Int?
    public var artworkID: String?
    public var trackCount: Int?
    public var albumKey: String?

    public var id: String { ref }

    public init(ref: String, serviceID: String, name: String, artist: String? = nil, year: Int? = nil, artworkID: String? = nil, trackCount: Int? = nil) {
        self.ref = ref
        self.serviceID = serviceID
        self.name = name
        self.artist = artist
        self.artists = []
        self.year = year
        self.artworkID = artworkID
        self.trackCount = trackCount
    }

    enum CodingKeys: String, CodingKey {
        case ref, name, artist, artists, year
        case serviceID = "service_id"
        case artworkID = "artwork_id"
        case trackCount = "track_count"
        case albumKey = "album_key"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        serviceID = ((try? container.decodeIfPresent(String.self, forKey: .serviceID)) ?? "").lowercased()
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        artist = try? container.decodeIfPresent(String.self, forKey: .artist)
        artists = (try? container.decodeIfPresent([TracksideLenientElement<TracksideCatalogCredit>].self, forKey: .artists))?.compactMap(\.value) ?? []
        year = try? container.decodeIfPresent(Int.self, forKey: .year)
        artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
        trackCount = try? container.decodeIfPresent(Int.self, forKey: .trackCount)
        albumKey = try? container.decodeIfPresent(String.self, forKey: .albumKey)
    }

    /// The artist who has a page of their own, where the service named one.
    public var artistRef: String? { artists.first { $0.ref != nil }?.ref }
}

public struct TracksideCatalogArtist: Codable, Sendable, Hashable, Identifiable {
    public var ref: String
    public var serviceID: String
    public var name: String
    /// The artist identity Tracks groups plays by.
    public var identity: String?
    public var artworkID: String?

    public var id: String { ref }

    public init(ref: String, serviceID: String, name: String, identity: String? = nil, artworkID: String? = nil) {
        self.ref = ref
        self.serviceID = serviceID
        self.name = name
        self.identity = identity
        self.artworkID = artworkID
    }

    enum CodingKeys: String, CodingKey {
        case ref, name, identity
        case serviceID = "service_id"
        case artworkID = "artwork_id"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        serviceID = ((try? container.decodeIfPresent(String.self, forKey: .serviceID)) ?? "").lowercased()
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        identity = try? container.decodeIfPresent(String.self, forKey: .identity)
        artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
    }
}

public struct TracksideCatalogPlaylist: Codable, Sendable, Hashable, Identifiable {
    public var ref: String
    public var serviceID: String
    public var name: String
    public var owner: String?
    public var trackCount: Int?
    public var artworkID: String?
    public var isEditable: Bool?

    public var id: String { ref }

    public init(ref: String, serviceID: String, name: String, owner: String? = nil, trackCount: Int? = nil, artworkID: String? = nil) {
        self.ref = ref
        self.serviceID = serviceID
        self.name = name
        self.owner = owner
        self.trackCount = trackCount
        self.artworkID = artworkID
    }

    enum CodingKeys: String, CodingKey {
        case ref, name, owner
        case serviceID = "service_id"
        case trackCount = "track_count"
        case artworkID = "artwork_id"
        case isEditable = "editable"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        serviceID = ((try? container.decodeIfPresent(String.self, forKey: .serviceID)) ?? "").lowercased()
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        owner = try? container.decodeIfPresent(String.self, forKey: .owner)
        trackCount = try? container.decodeIfPresent(Int.self, forKey: .trackCount)
        artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
        isEditable = try? container.decodeIfPresent(Bool.self, forKey: .isEditable)
    }
}

public struct TracksideCatalogRadio: Codable, Sendable, Hashable, Identifiable {
    public var ref: String
    public var serviceID: String
    public var name: String
    public var artworkID: String?

    public var id: String { ref }

    enum CodingKeys: String, CodingKey {
        case ref, name
        case serviceID = "service_id"
        case artworkID = "artwork_id"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ref = try container.decode(String.self, forKey: .ref)
        serviceID = ((try? container.decodeIfPresent(String.self, forKey: .serviceID)) ?? "").lowercased()
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
    }
}

/// A list read item by item: one in a shape this build doesn't know is left out, never the list.
struct TracksideLenientList<Item: Decodable & Sendable & Hashable>: Decodable, Sendable, Hashable {
    let items: [Item]

    enum CodingKeys: String, CodingKey {
        case items
    }

    init(items: [Item]) {
        self.items = items
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = (try? container.decodeIfPresent([TracksideLenientElement<Item>].self, forKey: .items))?.compactMap(\.value) ?? []
    }
}

/// `GET /catalog/search`, and the `catalog` section of `GET /search`.
public struct TracksideCatalogSearch: Codable, Sendable, Hashable {
    public enum ServiceState: String, TracksideOpenEnum {
        case ok, failed, unknown
        case timedOut = "timed_out"
        case signedOut = "signed_out"
    }

    public struct Answer: Codable, Sendable, Hashable {
        public var id: String
        public var state: ServiceState

        public init(id: String, state: ServiceState) {
            self.id = id
            self.state = state
        }

        enum CodingKeys: String, CodingKey {
            case id, state
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id).lowercased()
            state = (try? container.decodeIfPresent(ServiceState.self, forKey: .state)) ?? .unknown
        }
    }

    public var tracks: [TracksideCatalogTrack]
    public var albums: [TracksideCatalogAlbum]
    public var artists: [TracksideCatalogArtist]
    public var playlists: [TracksideCatalogPlaylist]
    public var radios: [TracksideCatalogRadio]
    /// False when a service didn't answer in time.
    public var isComplete: Bool
    /// How each service asked did.
    public var services: [Answer]
    /// Only inside `GET /search`, when the services can't be reached.
    public var state: TracksideSpeakerList.State?

    public init(tracks: [TracksideCatalogTrack] = [], albums: [TracksideCatalogAlbum] = [], artists: [TracksideCatalogArtist] = [], playlists: [TracksideCatalogPlaylist] = [], isComplete: Bool = true, services: [Answer] = [], state: TracksideSpeakerList.State? = nil) {
        self.tracks = tracks
        self.albums = albums
        self.artists = artists
        self.playlists = playlists
        self.radios = []
        self.isComplete = isComplete
        self.services = services
        self.state = state
    }

    enum CodingKeys: String, CodingKey {
        case tracks, albums, artists, playlists, radios, services, state
        case isComplete = "complete"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        tracks = (try? container.decodeIfPresent(TracksideLenientList<TracksideCatalogTrack>.self, forKey: .tracks))?.items ?? []
        albums = (try? container.decodeIfPresent(TracksideLenientList<TracksideCatalogAlbum>.self, forKey: .albums))?.items ?? []
        artists = (try? container.decodeIfPresent(TracksideLenientList<TracksideCatalogArtist>.self, forKey: .artists))?.items ?? []
        playlists = (try? container.decodeIfPresent(TracksideLenientList<TracksideCatalogPlaylist>.self, forKey: .playlists))?.items ?? []
        radios = (try? container.decodeIfPresent(TracksideLenientList<TracksideCatalogRadio>.self, forKey: .radios))?.items ?? []
        isComplete = (try? container.decodeIfPresent(Bool.self, forKey: .isComplete)) ?? true
        services = (try? container.decodeIfPresent([TracksideLenientElement<Answer>].self, forKey: .services))?.compactMap(\.value) ?? []
        state = try? container.decodeIfPresent(TracksideSpeakerList.State.self, forKey: .state)
    }

    private struct Section<Item: Encodable>: Encodable {
        let items: [Item]
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(Section(items: tracks), forKey: .tracks)
        try container.encode(Section(items: albums), forKey: .albums)
        try container.encode(Section(items: artists), forKey: .artists)
        try container.encode(Section(items: playlists), forKey: .playlists)
        if !radios.isEmpty { try container.encode(Section(items: radios), forKey: .radios) }
        try container.encode(isComplete, forKey: .isComplete)
        try container.encode(services, forKey: .services)
        try container.encodeIfPresent(state, forKey: .state)
    }

    public var isEmpty: Bool {
        tracks.isEmpty && albums.isEmpty && artists.isEmpty && playlists.isEmpty && radios.isEmpty
    }

    /// The services can't be reached right now.
    public var isUnavailable: Bool { state == .unavailable || state == .connecting }
}

/// What `GET /catalog/items/{ref}` answers, by the kind of item the ref names.
public enum TracksideCatalogItem: Decodable, Sendable, Hashable {
    case track(TracksideCatalogTrack)
    case album(TracksideCatalogAlbum, tracks: [TracksideCatalogTrack])
    case artist(TracksideCatalogArtist, topTracks: [TracksideCatalogTrack], albums: [TracksideCatalogAlbum])
    /// One page of its tracks; the next starts at `nextCursor`.
    case playlist(TracksideCatalogPlaylist, tracks: [TracksideCatalogTrack], nextCursor: String?)
    case radio(TracksideCatalogRadio)

    enum CodingKeys: String, CodingKey {
        case track, album, artist, playlist, radio, tracks, albums
        case topTracks = "top_tracks"
        case nextCursor = "next_cursor"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        func list<Item: Decodable>(_ key: CodingKeys, _ type: Item.Type) -> [Item] {
            (try? container.decodeIfPresent([TracksideLenientElement<Item>].self, forKey: key))?.compactMap(\.value) ?? []
        }
        if let playlist = try? container.decodeIfPresent(TracksideCatalogPlaylist.self, forKey: .playlist) {
            self = .playlist(playlist, tracks: list(.tracks, TracksideCatalogTrack.self), nextCursor: try? container.decodeIfPresent(String.self, forKey: .nextCursor))
        } else if let album = try? container.decodeIfPresent(TracksideCatalogAlbum.self, forKey: .album) {
            self = .album(album, tracks: list(.tracks, TracksideCatalogTrack.self))
        } else if let artist = try? container.decodeIfPresent(TracksideCatalogArtist.self, forKey: .artist) {
            self = .artist(artist, topTracks: list(.topTracks, TracksideCatalogTrack.self), albums: list(.albums, TracksideCatalogAlbum.self))
        } else if let radio = try? container.decodeIfPresent(TracksideCatalogRadio.self, forKey: .radio) {
            self = .radio(radio)
        } else {
            self = .track(try container.decode(TracksideCatalogTrack.self, forKey: .track))
        }
    }

    public var tracks: [TracksideCatalogTrack] {
        switch self {
        case .track(let track): [track]
        case .album(_, let tracks), .playlist(_, let tracks, _): tracks
        case .artist(_, let topTracks, _): topTracks
        case .radio: []
        }
    }
}

/// `GET /catalog/services/{id}/library`: what's saved there, newest first.
public struct TracksideCatalogLibraryPage: Decodable, Sendable, Hashable {
    public enum Kind: String, Sendable, CaseIterable {
        case playlist, album, artist, track
    }

    public var playlists: [TracksideCatalogPlaylist]
    public var albums: [TracksideCatalogAlbum]
    public var artists: [TracksideCatalogArtist]
    public var tracks: [TracksideCatalogTrack]
    public var nextCursor: String?
    /// The server's last copy of it; absent before the first.
    public var syncedAt: Date?

    enum CodingKeys: String, CodingKey {
        case items
        case nextCursor = "next_cursor"
        case syncedAt = "synced_at"
    }

    /// Each item read as the kind it says it is.
    private struct Entry: Decodable, Sendable, Hashable {
        let kind: String?

        enum CodingKeys: String, CodingKey {
            case kind
        }

        init(from decoder: any Decoder) throws {
            kind = try? decoder.container(keyedBy: CodingKeys.self).decodeIfPresent(String.self, forKey: .kind)
        }
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        nextCursor = try? container.decodeIfPresent(String.self, forKey: .nextCursor)
        syncedAt = try? container.decodeIfPresent(Date.self, forKey: .syncedAt)
        var playlists: [TracksideCatalogPlaylist] = []
        var albums: [TracksideCatalogAlbum] = []
        var artists: [TracksideCatalogArtist] = []
        var tracks: [TracksideCatalogTrack] = []
        if var items = try? container.nestedUnkeyedContainer(forKey: .items) {
            while !items.isAtEnd {
                // Read twice, from the same element: once for its kind, then as that kind.
                guard let element = try? items.superDecoder() else { break }
                switch (try? Entry(from: element))?.kind {
                case "album": if let album = try? TracksideCatalogAlbum(from: element) { albums.append(album) }
                case "artist": if let artist = try? TracksideCatalogArtist(from: element) { artists.append(artist) }
                case "track": if let track = try? TracksideCatalogTrack(from: element) { tracks.append(track) }
                case "playlist": if let playlist = try? TracksideCatalogPlaylist(from: element) { playlists.append(playlist) }
                default: break
                }
            }
        }
        self.playlists = playlists
        self.albums = albums
        self.artists = artists
        self.tracks = tracks
    }

    public var isEmpty: Bool {
        playlists.isEmpty && albums.isEmpty && artists.isEmpty && tracks.isEmpty
    }
}

/// SSE `catalog_services`: the list or a service's account changed; read it again.
public struct TracksideCatalogServicesChange: Codable, Sendable, Hashable {
    public var state: TracksideSpeakerList.State

    public init(state: TracksideSpeakerList.State) {
        self.state = state
    }

    enum CodingKeys: String, CodingKey {
        case state
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = (try? container.decodeIfPresent(TracksideSpeakerList.State.self, forKey: .state)) ?? .unknown
    }
}

// MARK: - Asking

extension TracksideClient {
    public enum CatalogType: String, Sendable, CaseIterable {
        case track, album, artist, playlist, radio

        /// What a search asks for unless told otherwise.
        public static let standard: [CatalogType] = [.track, .album, .artist, .playlist]
    }

    /// The services this user can play from, and whether the server reaches them now.
    public func catalogServices() async throws -> TracksideCatalogServiceList {
        try await get("catalog/services")
    }

    /// One search of the services. One-shot: a service that didn't answer within the server's
    /// five seconds is listed as such, and there's no next page.
    /// - Parameter services: the ids to ask; all that can search when empty.
    public func searchCatalog(_ query: String, types: [CatalogType] = CatalogType.standard, services: [String] = [], limit: Int = 20) async throws -> TracksideCatalogSearch {
        var parameters = [
            "q": query,
            "types": types.map(\.rawValue).joined(separator: ","),
            "limit": String(min(50, max(1, limit))),
        ]
        if !services.isEmpty { parameters["services"] = services.joined(separator: ",") }
        return try await get("catalog/search", parameters)
    }

    /// The library and the services in one search. The library's sections are as
    /// ``searchLibrary(_:types:limit:cursor:)`` gives them and never hold a catalog item; the
    /// services' come in ``TracksideSearchResults/catalog``. Never with a cursor.
    public func searchWithCatalog(_ query: String, types: Set<SearchType> = Set(SearchType.allCases), catalogTypes: [CatalogType] = CatalogType.standard, limit: Int = 20) async throws -> TracksideSearchResults {
        let library = SearchType.allCases.filter(types.contains).map(\.rawValue)
        return try await get("search", [
            "q": query,
            "types": (library + ["catalog"]).joined(separator: ","),
            "catalog_types": catalogTypes.map(\.rawValue).joined(separator: ","),
            "limit": String(limit),
        ])
    }

    /// An album with its tracks, an artist with their top tracks and albums, or a page of a
    /// playlist, by the ref a listing gave.
    /// - Throws: ``TracksideError/notFound`` for an item the service no longer has, and
    ///   ``TracksideError/conflict(reason:)`` `service_signed_out`.
    public func catalogItem(_ ref: String, cursor: String? = nil, limit: Int? = nil) async throws -> TracksideCatalogItem {
        var query: [String: String] = [:]
        query["cursor"] = cursor
        query["limit"] = limit.map { String(min(500, max(1, $0))) }
        // Refs are path safe as the server makes them, and stay exactly as given.
        return try await get("catalog/items/\(ref)", query)
    }

    /// What's saved on a service: playlists unless another kind is asked for.
    public func catalogLibrary(service id: String, kind: TracksideCatalogLibraryPage.Kind = .playlist, cursor: String? = nil, limit: Int = 100) async throws -> TracksideCatalogLibraryPage {
        var query = ["kind": kind.rawValue, "limit": String(min(500, max(1, limit)))]
        query["cursor"] = cursor
        return try await get("catalog/services/\(id)/library", query)
    }
}
