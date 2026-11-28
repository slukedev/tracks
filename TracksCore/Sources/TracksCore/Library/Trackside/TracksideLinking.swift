import Foundation

/// A TV's link code as someone types it: eight letters and digits shown `XXXX-XXXX`.
/// Case doesn't matter and anything but letters and digits is dropped, so a pasted
/// "wdjb mjht" reads as "WDJB-MJHT". The server reads O as 0 and I and L as 1 itself.
public enum TracksideLinkCode {
    public static let length = 8

    /// The letters and digits typed so far, up to eight, in capitals.
    public static func characters(of typed: String) -> String {
        String(typed.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(length))
    }

    /// What the field shows: the dash appears once a fifth character arrives.
    public static func formatted(_ typed: String) -> String {
        let characters = characters(of: typed)
        guard characters.count > 4 else { return characters }
        return "\(characters.prefix(4))-\(characters.dropFirst(4))"
    }

    public static func isComplete(_ typed: String) -> Bool {
        characters(of: typed).count == length
    }
}

/// A `tracks://link?server=<server_id>&code=<user_code>` link, from the QR code a TV shows
/// while it waits to be approved.
public struct TracksideLinkRequest: Sendable, Hashable {
    /// The Trackside server's `server_id`.
    public var serverID: String
    /// As ``TracksideLinkCode/formatted(_:)`` shows it.
    public var code: String

    public init(serverID: String, code: String) {
        self.serverID = serverID
        self.code = TracksideLinkCode.formatted(code)
    }

    /// Nil for any other link, or one without a whole code.
    public init?(url: URL) {
        guard url.scheme?.lowercased() == "tracks",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.host?.lowercased() == "link"
        else { return nil }
        let items = components.queryItems ?? []
        guard let server = items.first(where: { $0.name == "server" })?.value?.trimmingCharacters(in: .whitespaces), !server.isEmpty,
              let code = items.first(where: { $0.name == "code" })?.value, TracksideLinkCode.isComplete(code)
        else { return nil }
        self.init(serverID: server, code: code)
    }

    /// Whether a server whose `server_id` is `serverUUID` is the one this link is for. Ids
    /// are compared without regard to case.
    public func isFor(serverUUID: String?) -> Bool {
        serverUUID?.caseInsensitiveCompare(serverID) == .orderedSame
    }
}

/// What linking a TV says when it doesn't go through, in one short sentence each.
public enum TracksideLinkWords {
    /// A refusal from looking a code up, approving or denying it, in plain words.
    public static func problem(_ error: any Error) -> String {
        switch error {
        case TracksideError.notFound:
            String(localized: "No TV is showing that code.")
        case TracksideError.linkExpired:
            String(localized: "That code has expired. Get a new one on the TV.")
        case TracksideError.conflict(reason: let reason?) where reason == "link_used":
            String(localized: "That code has been used.")
        case TracksideError.linkLocked:
            String(localized: "Locked after too many wrong tries. Get a new code on the TV.")
        case TracksideError.rateLimited(let retryAfter):
            if let retryAfter, retryAfter > 0 {
                String(localized: "Too many wrong codes. Try again in \(Duration.seconds(retryAfter.rounded(.up)).formatted(.units(allowed: [.minutes, .seconds], width: .wide))).")
            } else {
                String(localized: "Too many wrong codes. Try again in a minute.")
            }
        case TracksideError.forbidden:
            String(localized: "Your account on this server can’t connect a TV.")
        case TracksideError.unauthenticated:
            String(localized: "Your server doesn’t know this device anymore. Pair it again.")
        case let error as URLError where error.code != .cancelled:
            String(localized: "Couldn’t reach your server. Check that you’re online.")
        case TracksideError.unproven:
            String(localized: "Couldn’t reach your server. Check that you’re online.")
        default:
            String(localized: "Your server couldn’t connect the TV. Try again.")
        }
    }

    /// The TV, as the confirmation names it: its model when it said, otherwise its kind.
    public static func kind(of device: TracksideLinkInfo.Device) -> String {
        if let model = device.model?.trimmingCharacters(in: .whitespaces), !model.isEmpty { return model }
        return switch device.platform {
        case "android_tv": String(localized: "Google TV")
        case "android": String(localized: "Android")
        case "ios": String(localized: "iPhone")
        case "macos": String(localized: "Mac")
        case "web": String(localized: "Web Browser")
        default: String(localized: "TV")
        }
    }

    /// "Living Room TV is connected", or for a TV without a name, "Your TV is connected".
    public static func connected(_ name: String) -> String {
        let name = name.trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? String(localized: "Your TV is connected") : String(localized: "\(name) is connected")
    }
}
