import Foundation

// This device's own stream of a music service through the server (contract section 3): the
// server joins the services as a player for this device and serves what it hears as HLS live.
// Every control goes through the speaker routes with the session's speaker id. Seek, skip,
// resume and a tier change start a new generation with a playlist of its own; the player
// moves to it rather than playing out what it buffered of the old one.

/// One phone's relay.
public struct TracksideRelaySession: Codable, Sendable, Hashable, Identifiable {
    public enum State: String, TracksideOpenEnum {
        /// Joining the services.
        case starting
        /// Joined, with nothing playing.
        case ready
        case playing, paused, ended, unknown
    }

    public enum EndedReason: String, TracksideOpenEnum {
        case idle, closed, replaced, revoked, failed, unknown
        case assistantUnavailable = "assistant_unavailable"
        case serverRestart = "server_restart"
    }

    /// `lossless` on Wi-Fi and wired networks, `cellular` on costly or constrained ones.
    public enum Tier: String, TracksideOpenEnum {
        case lossless, cellular, unknown
    }

    public struct Stream: Codable, Sendable, Hashable {
        public struct Format: Codable, Sendable, Hashable {
            public var codec: String?
            public var container: String?
            public var sampleRate: Int?
            public var bitDepth: Int?
            public var bitrateKbps: Int?
            public var channels: Int?

            public init(codec: String? = nil, container: String? = nil, sampleRate: Int? = nil, bitDepth: Int? = nil, bitrateKbps: Int? = nil, channels: Int? = nil) {
                self.codec = codec
                self.container = container
                self.sampleRate = sampleRate
                self.bitDepth = bitDepth
                self.bitrateKbps = bitrateKbps
                self.channels = channels
            }

            enum CodingKeys: String, CodingKey {
                case codec, container, channels
                case sampleRate = "sample_rate"
                case bitDepth = "bit_depth"
                case bitrateKbps = "bitrate_kbps"
            }

            public init(from decoder: any Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                codec = try? container.decodeIfPresent(String.self, forKey: .codec)
                self.container = try? container.decodeIfPresent(String.self, forKey: .container)
                sampleRate = try? container.decodeIfPresent(Int.self, forKey: .sampleRate)
                bitDepth = try? container.decodeIfPresent(Int.self, forKey: .bitDepth)
                bitrateKbps = try? container.decodeIfPresent(Int.self, forKey: .bitrateKbps)
                channels = try? container.decodeIfPresent(Int.self, forKey: .channels)
            }

            /// What the quality badge shows: read from the format, never assumed from the tier.
            public var audioFormat: AudioFormat? {
                guard let codec else { return nil }
                return AudioFormat(codec: AudioFormat.codec(forExtension: codec), sampleRate: sampleRate, bitDepth: bitDepth, bitRate: bitrateKbps)
            }
        }

        public var format: Format?
        /// The live generation's multivariant playlist, or the next one's when none is live:
        /// an absolute path, without the media token.
        public var hls: String

        public init(format: Format? = nil, hls: String) {
            self.format = format
            self.hls = hls
        }

        enum CodingKeys: String, CodingKey {
            case format, hls
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            format = try? container.decodeIfPresent(Format.self, forKey: .format)
            hls = try container.decode(String.self, forKey: .hls)
        }
    }

    public var id: String
    public var deviceID: String?
    /// The speaker every control goes to: the same for every session of this device.
    public var speakerID: String
    public var state: State
    public var endedReason: EndedReason?
    /// The epoch the stream is in, from 1; 0 before the first audio.
    public var generation: Int
    public var stream: Stream?
    public var tier: Tier
    /// About how far behind the services the phone plays, for display maths only.
    public var latencyMS: Int
    public var idleTimeoutS: Int?
    public var fetchTimeoutS: Int?
    public var createdAt: Date?

    public init(id: String, speakerID: String, state: State, generation: Int, stream: Stream?, tier: Tier = .lossless, latencyMS: Int = 4_000, endedReason: EndedReason? = nil) {
        self.id = id
        self.speakerID = speakerID
        self.state = state
        self.generation = generation
        self.stream = stream
        self.tier = tier
        self.latencyMS = latencyMS
        self.endedReason = endedReason
    }

