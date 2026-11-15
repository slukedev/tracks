import Foundation
import Synchronization
@testable import TracksCore

/// Answers each request with whatever the test says, and remembers what was asked.
final class StubTracksideTransport: TracksideTransport {
    typealias Answer = (status: Int, body: String, headers: [String: String])

    private let answer: @Sendable (URLRequest) async throws -> Answer
    private let asked = Mutex<[URLRequest]>([])

    init(_ answer: @escaping @Sendable (URLRequest) async throws -> Answer) {
        self.answer = answer
    }

    convenience init(status: Int = 200, body: String, headers: [String: String] = [:]) {
        self.init { _ in (status, body, headers) }
    }

    var requests: [URLRequest] { asked.withLock { $0 } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        asked.withLock { $0.append(request) }
        let (status, body, headers) = try await answer(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        return (Data(body.utf8), response)
    }
}

enum TracksideTesting {
    static let home = URL(string: "http://192.168.1.20:4747")!
    static let serverUUID = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"
    static let mediaToken = TracksideMediaToken(token: "eyJ1Ijoi", expiresAt: Date(timeIntervalSince1970: 1_900_000_000))

    static let apiKey = "tsk_9pQsecret"
    static let deviceKey = TracksideEndpointProof.Key.device(id: "d4k2m9q7x1v8b3n6c0de", apiKey: apiKey)

    /// What the real server answers to a challenge at `url`.
    static func proven(_ url: URL, _ query: [URLQueryItem], key: TracksideEndpointProof.Key = deviceKey, serverID: String = serverUUID) -> TracksidePing {
        let nonce = query.first { $0.name == "challenge" }?.value ?? ""
        return TracksidePing(serverID: serverID, proof: TracksideEndpointProof.expected(key: key, serverID: serverID, url: url, nonce: nonce))
    }

    static func endpoints(base: URL = home) -> ServerEndpoints {
        ServerEndpoints(serverUUID: serverUUID, candidates: [TracksideServerURL(url: base, kind: .lan)], current: base, proofKey: deviceKey) { candidate, _, query in
            proven(candidate.url, query)
        }
    }

    static func client(_ transport: StubTracksideTransport, base: URL = home, mediaToken: TracksideMediaToken? = mediaToken) -> TracksideClient {
        TracksideClient(serverID: "home", endpoints: endpoints(base: base), apiKey: apiKey, mediaToken: mediaToken, transport: transport)
    }

    static func query(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }) { first, _ in first }
    }

    static func decode<Value: Decodable>(_ type: Value.Type, _ json: String) throws -> Value {
        try TracksideJSON.decoder.decode(type, from: Data(json.utf8))
    }

    static func date(_ text: String) -> Date {
        TracksideJSON.date(from: text)!
    }
}

/// The server's wire fixtures, which its own tests check its types against. `Fixtures/` is a
/// copy of `crates/trackside-api/tests/fixtures` from slukedev/tracks-companion, without the
/// dashboard's `admin/` shapes; re-copy it when they change. `TRACKSIDE_FIXTURES` points the
/// tests at another copy, such as a server checkout.
enum TracksideFixtures {
    static let directory: URL? = {
        if let path = ProcessInfo.processInfo.environment["TRACKSIDE_FIXTURES"] {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        let copy = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appending(path: "Fixtures", directoryHint: .isDirectory)
        return FileManager.default.fileExists(atPath: copy.path) ? copy : nil
    }()

    static var files: [URL] {
        guard let directory, let found = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else { return [] }
        return found.compactMap { $0 as? URL }.filter { $0.pathExtension == "json" }.sorted { $0.path < $1.path }
    }
}

/// A folder of its own for each test, removed afterwards.
struct TemporaryFolder: ~Copyable {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appending(path: "tracks-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }
}
