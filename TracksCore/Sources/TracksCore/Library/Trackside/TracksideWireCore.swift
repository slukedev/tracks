import Foundation

// The parts of Trackside's wire that need nothing but Foundation: JSON rules, what's read
// before signing in, addresses and refusals. The rest is in TracksideWire.swift.

/// A string the server may add new values to. One this build doesn't know reads as `unknown`
/// instead of failing the whole answer.
public protocol TracksideOpenEnum: RawRepresentable<String>, Codable, Sendable, Hashable {
    static var unknown: Self { get }
}

extension TracksideOpenEnum {
    public init(from decoder: any Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = Self(rawValue: raw) ?? .unknown
    }
}

/// Trackside's JSON: snake_case keys, and times in RFC 3339 with milliseconds.
public enum TracksideJSON {
    public static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            guard let date = date(from: text) else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Not a time: \(text)"))
            }
            return date
        }
        return decoder
    }()

    public static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(string(from: date))
        }
        return encoder
    }()

    static func date(from text: String) -> Date? {
        if let date = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return date }
        return try? Date(text, strategy: .iso8601)
    }

    /// "2026-11-14T09:30:00.000Z".
    static func string(from date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }
}

// MARK: - Public endpoints

/// `GET /api/v1/ping`, for racing a server's addresses.
public struct TracksidePing: Codable, Sendable, Hashable {
    public var serverID: String
    public var time: Date?
    /// The answer to a challenge, when one was asked (``TracksideEndpointProof``).
    public var proof: String?

    public init(serverID: String, time: Date? = nil, proof: String? = nil) {
        self.serverID = serverID
        self.time = time
        self.proof = proof
    }

    enum CodingKeys: String, CodingKey {
        case time, proof
        case serverID = "server_id"
    }
}

/// `GET /api/v1/capabilities`, which tells Trackside from other servers before signing in.
public struct TracksideCapabilities: Codable, Sendable, Hashable {
    public var product: String
    public var serverID: String?
    public var name: String?
    public var version: String?
    public var api: API?
    public var setupRequired: Bool?
    public var auth: Auth?
    public var media: Media?
    public var features: Features?
    public var subsonic: Subsonic?
    /// Present when the server offers Trackside Cloud.
    public var cloud: Cloud?

    public var isTrackside: Bool { product == "trackside" }

    public struct Cloud: Codable, Sendable, Hashable {
        public var version: Int?
        /// The paths the server keeps, `settings` among them.
        public var entities: [String]?
        /// Changes when the server's data is restored from a backup, so cursors from before
        /// it are refused.
        public var epoch: String?
        public var maxBatch: Int?
        public var maxPage: Int?

        enum CodingKeys: String, CodingKey {
            case version, entities, epoch
            case maxBatch = "max_batch"
            case maxPage = "max_page"
        }
    }

    public struct API: Codable, Sendable, Hashable {
        public var versions: [String]?
        public var current: String?
        public var deprecated: [Deprecation]?
    }

    public struct Deprecation: Codable, Sendable, Hashable {
        public var what: String
        public var since: String?
        public var sunset: String?
        public var use: String?
    }

    public struct Auth: Codable, Sendable, Hashable {
        public var passwordLogin: Bool?
        public var pairing: Bool?
        public var pairRequests: Bool?
        /// A TV can link with a short code that a signed-in device approves.
        public var deviceLink: Bool?

        enum CodingKeys: String, CodingKey {
            case pairing
            case passwordLogin = "password_login"
            case pairRequests = "pair_requests"
            case deviceLink = "device_link"
        }
    }

    public struct Media: Codable, Sendable, Hashable {
        public var transcoding: Bool?
        public var profiles: [String]?
        public var hls: Bool?
        public var suggestedCellularProfile: String?

        enum CodingKeys: String, CodingKey {
            case transcoding, profiles, hls
            case suggestedCellularProfile = "suggested_cellular_profile"
        }
    }

    public struct Features: Codable, Sendable, Hashable {
        public var lyrics: Bool?
        public var uploads: Bool?
        public var events: Bool?
        public var animatedArt: Bool?
        public var cloud: Bool?
        /// The server has the speakers, catalog and relay routes. Whether this user may use
        /// them now is `/me`'s to say.
        public var speakers: Bool?

        enum CodingKeys: String, CodingKey {
            case lyrics, uploads, events, cloud, speakers
            case animatedArt = "animated_art"
        }
    }

