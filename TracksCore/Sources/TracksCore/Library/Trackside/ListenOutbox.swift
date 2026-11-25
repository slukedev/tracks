import Foundation

/// A song that played, waiting to be told to its server.
public struct ServerListen: Codable, Sendable, Hashable, Identifiable {
    /// The play's lasting id (``PlayID``), the same as its row in the history, which every
    /// device that syncs the history knows it by. The server keeps one listen per id, so
    /// sending it twice, or from two devices, counts it once.
    public let id: String
    /// The server's id for the song, when it's one of the server's.
    public var songID: String?
    public var startedAt: Date
    public var playedMS: Int
    public var title: String
    public var artist: String
    public var album: String?
    public var durationMS: Int?

    public init(id: String, songID: String?, startedAt: Date, played: TimeInterval, title: String, artist: String, album: String? = nil, duration: TimeInterval? = nil) {
        self.id = id
        self.songID = songID
        self.startedAt = startedAt
        self.playedMS = Int((played * 1_000).rounded())
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMS = duration.map { Int(($0 * 1_000).rounded()) }
    }
}

/// What a server made of one listen.
public enum ServerListenStatus: String, TracksideOpenEnum {
    case accepted
    /// It had it already, which is as good as taking it.
    case duplicate
    /// Never taken, however often it's sent: from the future, or missing what it needs.
    case rejected
    case unknown
}

/// Plays waiting to be told to one server, kept on disk so none are lost to a lost connection
/// or a relaunch, and sent in batches once the server can be reached.
public actor ListenOutbox {
    public nonisolated let file: URL
    /// The most a server takes in one request.
    public let batchSize: Int
    public private(set) var listens: [ServerListen]
    private var flushing: Task<FlushReport, any Error>?

    public struct FlushReport: Sendable, Equatable {
        /// Taken by the server, or already there.
        public var sent = 0
        /// Refused for good, so let go of.
        public var dropped = 0
        /// Still waiting: the server didn't answer for them.
        public var waiting = 0
    }

    public init(file: URL, batchSize: Int = 1_000) {
        self.file = file
        self.batchSize = max(1, batchSize)
        listens = StoredJSON.loadEach([ServerListen].self, from: file, decoder: TracksideJSON.decoder) ?? []
    }

    /// Keeps a play to send. A play already waiting is kept once.
    public func add(_ listen: ServerListen) {
        guard !listens.contains(where: { $0.id == listen.id }) else { return }
        listens.append(listen)
        save()
    }

    /// Sends everything waiting, a batch at a time, through any music server.
    @discardableResult
    public func flush(to client: some MusicServerClient) async throws -> FlushReport {
        try await flush { try await client.submitListens($0) }
    }

    /// Sends everything waiting, a batch at a time. A batch that fails stops the flush and
    /// stays to be sent next time; a flush already running is waited for instead of sending
    /// the same plays twice at once.
    @discardableResult
    public func flush(_ send: @escaping @Sendable ([ServerListen]) async throws -> [String: ServerListenStatus]) async throws -> FlushReport {
        if let flushing { return try await flushing.value }
        let task = Task { try await sendAll(send) }
        flushing = task
        defer { flushing = nil }
        return try await task.value
    }

    private func sendAll(_ send: @Sendable ([ServerListen]) async throws -> [String: ServerListenStatus]) async throws -> FlushReport {
        var report = FlushReport()
        var tried: Set<String> = []
        while true {
            let batch = Array(listens.lazy.filter { !tried.contains($0.id) }.prefix(batchSize))
            guard !batch.isEmpty else { break }
            tried.formUnion(batch.map(\.id))
            let results = try await send(batch)
            var settled: Set<String> = []
            for listen in batch {
                switch results[listen.id] {
                case .accepted, .duplicate:
                    report.sent += 1
                    settled.insert(listen.id)
                case .rejected:
                    report.dropped += 1
                    settled.insert(listen.id)
                case .unknown, nil:
                    break
                }
            }
            listens.removeAll { settled.contains($0.id) }
            save()
        }
        report.waiting = listens.count
        return report
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            if listens.isEmpty {
                try? FileManager.default.removeItem(at: file)
            } else {
                try TracksideJSON.encoder.encode(listens).write(to: file, options: .atomic)
            }
        } catch {
            // Kept in memory for this launch, and tried again with the next change.
        }
    }
}
