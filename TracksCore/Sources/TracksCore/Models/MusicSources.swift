import Foundation

/// One source a person has added: Apple Music, the music on this device, one server, or
/// Spotify. Kept as a string so a value from a newer version reads back unchanged.
public enum SourceID: Hashable, Sendable, Codable, RawRepresentable {
    case appleMusic
    /// Files on this device: On This iPhone, On This Mac.
    case device
    /// A Trackside or Subsonic server, by its id in the server list.
    case server(String)
    case spotify
    case unknown(String)

    private static let serverPrefix = "server:"

    public init(rawValue: String) {
        switch rawValue {
        case "appleMusic": self = .appleMusic
        case "device": self = .device
        case "spotify": self = .spotify
        case let value where value.hasPrefix(Self.serverPrefix) && value.count > Self.serverPrefix.count:
            self = .server(String(value.dropFirst(Self.serverPrefix.count)))
        default: self = .unknown(rawValue)
        }
    }

    public var rawValue: String {
        switch self {
        case .appleMusic: "appleMusic"
        case .device: "device"
        case .server(let id): Self.serverPrefix + id
        case .spotify: "spotify"
        case .unknown(let stored): stored
        }
    }

    public var isServer: Bool {
        if case .server = self { return true }
        return false
    }

    /// How a play from this source is kept in the history.
    public var playSource: PlaySource {
        switch self {
        case .appleMusic: .appleMusic
        case .device, .server: .yourMusic
        case .spotify: .spotify
        case .unknown(let stored): .unknown(stored)
        }
    }
}

/// What Play, Library and Tracks Radio draw from: every source, or one. A filter only; it
/// never changes what's queued.
public enum SourceSelection: Hashable, Sendable, RawRepresentable {
    case all
    case only(SourceID)

    public static let storageKey = "sourceFilter"

    public init(rawValue: String) {
        self = rawValue.isEmpty || rawValue == "all" ? .all : .only(SourceID(rawValue: rawValue))
    }

    public var rawValue: String {
        switch self {
        case .all: "all"
        case .only(let source): source.rawValue
        }
    }

    public func includes(_ source: SourceID) -> Bool {
        switch self {
        case .all: true
        case .only(let chosen): chosen == source
        }
    }

    /// The selection that applies: one source only while it's still added, otherwise all.
    public func effective(among sources: [SourceID]) -> SourceSelection {
        switch self {
        case .all: .all
        case .only(let chosen): sources.contains(chosen) && sources.count > 1 ? self : .all
        }
    }
}

/// A place a copy of a song can play from, in the order Play From ranks them.
public enum PlayFromPlace: Hashable, Sendable, RawRepresentable {
    /// A server's song kept on this device.
    case downloads
    case source(SourceID)

    public init(rawValue: String) {
        self = rawValue == "downloads" ? .downloads : .source(SourceID(rawValue: rawValue))
    }

    public var rawValue: String {
        switch self {
        case .downloads: "downloads"
        case .source(let source): source.rawValue
        }
    }
}

/// Which copy plays when a song is in several sources: the first that can play, in this
/// order. Synced between devices; servers another device doesn't have are kept and skipped.
public struct PlayFromOrder: Hashable, Sendable, RawRepresentable {
    public static let storageKey = "playFromOrder"

    public let places: [PlayFromPlace]

    public init(_ places: [PlayFromPlace]) {
        var seen = Set<PlayFromPlace>()
        self.places = places.filter { seen.insert($0).inserted }
    }

    public init(rawValue: String) {
        self.init(rawValue.split(separator: "\n").map { PlayFromPlace(rawValue: String($0)) })
    }

    public var rawValue: String {
        places.map(\.rawValue).joined(separator: "\n")
    }

    /// Nothing chosen yet: the default order alone.
    public static let standard = PlayFromOrder([])

    /// Local and owned first: free, offline and more often lossless.
    static func defaultOrder(servers: [String]) -> [PlayFromPlace] {
        [.source(.device), .downloads] + servers.map { .source(.server($0)) } + [.source(.appleMusic), .source(.spotify)]
    }

    /// The full order for these servers, in the order they were added: the chosen order, with
    /// anything it doesn't name placed after what comes before it by default, and servers no
    /// longer here left out.
    public func arranged(servers: [String]) -> [PlayFromPlace] {
        let defaults = Self.defaultOrder(servers: servers)
        let current = Set(defaults)
        var arranged = places.filter(current.contains)
        for (offset, place) in defaults.enumerated() where !arranged.contains(place) {
            let before = defaults[..<offset].last(where: arranged.contains)
            let index = before.flatMap { arranged.firstIndex(of: $0) }.map { $0 + 1 } ?? 0
            arranged.insert(place, at: index)
        }
        return arranged
    }

    /// The order with these places as arranged for the servers here, keeping the places of
    /// servers this device doesn't have where they were.
    public func replacing(arranged new: [PlayFromPlace], servers: [String]) -> PlayFromOrder {
        let here = Set(Self.defaultOrder(servers: servers))
        let elsewhere = places.filter { !here.contains($0) }
        return PlayFromOrder(new + elsewhere)
    }

    /// Copies best first, by where they play from; copies from the same place keep their order.
    public func ranked<Copy>(_ copies: [Copy], place: (Copy) -> PlayFromPlace) -> [Copy] {
        let servers = copies.compactMap { copy -> String? in
            if case .source(.server(let id)) = place(copy) { return id }
            return nil
        }
        var unique: [String] = []
        for id in servers where !unique.contains(id) { unique.append(id) }
        let order = arranged(servers: unique)
        let rank = Dictionary(uniqueKeysWithValues: order.enumerated().map { ($1, $0) })
        return copies.enumerated()
            .sorted { lhs, rhs in
                let left = rank[place(lhs.element)] ?? order.count
                let right = rank[place(rhs.element)] ?? order.count
                return left == right ? lhs.offset < rhs.offset : left < right
            }
            .map(\.element)
    }

    /// The copy to play: the best that can play now, or nil when none can.
    public func choose<Copy>(_ copies: [Copy], place: (Copy) -> PlayFromPlace, canPlay: (Copy) -> Bool) -> Copy? {
        ranked(copies, place: place).first(where: canPlay)
    }
}
