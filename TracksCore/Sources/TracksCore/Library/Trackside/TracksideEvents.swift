import Foundation

/// One server-sent event, as the stream spells it.
public struct ServerSentEvent: Sendable, Equatable {
    /// The last id the stream gave, which a reconnect sends back as `Last-Event-ID`.
    public var id: String?
    public var event: String
    public var data: String

    public init(id: String?, event: String, data: String) {
        self.id = id
        self.event = event
        self.data = data
    }
}

/// Reads a `text/event-stream` as its bytes arrive, in pieces of any size, following the
/// HTML standard's rules: comment lines are skipped, `data` lines join with line breaks, a
/// blank line ends an event, and lines end with CR, LF or both.
public struct ServerSentEventParser: Sendable {
    public private(set) var lastEventID: String?
    /// How long the server asks for between reconnects, where it says.
    public private(set) var retry: Duration?

    private var line: [UInt8] = []
    private var afterCR = false
    private var isFirstLine = true
    private var eventType = ""
    private var data = ""
    private var hasData = false

    public init(lastEventID: String? = nil) {
        self.lastEventID = lastEventID
    }

    public mutating func push(_ bytes: some Sequence<UInt8>) -> [ServerSentEvent] {
        var events: [ServerSentEvent] = []
        for byte in bytes {
            switch byte {
            case 0x0D:
                if let event = endLine() { events.append(event) }
                afterCR = true
            case 0x0A:
                if afterCR {
                    afterCR = false
                } else if let event = endLine() {
                    events.append(event)
                }
            default:
                afterCR = false
                line.append(byte)
            }
        }
        return events
    }

    private mutating func endLine() -> ServerSentEvent? {
        var text = String(decoding: line, as: UTF8.self)
        line.removeAll(keepingCapacity: true)
        if isFirstLine {
            isFirstLine = false
            if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        }
        if text.isEmpty { return dispatch() }
        if text.hasPrefix(":") { return nil }
        let field: Substring
        var value: Substring
        if let colon = text.firstIndex(of: ":") {
            field = text[..<colon]
            value = text[text.index(after: colon)...]
            if value.hasPrefix(" ") { value = value.dropFirst() }
        } else {
            field = text[...]
            value = ""
        }
        switch field {
        case "event":
            eventType = String(value)
        case "data":
            if hasData { data += "\n" }
            data += value
            hasData = true
        case "id":
            if !value.contains("\0") { lastEventID = value.isEmpty ? nil : String(value) }
        case "retry":
            if !value.isEmpty, value.allSatisfy(\.isASCII), let milliseconds = Int(value) { retry = .milliseconds(milliseconds) }
        default:
            break
        }
        return nil
    }

    private mutating func dispatch() -> ServerSentEvent? {
        defer {
            eventType = ""
            data = ""
            hasData = false
        }
        guard hasData else { return nil }
        return ServerSentEvent(id: lastEventID, event: eventType.isEmpty ? "message" : eventType, data: data)
    }
}

/// What a Trackside server says while Tracks is open.
public enum TracksideEvent: Sendable, Equatable {
    case hello(serverID: String, seq: Int64)
    /// Something this user can see changed: time to sync.
    case changed(seq: Int64)
    /// The stream fell behind and events were lost: time to sync.
    case resync
    case library(id: String, state: TracksideLibrary.State)
    case acquisition(TracksideAcquisition)
    /// This device's key was revoked, and the stream has ended.
    case revoked
    /// The server's addresses changed: ask `/server/urls` again, unless `etag` (the new list's
    /// ETag, made from its `version`) is the one already known.
    case serverURLs(etag: String?)
    /// Something synced through Trackside Cloud changed, such as `plays`: time to pull it.
    case cloud(entity: String?)
    /// A speaker became visible, or changed in anything but its position.
    case speakerChanged(TracksideSpeaker)
    case speakerRemoved(id: String)
    case speakerProgress(TracksideSpeakerProgress)
    /// A speaker's Up Next changed: read it again if it's on show.
    case speakerQueue(id: String, revision: Int64?)
    /// How a command this device sent went.
    case speakerCommand(TracksideSpeakerCommandOutcome)
    /// Whether the server can reach its speakers. Back to `ok`: read them all again.
    case speakers(TracksideSpeakersState)
    /// One this build doesn't know, or doesn't need.
    case other(String)

