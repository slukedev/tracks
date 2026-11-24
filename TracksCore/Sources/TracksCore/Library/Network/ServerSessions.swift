import Foundation

/// The URLSessions music servers are asked through.
///
/// A redirect is followed only to the same server: same scheme, host and port. Anywhere else
/// (a Wi-Fi network's sign-in page, a proxy, a server moved without saying) is answered with
/// the redirect itself, so nothing that signs a request is carried there, and the caller can
/// tell a captive portal from a server that's down.
public enum ServerSessions {
    public static let api: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 120
        configuration.waitsForConnectivity = false
        // Nothing a server sends is kept on disk: its addresses carry credentials.
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        return URLSession(configuration: configuration, delegate: SameOriginRedirects(), delegateQueue: nil)
    }()

    /// Whether a redirect stays on the server it came from.
    public static func isSameOrigin(_ from: URL?, _ to: URL?) -> Bool {
        guard let from, let to else { return false }
        return from.scheme?.lowercased() == to.scheme?.lowercased()
            && from.host(percentEncoded: false)?.lowercased() == to.host(percentEncoded: false)?.lowercased()
            && (from.port ?? defaultPort(from)) == (to.port ?? defaultPort(to))
    }

    private static func defaultPort(_ url: URL) -> Int {
        url.scheme?.lowercased() == "https" ? 443 : 80
    }
}

/// Refuses redirects that leave the server.
final class SameOriginRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) async -> URLRequest? {
        ServerSessions.isSameOrigin(task.originalRequest?.url, request.url) ? request : nil
    }
}
