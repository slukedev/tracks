import Foundation

/// One source a person has added: Apple Music, the music on this device, or one server. Kept
/// as a string so a value from a newer or older version reads back unchanged.
public enum SourceID: Hashable, Sendable, Codable, RawRepresentable {
    case appleMusic
    /// Files on this device: On This iPhone, On This Mac.
    case device
    /// A Trackside or Subsonic server, by its id in the server list.
    case server(String)
    case unknown(String)

    private static let serverPrefix = "server:"

    public init(rawValue: String) {
        switch rawValue {
        case "appleMusic": self = .appleMusic
        case "device": self = .device
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
/// order. Entirely the person's to arrange: nothing is pinned, and a place it doesn't name yet
/// goes last. Synced between devices; places another device doesn't have are kept and skipped.
public struct PlayFromOrder: Hashable, Sendable, RawRepresentable {
    public static let storageKey = "playFromOrder"
    /// Set on each device once its stored order has been written out in full, as it played
    /// before places could go anywhere.
    public static let migratedKey = "playFromOrderMigrated"

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

    /// Nothing chosen yet.
    public static let standard = PlayFromOrder([])

    /// Where a new order starts, before anything is moved.
    public static let starting: [PlayFromPlace] = [.source(.device), .downloads, .source(.appleMusic)]

    /// The order with the places it doesn't name yet added at the end, in the order given.
    public func including(_ new: [PlayFromPlace]) -> PlayFromOrder {
        PlayFromOrder(places + Self.starting + new)
    }

    /// The order of these places, the ones it doesn't name yet last.
    public func arranged(_ present: [PlayFromPlace]) -> [PlayFromPlace] {
        let here = Set(present)
        return including(present).places.filter(here.contains)
    }

    /// The order with these places, as shown, in their new order. Every other place keeps
    /// its position.
    public func reordered(_ shown: [PlayFromPlace]) -> PlayFromOrder {
        var full = including(shown).places
        let moved = Set(shown)
        let slots = full.indices.filter { moved.contains(full[$0]) }
        for (slot, place) in zip(slots, PlayFromOrder(shown).places) { full[slot] = place }
        return PlayFromOrder(full)
    }

    /// Copies best first, by where they play from; copies from the same place keep their order.
    public func ranked<Copy>(_ copies: [Copy], place: (Copy) -> PlayFromPlace) -> [Copy] {
        let order = including([]).places
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

    // MARK: - Orders saved before places could go anywhere

    /// The order as it played before, written out in full for these servers: what it didn't
    /// name sat after what comes before it in the old default order.
    public func migrated(servers: [String]) -> PlayFromOrder {
        let defaults: [PlayFromPlace] = [.source(.device), .downloads] + servers.map { .source(.server($0)) } + [.source(.appleMusic)]
        let current = Set(defaults)
        var arranged = places.filter(current.contains)
        for (offset, place) in defaults.enumerated() where !arranged.contains(place) {
            let before = defaults[..<offset].last(where: arranged.contains)
            let index = before.flatMap { arranged.firstIndex(of: $0) }.map { $0 + 1 } ?? 0
            arranged.insert(place, at: index)
        }
        return PlayFromOrder(arranged + places.filter { !current.contains($0) })
    }
}