    public init(_ event: ServerSentEvent) {
        let data = Data(event.data.utf8)
        let decoder = TracksideJSON.decoder
        switch event.event {
        case "hello":
            guard let hello = try? decoder.decode(Hello.self, from: data) else { self = .other(event.event); return }
            self = .hello(serverID: hello.serverID, seq: hello.seq ?? 0)
        case "changed":
            guard let changed = try? decoder.decode(Changed.self, from: data) else { self = .resync; return }
            self = .changed(seq: changed.seq)
        case "resync":
            self = .resync
        case "library":
            guard let library = try? decoder.decode(LibraryState.self, from: data) else { self = .other(event.event); return }
            self = .library(id: library.id, state: library.state)
        case "acquisition":
            guard let acquisition = try? decoder.decode(TracksideAcquisition.self, from: data) else { self = .other(event.event); return }
            self = .acquisition(acquisition)
        case "session":
            self = .revoked
        case "server_urls":
            self = .serverURLs(etag: (try? decoder.decode(TracksideServerURLsEvent.self, from: data))?.etag)
        case "cloud":
            self = .cloud(entity: (try? decoder.decode(CloudChange.self, from: data))?.entity)
        case "speaker_added", "speaker_updated":
            guard let speaker = try? decoder.decode(TracksideSpeaker.self, from: data) else { self = .other(event.event); return }
            self = .speakerChanged(speaker)
        case "speaker_removed":
            guard let removed = try? decoder.decode(SpeakerReference.self, from: data) else { self = .other(event.event); return }
            self = .speakerRemoved(id: removed.id.lowercased())
        case "speaker_progress":
            guard let progress = try? decoder.decode(TracksideSpeakerProgress.self, from: data) else { self = .other(event.event); return }
            self = .speakerProgress(progress)
        case "speaker_queue":
            guard let queue = try? decoder.decode(SpeakerReference.self, from: data) else { self = .other(event.event); return }
            self = .speakerQueue(id: queue.id.lowercased(), revision: queue.revision)
        case "speaker_command":
            guard let outcome = try? decoder.decode(TracksideSpeakerCommandOutcome.self, from: data) else { self = .other(event.event); return }
            self = .speakerCommand(outcome)
        case "speakers":
            guard let state = try? decoder.decode(TracksideSpeakersState.self, from: data) else { self = .other(event.event); return }
            self = .speakers(state)
        default:
            self = .other(event.event)
        }
    }

    private struct Hello: Decodable {
        let serverID: String
        let seq: Int64?

        enum CodingKeys: String, CodingKey {
            case seq
            case serverID = "server_id"
        }
    }

    private struct SpeakerReference: Decodable {
        let id: String
        let revision: Int64?
    }

    private struct CloudChange: Decodable {
        let entity: String?
    }

    private struct Changed: Decodable {
        let seq: Int64
    }

    private struct LibraryState: Decodable {
        let id: String
        let state: TracksideLibrary.State
    }
}

/// Opens a streamed response and hands its body on as it arrives. `URLSession.bytes` in the
/// app; a stub in tests.
public protocol TracksideStreamTransport: Sendable {
    func open(_ request: URLRequest) async throws -> (HTTPURLResponse, AsyncThrowingStream<[UInt8], any Error>)
}

/// A Trackside server's events, kept connected while it's listened to: a dropped connection is
/// opened again, a little later each time it fails, and picks up after the last event seen.
public struct TracksideEventStream: Sendable {
    public struct Message: Sendable, Equatable {
        public var event: TracksideEvent
        /// Where a later stream picks up from.
        public var lastEventID: String?
    }

    private let client: TracksideClient
    private let transport: any TracksideStreamTransport
    private let firstDelay: Duration
    private let longestDelay: Duration

    public init(client: TracksideClient, transport: any TracksideStreamTransport = URLSessionTracksideStreamTransport(), firstDelay: Duration = .seconds(1), longestDelay: Duration = .seconds(30)) {
        self.client = client
        self.transport = transport
        self.firstDelay = firstDelay
        self.longestDelay = longestDelay
    }

    /// Ends when the device's key is revoked or refused, which throws
    /// ``TracksideError/unauthenticated``, or when the listening task is cancelled. Connect
    /// only while Tracks is in the foreground.
    public func messages(after lastEventID: String? = nil) -> AsyncThrowingStream<Message, any Error> {
        let (stream, continuation) = AsyncThrowingStream<Message, any Error>.makeStream()
        let task = Task { [client, transport, firstDelay, longestDelay] in
            var lastID = lastEventID
            var delay = firstDelay
            while !Task.isCancelled {
                do {
                    let (response, bytes) = try await transport.open(try await client.eventsRequest(lastEventID: lastID))
                    switch response.statusCode {
                    case 200..<300:
                        break
                    case 401:
                        throw TracksideError.unauthenticated
                    default:
                        throw TracksideError.http(status: response.statusCode)
                    }
                    var parser = ServerSentEventParser(lastEventID: lastID)
                    var connected = false
                    for try await chunk in bytes {
                        for event in parser.push(chunk) {
                            if !connected {
                                connected = true
                                delay = firstDelay
                            }
                            lastID = parser.lastEventID
                            let message = Message(event: TracksideEvent(event), lastEventID: lastID)
                            continuation.yield(message)
                            if message.event == .revoked {
                                continuation.finish(throwing: TracksideError.unauthenticated)
                                return
                            }
                        }
                    }
                    lastID = parser.lastEventID
                    // A server asking for no wait at all would be asked again at once, forever.
                    if let retry = parser.retry { delay = min(max(retry, delay), longestDelay) }
                } catch TracksideError.unauthenticated {
                    continuation.finish(throwing: TracksideError.unauthenticated)
                    return
                } catch {
                    if Task.isCancelled { break }
                }
                do {
                    try await Task.sleep(for: delay)
                } catch {
                    break
                }
                delay = min(delay * 2, longestDelay)
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }
}
