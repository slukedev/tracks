import Foundation

/// Songs asked of one music server to keep, by song, with when each was asked. Asking stars
/// the song there, which a server like Octo takes as "get me this" and answers by fetching the
/// file into its library. Each song is waited for until a sync brings it in, or a day passes.
public struct ServerKeeps: Codable, Sendable, Equatable {
    /// How long a song is waited for. Past this the server couldn't find it, and a daily sync
    /// will bring it in if it ever does.
    public static let patience: TimeInterval = 24 * 60 * 60

    private var asked: [String: Date] = [:]
    /// Each song's names, for the list of what's on its way. Optional so what earlier builds
    /// saved, which has none, still reads.
    private var names: [String: Name]?

    public struct Name: Codable, Sendable, Equatable {
        public let title: String
        public let artist: String
    }

    /// A song still waited for.
    public struct Waiting: Sendable, Equatable, Identifiable {
        public let identity: String
        public let name: Name?
        public let askedAt: Date

        public var id: String { identity }
    }

    public init() {}

    public var isEmpty: Bool { asked.isEmpty }

    /// - Parameter identity: the song's ``LocalTrack/identity``, which stays the same when
    ///   the server files it under a new id.
    public mutating func ask(for identity: String, title: String? = nil, artist: String? = nil, at date: Date = .now) {
        asked[identity] = date
        if let title, let artist {
            var known = names ?? [:]
            known[identity] = Name(title: title, artist: artist)
            names = known
        }
    }

    public func isWaiting(for identity: String) -> Bool {
        asked[identity] != nil
    }

    /// Every song still waited for, the last asked first.
    public var waiting: [Waiting] {
        asked
            .map { Waiting(identity: $0.key, name: names?[$0.key], askedAt: $0.value) }
            .sorted { ($0.askedAt, $1.identity) > ($1.askedAt, $0.identity) }
    }

    /// Lets go of the songs a sync brought in, and of any waited for longer than ``patience``.
    public mutating func settle(arrived: Set<String>, now: Date = .now) {
        asked = asked.filter { identity, date in
            !arrived.contains(identity) && now.timeIntervalSince(date) < Self.patience
        }
        if let known = names {
            let kept = known.filter { asked[$0.key] != nil }
            names = kept.isEmpty ? nil : kept
        }
    }
}
