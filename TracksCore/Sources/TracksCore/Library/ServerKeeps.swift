import Foundation

/// Songs asked of one music server to keep, by song, with when each was asked. Asking stars the
/// song there, which a server that fetches songs on request takes as "get me this" and answers
/// by fetching the file into its library. Each song is waited for until a sync brings it in, or
/// a day passes.
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

/// What may be done to bring a server's song to this device. A song in the server's library
/// downloads. One it doesn't have is only asked for when you tap Get: asking stars it, and a
/// server that fetches songs on request goes and gets it, so nothing else may.
public enum ServerSongFetch: Sendable, Equatable {
    case nothing
    case download
    case askServer

    /// - Parameters:
    ///   - isInLibrary: the server has the song, as the last sync found.
    ///   - isOnDevice: it's downloaded or downloading.
    ///   - isWaiting: it's been asked for and hasn't arrived.
    ///   - tappedGet: you asked for this song.
    public static func decide(isInLibrary: Bool, isOnDevice: Bool, isWaiting: Bool, tappedGet: Bool) -> Self {
        if isInLibrary { return isOnDevice ? .nothing : .download }
        return tappedGet && !isWaiting ? .askServer : .nothing
    }
}

/// Watches for the songs asked of one Subsonic server to arrive, without syncing to find out.
///
/// A server that fetches songs on request usually has one within minutes. Each song is looked
/// for on its own, by a search for its title, every half minute at first and every two
/// minutes after, for twenty minutes from when it was asked; only once one has arrived is the
/// library synced, so it comes in with its place in the albums. A server that can't be asked
/// is asked less and less often. Asking for another song while it watches adds that song to
/// the same rounds rather than starting again.
@MainActor
public final class ServerKeepWatch {
    public struct Timing: Sendable {
        /// How long after it's asked for a song is looked for. A daily sync finds it later.
        public var window: Duration = .seconds(20 * 60)
        /// How long the first, closer looks last.
        public var early: Duration = .seconds(5 * 60)
        public var earlyStep: Duration = .seconds(30)
        public var lateStep: Duration = .seconds(120)
        /// The longest wait between looks at a server that keeps failing.
        public var longestStep: Duration = .seconds(10 * 60)
        /// How far each wait may move either way, as a share of it, so devices asking the same
        /// server don't ask it in step.
        public var jitter: Double = 0.2

        public init() {}
    }

    /// Whether the server has the song in its library now.
    public typealias Look = @Sendable (ServerKeeps.Waiting) async throws -> Bool

    private let timing: Timing
    private let now: @Sendable () -> Date
    private let sleep: @Sendable (Duration) async throws -> Void
    private var task: Task<Void, Never>?
    private var taskID: UUID?

    public init(
        timing: Timing = Timing(),
        now: @escaping @Sendable () -> Date = { .now },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.timing = timing
        self.now = now
        self.sleep = sleep
    }

    public var isWatching: Bool { task != nil }

    /// Watches, unless it's watching already.
    /// - Parameters:
    ///   - waiting: the songs still waited for, read afresh before each round.
    ///   - look: asks the server for one song.
    ///   - arrived: called once in a round in which any song turned up: a sync, which lets go
    ///     of the songs it brings in.
    public func watch(
        waiting: @escaping @MainActor () -> [ServerKeeps.Waiting],
        look: @escaping Look,
        arrived: @escaping @MainActor () async -> Void
    ) {
        guard task == nil else { return }
        let id = UUID()
        taskID = id
        task = Task { [timing, now, sleep, weak self] in
            await Self.run(timing: timing, now: now, sleep: sleep, waiting: waiting, look: look, arrived: arrived)
            guard let self, self.taskID == id else { return }
            self.task = nil
            self.taskID = nil
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
        taskID = nil
    }

    private static func run(
        timing: Timing,
        now: @Sendable () -> Date,
        sleep: @Sendable (Duration) async throws -> Void,
        waiting: @MainActor () -> [ServerKeeps.Waiting],
        look: Look,
        arrived: @MainActor () async -> Void
    ) async {
        var failures = 0
        while !Task.isCancelled {
            let due = Self.due(waiting(), at: now(), within: timing.window)
            guard let youngest = due.map(\.askedAt).max() else { return }
            var step = now().timeIntervalSince(youngest) < timing.early.seconds ? timing.earlyStep : timing.lateStep
            if failures > 0 { step = min(timing.longestStep, step * Double(1 << min(failures, 8))) }
            let spread = Double.random(in: -timing.jitter...timing.jitter)
            do { try await sleep(step * (1 + spread)) } catch { return }
            guard !Task.isCancelled else { return }

            var found = false
            var failed = false
            for song in Self.due(waiting(), at: now(), within: timing.window) {
                do {
                    if try await look(song) { found = true }
                } catch {
                    if error is CancellationError { return }
                    failed = true
                    break
                }
                guard !Task.isCancelled else { return }
            }
            failures = failed ? failures + 1 : 0
            if found { await arrived() }
        }
    }

    /// The songs still within their window, which can be looked for by name.
    private static func due(_ waiting: [ServerKeeps.Waiting], at now: Date, within window: Duration) -> [ServerKeeps.Waiting] {
        waiting.filter { $0.name != nil && now.timeIntervalSince($0.askedAt) < window.seconds }
    }
}

private extension Duration {
    var seconds: TimeInterval {
        let (whole, fraction) = components
        return TimeInterval(whole) + TimeInterval(fraction) / 1e18
    }
}
