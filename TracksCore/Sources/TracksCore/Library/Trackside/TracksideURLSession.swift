import Foundation
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
        URLSession(configuration: .default, delegate: Delegate(pins: pins), delegateQueue: nil)
        #else
        URLSession(configuration: .default)
        #endif
    }

    #if canImport(Security)
    final class Delegate: NSObject, URLSessionDelegate, Sendable {
        let pins: TracksidePins

        init(pins: TracksidePins) {
            self.pins = pins
        }

        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
            let space = challenge.protectionSpace
            guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust, let trust = space.serverTrust else {
                return (.performDefaultHandling, nil)
            }
            let leaf = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
            let der = leaf.map { SecCertificateCopyData($0) as Data } ?? Data()
            switch pins.accepts(host: space.host, port: space.port, leafCertificate: der) {
            case nil: return (.performDefaultHandling, nil)
            case true?: return (.useCredential, URLCredential(trust: trust))
            case false?: return (.cancelAuthenticationChallenge, nil)
            }
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
