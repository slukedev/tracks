import Foundation
import Synchronization
#if canImport(Security)
import Security
#endif

public struct URLSessionTracksideTransport: TracksideTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    /// A session that holds a server's self-signed addresses to their fingerprints.
    public init(pins: TracksidePins) {
        self.session = TracksidePinning.session(pins: pins)
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, response)
    }
}

/// URLSession's side of ``TracksidePins``.
enum TracksidePinning {
    static func session(pins: TracksidePins) -> URLSession {
        #if canImport(Security)
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        return URLSession(configuration: configuration, delegate: Delegate(pins: pins), delegateQueue: nil)
        #else
        URLSession(configuration: .default)
        #endif
    }

    #if canImport(Security)
    final class Delegate: NSObject, URLSessionTaskDelegate, Sendable {
        let pins: TracksidePins

        init(pins: TracksidePins) {
            self.pins = pins
        }

        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
            TracksidePinning.answer(challenge, accepts: pins.accepts)
        }

        /// The device key never follows a redirect off the server: a Wi-Fi sign-in page gets
        /// the redirect itself back, and the address fails its proof.
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) async -> URLRequest? {
            ServerSessions.isSameOrigin(task.originalRequest?.url, request.url) ? request : nil
        }
    }

    /// The answer to a challenge, by the pins: the system's own checks for an address that
    /// isn't pinned, the certificate trusted for one whose leaf matches, and the connection
    /// refused for one that doesn't.
    static func answer(_ challenge: URLAuthenticationChallenge, accepts: (_ host: String, _ port: Int?, _ leaf: Data) -> Bool?) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        let space = challenge.protectionSpace
        return answer(method: space.authenticationMethod, host: space.host, port: space.port, trust: space.serverTrust, accepts: accepts)
    }

    static func answer(method: String, host: String, port: Int, trust: SecTrust?, accepts: (_ host: String, _ port: Int?, _ leaf: Data) -> Bool?) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        guard method == NSURLAuthenticationMethodServerTrust, let trust else { return (.performDefaultHandling, nil) }
        let leaf = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
        let der = leaf.map { SecCertificateCopyData($0) as Data } ?? Data()
        switch accepts(host, port, der) {
        case nil: return (.performDefaultHandling, nil)
        case true?: return (.useCredential, URLCredential(trust: trust))
        case false?: return (.cancelAuthenticationChallenge, nil)
        }
    }
    #endif
}

/// The pins of every Trackside server Tracks is paired with, for what reaches a server
/// outside its own session: background downloads, the player's streams and covers. A server
/// whose address has a self-signed certificate is proven by its fingerprint there too, and any
/// other certificate at that address is refused.
public final class TracksideMediaTrust: Sendable {
    public static let shared = TracksideMediaTrust()

    private let pins = Mutex<[String: TracksidePins]>([:])

    public init() {}

    /// Holds a server's addresses to its pins, which follow the server's latest addresses.
    public func hold(_ pins: TracksidePins, for serverID: String) {
        self.pins.withLock { $0[serverID] = pins }
    }

    public func release(_ serverID: String) {
        _ = pins.withLock { $0.removeValue(forKey: serverID) }
    }

    /// Nil when no server pins the address; otherwise whether one pinning it matches.
    public func accepts(host: String, port: Int?, leafCertificate der: Data) -> Bool? {
        let all = pins.withLock { Array($0.values) }
        var pinned = false
        for pins in all {
            switch pins.accepts(host: host, port: port, leafCertificate: der) {
            case true?: return true
            case false?: pinned = true
            case nil: break
            }
        }
        return pinned ? false : nil
    }

    /// Whether the address has a pinned certificate, so the player needs to be told how to
    /// trust it.
    public func isPinned(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host(percentEncoded: false) else { return false }
        return accepts(host: host, port: url.port, leafCertificate: Data()) != nil
    }

    #if canImport(Security)
    public func answer(_ challenge: URLAuthenticationChallenge) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        TracksidePinning.answer(challenge, accepts: accepts)
    }

    /// A session that holds pinned addresses to their fingerprints and treats every other
    /// address as the system does, for covers and other media from any server.
    public func session(configuration: URLSessionConfiguration = .default) -> URLSession {
        URLSession(configuration: configuration, delegate: MediaDelegate(trust: self), delegateQueue: nil)
    }

    private final class MediaDelegate: NSObject, URLSessionDelegate, Sendable {
        let trust: TracksideMediaTrust

        init(trust: TracksideMediaTrust) {
            self.trust = trust
        }

        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
            trust.answer(challenge)
        }
    }
    #endif
}

public struct URLSessionTracksideStreamTransport: TracksideStreamTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public init(pins: TracksidePins) {
        self.session = TracksidePinning.session(pins: pins)
    }

    public func open(_ request: URLRequest) async throws -> (HTTPURLResponse, AsyncThrowingStream<[UInt8], any Error>) {
        let (bytes, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        let (stream, continuation) = AsyncThrowingStream<[UInt8], any Error>.makeStream()
        let reader = Task {
            do {
                var chunk: [UInt8] = []
                for try await byte in bytes {
                    chunk.append(byte)
                    // Handed on a line at a time, so an event isn't held back waiting for more.
                    if byte == 0x0A || chunk.count >= 4_096 {
                        continuation.yield(chunk)
                        chunk.removeAll(keepingCapacity: true)
                    }
                }
                if !chunk.isEmpty { continuation.yield(chunk) }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
        continuation.onTermination = { _ in reader.cancel() }
        return (response, stream)
    }
}