    public struct Subsonic: Codable, Sendable, Hashable {
        public var enabled: Bool?
        public var path: String?
    }

    enum CodingKeys: String, CodingKey {
        case product, name, version, api, auth, media, features, subsonic, cloud
        case serverID = "server_id"
        case setupRequired = "setup_required"
    }
}

// MARK: - Signing in and pairing

/// One of the addresses a server can be reached at.
public struct TracksideServerURL: Codable, Sendable, Hashable {
    public enum Kind: String, TracksideOpenEnum {
        case lan, tailnet, `public`, unknown
    }

    public var url: URL
    public var kind: Kind
    public var tls: Bool?
    /// The SHA-256 of a self-signed certificate, for pinning.
    public var fingerprint: String?

    public init(url: URL, kind: Kind, tls: Bool? = nil, fingerprint: String? = nil) {
        self.url = url
        self.kind = kind
        self.tls = tls
        self.fingerprint = fingerprint
    }
}

/// The server as the sign-in answers and `/me` describe it.
public struct TracksideServerInfo: Codable, Sendable, Hashable {
    public var serverID: String
    public var name: String?
    public var version: String?
    public var urls: [TracksideServerURL]?

    enum CodingKeys: String, CodingKey {
        case name, version, urls
        case serverID = "server_id"
    }
}

/// This device, as Tracks introduces it when signing in or pairing.
public struct TracksideDeviceInfo: Codable, Sendable, Hashable {
    /// The app's own device id, the one Trackside Cloud knows this device by
    /// (``DeviceIdentity/current``), so the key signing in makes speaks for it. Signing in
    /// again from the same id replaces this device's key rather than adding another device.
    public var clientDeviceID: String
    public var name: String
    /// "ios" or "macos".
    public var platform: String
    public var model: String?
    public var appName: String?
    public var appVersion: String?

    public init(clientDeviceID: String, name: String, platform: String, model: String? = nil, appName: String? = "Tracks", appVersion: String? = nil) {
        self.clientDeviceID = clientDeviceID
        self.name = name
        self.platform = platform
        self.model = model
        self.appName = appName
        self.appVersion = appVersion
    }

    /// The same device, introduced by `appDeviceID`.
    public func speaking(for appDeviceID: String) -> TracksideDeviceInfo {
        var device = self
        device.clientDeviceID = appDeviceID
        return device
    }

    enum CodingKeys: String, CodingKey {
        case name, platform, model
        case clientDeviceID = "client_device_id"
        case appName = "app_name"
        case appVersion = "app_version"
    }
}

// MARK: - Errors

/// The body of every refusal: `{"error": {"code": ..., "message": ...}}`.
struct TracksideErrorEnvelope: Codable, Sendable {
    let error: Body

    struct Body: Codable, Sendable {
        let code: String
        let message: String?
        let details: Details?
        let requestID: String?

        enum CodingKeys: String, CodingKey {
            case code, message, details
            case requestID = "request_id"
        }
    }

    /// Read field by field, so a detail in a shape this build doesn't expect never hides the
    /// refusal's code.
    struct Details: Codable, Sendable {
        let field: String?
        let reason: String?
        /// With `unsupported`: what the speaker can't do.
        let capability: String?
        let resetsAt: Date?
        let received: Int?
        /// With `https_required`: the server's https addresses, when it has any.
        let httpsURLs: [TracksideServerURL]?
        /// With `slow_down`: the seconds to poll at from now on.
        let intervalS: Int?

        enum CodingKeys: String, CodingKey {
            case field, reason, received, capability
            case resetsAt = "resets_at"
            case httpsURLs = "https_urls"
            case intervalS = "interval_s"
        }

        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            field = try? container.decodeIfPresent(String.self, forKey: .field)
            reason = try? container.decodeIfPresent(String.self, forKey: .reason)
            capability = try? container.decodeIfPresent(String.self, forKey: .capability)
            resetsAt = try? container.decodeIfPresent(Date.self, forKey: .resetsAt)
            received = try? container.decodeIfPresent(Int.self, forKey: .received)
            httpsURLs = (try? container.decodeIfPresent([TracksideLenientElement<TracksideServerURL>].self, forKey: .httpsURLs))?.compactMap(\.value)
            intervalS = try? container.decodeIfPresent(Int.self, forKey: .intervalS)
        }
    }
}

/// An element of a list that reads as nil, instead of failing the list, when it isn't the
/// shape expected.
struct TracksideLenientElement<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: any Decoder) throws {
        value = try? Value(from: decoder)
    }
}
