import Foundation

/// Addresses and errors as they may be written to a log: without anything that signs in.
///
/// A Subsonic address carries the username, a token and its salt, or an API key; a Trackside
/// media address carries a media token; a `URLError` repeats the address that failed. Logged
/// as they are, any of them lets whoever reads the log play from the server. What's left
/// still says which server and which endpoint, which is what a log is read for.
public enum URLRedaction {
    /// Query items that sign a request, by name, compared without case.
    public static let signingItems: Set<String> = [
        "u", "t", "s", "p", "apikey", "mt", "token", "key", "api_key", "access_token", "password", "sig", "signature",
    ]

    static let mask = "redacted"

    /// The address with its user, password and signing items masked, and the rest intact.
    public static func redact(_ url: URL) -> String {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return "<address>"
        }
        if components.user != nil { components.user = mask }
        if components.password != nil { components.password = mask }
        if let items = components.queryItems {
            components.queryItems = items.map { item in
                signingItems.contains(item.name.lowercased()) ? URLQueryItem(name: item.name, value: mask) : item
            }
        }
        components.fragment = nil
        return components.string ?? "<address>"
    }

    /// Any text that may hold addresses, such as an error's description, with each one
    /// redacted.
    public static func redact(text: String) -> String {
        var result = ""
        var rest = Substring(text)
        while let start = rest.range(of: "http", options: .caseInsensitive) {
            result += rest[..<start.lowerBound]
            let tail = rest[start.lowerBound...]
            let end = tail.firstIndex(where: { $0.isWhitespace || $0 == "\"" || $0 == "'" || $0 == "<" || $0 == ">" || $0 == "," || $0 == ")" || $0 == "}" }) ?? tail.endIndex
            let candidate = String(tail[..<end])
            if candidate.contains("://"), let url = URL(string: candidate) {
                result += redact(url)
            } else {
                result += candidate
            }
            rest = tail[end...]
        }
        result += rest
        return result
    }

    /// An error as a log line: its domain and code, and its description with every address
    /// in it redacted. A `URLError`'s failing address is redacted rather than left out.
    public static func describe(_ error: any Error) -> String {
        let ns = error as NSError
        var parts = ["\(ns.domain) \(ns.code)"]
        if let url = (error as? URLError)?.failingURL ?? ns.userInfo[NSURLErrorFailingURLErrorKey] as? URL {
            parts.append(redact(url))
        }
        let detail = redact(text: String(describing: error))
        if !detail.isEmpty { parts.append(detail) }
        return parts.joined(separator: " · ")
    }
}