    enum CodingKeys: String, CodingKey {
        case id, state, generation, stream, tier
        case deviceID = "device_id"
        case speakerID = "speaker_id"
        case endedReason = "ended_reason"
        case latencyMS = "latency_ms"
        case idleTimeoutS = "idle_timeout_s"
        case fetchTimeoutS = "fetch_timeout_s"
        case createdAt = "created_at"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id).lowercased()
        deviceID = (try? container.decodeIfPresent(String.self, forKey: .deviceID))?.lowercased()
        speakerID = try container.decode(String.self, forKey: .speakerID).lowercased()
        state = (try? container.decodeIfPresent(State.self, forKey: .state)) ?? .unknown
        endedReason = try? container.decodeIfPresent(EndedReason.self, forKey: .endedReason)
        generation = (try? container.decodeIfPresent(Int.self, forKey: .generation)) ?? 0
        stream = try? container.decodeIfPresent(Stream.self, forKey: .stream)
        tier = (try? container.decodeIfPresent(Tier.self, forKey: .tier)) ?? .unknown
        latencyMS = (try? container.decodeIfPresent(Int.self, forKey: .latencyMS)) ?? 4_000
        idleTimeoutS = try? container.decodeIfPresent(Int.self, forKey: .idleTimeoutS)
        fetchTimeoutS = try? container.decodeIfPresent(Int.self, forKey: .fetchTimeoutS)
        createdAt = try? container.decodeIfPresent(Date.self, forKey: .createdAt)
    }

    public var hasEnded: Bool { state == .ended }

    public var latency: TimeInterval { TimeInterval(max(0, latencyMS)) / 1_000 }
}

struct TracksideRelaySessions: Decodable, Sendable {
    let sessions: [TracksideRelaySession]

    enum CodingKeys: String, CodingKey {
        case sessions
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sessions = (try? container.decodeIfPresent([TracksideLenientElement<TracksideRelaySession>].self, forKey: .sessions))?.compactMap(\.value) ?? []
    }
}

/// `POST /relay/sessions`.
public struct TracksideRelayStart: Encodable, Sendable, Hashable {
    /// Played at once, in the same round trip.
    public var play: TracksideSpeakerPlayRequest?
    /// The name the services show for this device.
    public var name: String?
    public var tier: TracksideRelaySession.Tier

    public init(play: TracksideSpeakerPlayRequest? = nil, name: String? = nil, tier: TracksideRelaySession.Tier) {
        self.play = play
        self.name = name
        self.tier = tier
    }

    enum CodingKeys: String, CodingKey {
        case play, name, tier
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(play, forKey: .play)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encode(tier == .unknown ? .lossless : tier, forKey: .tier)
    }
}

struct TracksideRelayTierBody: Encodable {
    let tier: TracksideRelaySession.Tier
}

struct TracksideRelayTransferBody: Encodable {
    let to: String
    let keepPlayingHere: Bool
    let tier: TracksideRelaySession.Tier

    enum CodingKeys: String, CodingKey {
        case to, tier
        case keepPlayingHere = "keep_playing_here"
    }
}

// MARK: - Asking

extension TracksideClient {
    /// Starts this device's relay, or answers with the one it has, playing `play` on it when
    /// given. The same `key` for the same start is answered once.
    /// - Throws: ``TracksideError/conflict(reason:)`` `relay_limit` when too many play now,
    ///   ``TracksideError/unsupported(capability:)`` `relay` or `relay_cellular` when the
    ///   server can't, and ``TracksideError/unavailable(code:retryAfter:)`` `catalog_unavailable`.
    public func startRelay(_ start: TracksideRelayStart, key: String) async throws -> TracksideRelaySession {
        try await send("relay/sessions", method: "POST", body: start, headers: ["Idempotency-Key": key])
    }

    public func relaySession(id: String) async throws -> TracksideRelaySession {
        try await get("relay/sessions/\(id)")
    }

    /// This user's relays, on every device.
    public func relaySessions() async throws -> [TracksideRelaySession] {
        let list: TracksideRelaySessions = try await get("relay/sessions")
        return list.sessions
    }

    /// Moves the relay to another tier. While it plays, that starts a new generation.
    public func setRelayTier(_ tier: TracksideRelaySession.Tier, session id: String) async throws -> TracksideRelaySession {
        try await send("relay/sessions/\(id)", method: "PATCH", body: TracksideRelayTierBody(tier: tier))
    }

    /// Ends the relay: the server leaves the services as this device's player.
    public func endRelay(session id: String) async throws {
        try await perform("relay/sessions/\(id)", method: "DELETE")
    }

    /// Moves what a speaker plays to this device's relay, which is made if there isn't one.
    /// The answer's `relay` is the session to open.
    public func transferHere(from id: String, tier: TracksideRelaySession.Tier, key: String) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)/transfer", method: "POST", body: TracksideRelayTransferBody(to: "this_device", keepPlayingHere: false, tier: tier), headers: ["Idempotency-Key": key])
    }

    /// The address a relay playlist plays from: at the address in use, with the media token.
    public func relayURL(_ session: TracksideRelaySession) -> URL? {
        session.stream.flatMap { mediaURL($0.hls) }
    }
}
