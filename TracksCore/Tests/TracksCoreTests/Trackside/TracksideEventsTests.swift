import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// Serves one prepared stream body per connection, and remembers each connection's request.
private final class StreamStub: TracksideStreamTransport {
    private let bodies: Mutex<[(status: Int, chunks: [String])]>
    private let opened = Mutex<[URLRequest]>([])

    init(_ bodies: [(status: Int, chunks: [String])]) {
        self.bodies = Mutex(bodies)
    }

    var requests: [URLRequest] { opened.withLock { $0 } }

    func open(_ request: URLRequest) async throws -> (HTTPURLResponse, AsyncThrowingStream<[UInt8], any Error>) {
        opened.withLock { $0.append(request) }
        guard let (status, chunks) = bodies.withLock({ $0.isEmpty ? nil : $0.removeFirst() }) else { throw URLError(.cannotConnectToHost) }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/event-stream"])!
        return (response, AsyncThrowingStream { continuation in
            for chunk in chunks { continuation.yield(Array(chunk.utf8)) }
            continuation.finish()
        })
    }
}

@Suite("Server-sent events")
struct TracksideEventsTests {
    private func parse(_ chunks: [String], lastEventID: String? = nil) -> (events: [ServerSentEvent], parser: ServerSentEventParser) {
        var parser = ServerSentEventParser(lastEventID: lastEventID)
        let events = chunks.flatMap { parser.push(Array($0.utf8)) }
        return (events, parser)
    }

    @Test("comments are skipped, data lines join, and a blank line ends each event")
    func basics() {
        let (events, parser) = parse([
            ": keep-alive\n\n",
            "id: 918301\nevent: changed\ndata: {\"seq\": 918301}\n\n",
            "event: hello\ndata: {\"server_id\": \"x\",\ndata:  \"seq\": 1}\n\n",
        ])
        #expect(events == [
            ServerSentEvent(id: "918301", event: "changed", data: #"{"seq": 918301}"#),
            ServerSentEvent(id: "918301", event: "hello", data: "{\"server_id\": \"x\",\n \"seq\": 1}"),
        ])
        #expect(parser.lastEventID == "918301")
    }

    @Test("lines end with CR, LF or both, and events arrive in pieces of any size")
    func lineEndings() {
        let body = "\u{FEFF}id: 1\r\ndata: a\r\n\r\nid: 2\rdata: b\r\rdata:c\nevent:resync\n\n"
        let whole = parse([body]).events
        let byteAtATime = parse(body.unicodeScalars.map(String.init)).events
        #expect(whole.map(\.data) == ["a", "b", "c"])
        #expect(whole.map(\.id) == ["1", "2", "2"])
        #expect(whole.last?.event == "resync")
        #expect(byteAtATime == whole)
    }

    @Test("an event with no data isn't one, a bare field name counts as empty, and retry is read")
    func edges() {
        let (events, parser) = parse(["event: changed\n\n", "data\n\n", "retry: 2500\nretry: soon\n"])
        #expect(events == [ServerSentEvent(id: nil, event: "message", data: "")])
        #expect(parser.retry == .milliseconds(2_500))
    }

    @Test("Trackside's events read as what they mean")
    func typed() throws {
        #expect(TracksideEvent(ServerSentEvent(id: "9", event: "hello", data: #"{"server_id": "5f0c", "seq": 9}"#)) == .hello(serverID: "5f0c", seq: 9))
        #expect(TracksideEvent(ServerSentEvent(id: "9", event: "changed", data: #"{"seq": 918301}"#)) == .changed(seq: 918301))
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "resync", data: "{}")) == .resync)
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "library", data: #"{"id": "l1", "state": "unavailable"}"#)) == .library(id: "l1", state: .unavailable))
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "session", data: #"{"reason": "revoked"}"#)) == .revoked)
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "scan", data: "{}")) == .other("scan"))
        let acquisition = try TracksideTesting.decode(TracksideAcquisition.self, APIExamples.acquisition)
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "acquisition", data: APIExamples.acquisition)) == .acquisition(acquisition))
    }

    @Test("a dropped stream is opened again from the last event seen, and a revoked key ends it")
    func reconnects() async throws {
        let stub = StreamStub([
            (200, ["event: hello\nid: 5\ndata: {\"server_id\": \"5f0c\", \"seq\": 5}\n\n", "id: 6\nevent: changed\ndata: {\"seq\": 6}\n\n"]),
            (503, []),
            (200, ["id: 7\nevent: changed\ndata: {\"seq\": 7}\n\n", "event: session\ndata: {\"reason\": \"revoked\"}\n\n"]),
        ])
        let events = TracksideEventStream(client: TracksideTesting.client(StubTracksideTransport(body: "{}")), transport: stub, firstDelay: .zero, longestDelay: .zero)
        var seen: [TracksideEvent] = []
        await #expect(throws: TracksideError.unauthenticated) {
            for try await message in events.messages(after: "4") { seen.append(message.event) }
        }
        #expect(seen == [.hello(serverID: "5f0c", seq: 5), .changed(seq: 6), .changed(seq: 7), .revoked])
        #expect(stub.requests.map { $0.value(forHTTPHeaderField: "Last-Event-ID") } == ["4", "6", "6"])
        let first = try #require(stub.requests.first)
        #expect(first.url?.path() == "/api/v1/events")
        #expect(first.value(forHTTPHeaderField: "Authorization") == "Bearer tsk_9pQsecret")
        #expect(first.value(forHTTPHeaderField: "Accept") == "text/event-stream")
    }

    @Test("a refused key ends the stream instead of trying forever")
    func refused() async {
        let stub = StreamStub([(401, [])])
        let events = TracksideEventStream(client: TracksideTesting.client(StubTracksideTransport(body: "{}")), transport: stub, firstDelay: .zero, longestDelay: .zero)
        await #expect(throws: TracksideError.unauthenticated) {
            for try await _ in events.messages() {}
        }
        #expect(stub.requests.count == 1)
    }

    @Test("a server asking to come back with no wait is still asked again only after the first delay")
    func retryZeroIsClamped() async throws {
        let stub = StreamStub([
            (200, ["retry: 0\nevent: hello\ndata: {\"server_id\": \"5f0c\", \"seq\": 1}\n\n"]),
            (200, ["retry: 0\n\n"]),
            (200, ["event: session\ndata: {\"reason\": \"revoked\"}\n\n"]),
        ])
        let events = TracksideEventStream(client: TracksideTesting.client(StubTracksideTransport(body: "{}")), transport: stub, firstDelay: .milliseconds(100), longestDelay: .seconds(1))
        let started = ContinuousClock.now
        await #expect(throws: TracksideError.unauthenticated) {
            for try await _ in events.messages() {}
        }
        #expect(stub.requests.count == 3)
        // Two reconnects, each waiting at least the first delay: 100 ms, then 200 ms.
        #expect(ContinuousClock.now - started >= .milliseconds(300))
    }
}
