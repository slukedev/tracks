import Foundation

// The speakers a Trackside server plays to (contract section 1): what each is doing, and the
// commands that change it. Every field but a speaker's id is read on its own, so a value in a
// shape this build doesn't know drops that value, never the speaker or the list.

public struct TracksideSpeaker: Codable, Sendable, Hashable, Identifiable {
    public enum Kind: String, TracksideOpenEnum {
        case speaker, group, phone, unknown
        case stereoPair = "stereo_pair"
    }

    /// How the server reaches it.
    public enum Link: String, TracksideOpenEnum {
        case airplay, cast, sonos, dlna, snapcast, squeezelite, sendspin, relay, other, unknown
        case homeAssistant = "home_assistant"
    }

    public enum Availability: String, TracksideOpenEnum {
        case available, unavailable, unknown
        case poweredOff = "powered_off"
    }

    /// A control is shown only when its capability is listed.
    public enum Capability: String, TracksideOpenEnum {
        case play, pause, seek, queue, shuffle, volume, mute, power, group, transfer, stop, unknown
        case nextPrevious = "next_previous"
        case repeatMode = "repeat"
    }

    public enum Repeat: String, TracksideOpenEnum {
        case off, one, all, unknown
    }

    public struct Playback: Codable, Sendable, Hashable {
        public enum State: String, TracksideOpenEnum {
            case playing, paused, idle, buffering, unknown
        }

        public var state: State
        /// Where it was at ``positionAt``, by the server's clock.
        public var positionMS: Int
        public var positionAt: Date
        public var shuffle: Bool?
        public var repeatMode: Repeat?

        public init(state: State, positionMS: Int, positionAt: Date, shuffle: Bool? = nil, repeatMode: Repeat? = nil) {
            self.state = state
            self.positionMS = positionMS
            self.positionAt = positionAt
            self.shuffle = shuffle
            self.repeatMode = repeatMode
        }

        enum CodingKeys: String, CodingKey {
            case state, shuffle
            case positionMS = "position_ms"
            case positionAt = "position_at"
            case repeatMode = "repeat"
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            state = (try? container.decodeIfPresent(State.self, forKey: .state)) ?? .unknown
            positionMS = (try? container.decodeIfPresent(Int.self, forKey: .positionMS)) ?? 0
            positionAt = (try? container.decodeIfPresent(Date.self, forKey: .positionAt)) ?? .distantPast
            shuffle = try? container.decodeIfPresent(Bool.self, forKey: .shuffle)
            repeatMode = try? container.decodeIfPresent(Repeat.self, forKey: .repeatMode)
        }
    }

    public struct Group: Codable, Sendable, Hashable {
        public enum Role: String, TracksideOpenEnum {
            case leader, member, unknown
        }

        public var leaderID: String?
        /// The leader's first, as the server lists them.
        public var memberIDs: [String]
        public var role: Role
        /// Its members are fixed where the speakers are set up.
        public var isStatic: Bool?

        public init(leaderID: String?, memberIDs: [String], role: Role, isStatic: Bool? = nil) {
            self.leaderID = leaderID
            self.memberIDs = memberIDs
            self.role = role
            self.isStatic = isStatic
        }

        enum CodingKeys: String, CodingKey {
            case role
            case leaderID = "leader_id"
            case memberIDs = "member_ids"
            case isStatic = "static"
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            leaderID = try? container.decodeIfPresent(String.self, forKey: .leaderID)
            memberIDs = (try? container.decodeIfPresent([TracksideLenientElement<String>].self, forKey: .memberIDs))?.compactMap(\.value) ?? []
            role = (try? container.decodeIfPresent(Role.self, forKey: .role)) ?? .unknown
            isStatic = try? container.decodeIfPresent(Bool.self, forKey: .isStatic)
        }
    }

    /// What the queue was started from.
    public struct Context: Codable, Sendable, Hashable {
        public enum Kind: String, TracksideOpenEnum {
            case album, artist, playlist, mix, station, search, catalog, other, unknown
        }

        public var id: String?
        public var kind: Kind
        public var name: String?

        public init(id: String? = nil, kind: Kind, name: String? = nil) {
            self.id = id
            self.kind = kind
            self.name = name
        }

        enum CodingKeys: String, CodingKey {
            case id, kind, name
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try? container.decodeIfPresent(String.self, forKey: .id)
            kind = (try? container.decodeIfPresent(Kind.self, forKey: .kind)) ?? .unknown
            name = try? container.decodeIfPresent(String.self, forKey: .name)
        }
    }

    public struct NowPlaying: Codable, Sendable, Hashable {
        public enum Origin: String, TracksideOpenEnum {
            /// The server's own queue, which it controls.
            case queue
            /// Something else on the speaker: a TV input, another app casting.
            case other
            case unknown
        }

        public var title: String
        public var artist: String?
        public var album: String?
        public var artworkID: String?
        public var durationMS: Int?
        /// The queue item, for jumping to it.
        public var itemID: String?
        public var origin: Origin?
        /// A song in the server's library.
        public var songID: String?
        /// A music service's track: opaque, never built or taken apart.
        public var ref: String?
        public var serviceID: String?
        /// The key Tracks' history groups the song's plays by.
        public var songKey: String?

        public init(title: String, artist: String? = nil, album: String? = nil, artworkID: String? = nil, durationMS: Int? = nil, itemID: String? = nil, origin: Origin? = nil, songID: String? = nil, ref: String? = nil, serviceID: String? = nil, songKey: String? = nil) {
            self.title = title
            self.artist = artist
            self.album = album
            self.artworkID = artworkID
            self.durationMS = durationMS
            self.itemID = itemID
            self.origin = origin
            self.songID = songID
            self.ref = ref
            self.serviceID = serviceID
            self.songKey = songKey
        }

        enum CodingKeys: String, CodingKey {
            case title, artist, album, origin, ref
            case artworkID = "artwork_id"
            case durationMS = "duration_ms"
            case itemID = "item_id"
            case songID = "song_id"
            case serviceID = "service_id"
            case songKey = "song_key"
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            title = (try? container.decodeIfPresent(String.self, forKey: .title)) ?? ""
            artist = try? container.decodeIfPresent(String.self, forKey: .artist)
            album = try? container.decodeIfPresent(String.self, forKey: .album)
            artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
            durationMS = try? container.decodeIfPresent(Int.self, forKey: .durationMS)
            itemID = try? container.decodeIfPresent(String.self, forKey: .itemID)
            origin = try? container.decodeIfPresent(Origin.self, forKey: .origin)
            songID = try? container.decodeIfPresent(String.self, forKey: .songID)
            ref = try? container.decodeIfPresent(String.self, forKey: .ref)
            serviceID = try? container.decodeIfPresent(String.self, forKey: .serviceID)
            songKey = try? container.decodeIfPresent(String.self, forKey: .songKey)
        }

        public var duration: TimeInterval? {
            durationMS.flatMap { $0 > 0 ? TimeInterval($0) / 1_000 : nil }
        }
    }

    /// A phone's own stream through the server, as its owner sees it.
    public struct Relay: Codable, Sendable, Hashable {
        public var sessionID: String?
        public var deviceID: String?
        public var isThisDevice: Bool?

        enum CodingKeys: String, CodingKey {
            case sessionID = "session_id"
            case deviceID = "device_id"
            case isThisDevice = "this_device"
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            sessionID = try? container.decodeIfPresent(String.self, forKey: .sessionID)
            deviceID = try? container.decodeIfPresent(String.self, forKey: .deviceID)
            isThisDevice = try? container.decodeIfPresent(Bool.self, forKey: .isThisDevice)
        }
    }

    public var id: String
    public var name: String
    public var kind: Kind
    public var link: Link
    public var manufacturer: String?
    public var model: String?
    public var availability: Availability
    public var capabilities: Set<Capability>
    public var powered: Bool?
    /// 0 to 100.
    public var volume: Int?
    public var muted: Bool?
    public var group: Group?
    /// 0 to 100, on a group's leader.
    public var groupVolume: Int?
    public var canGroupWith: [String]
    public var playback: Playback?
    public var nowPlaying: NowPlaying?
    public var context: Context?
    public var relay: Relay?
    /// Grows with every change but the position, within one run of the server.
    public var revision: Int64

    public init(id: String, name: String, kind: Kind = .speaker, link: Link = .other, manufacturer: String? = nil, model: String? = nil, availability: Availability = .available, capabilities: Set<Capability> = [], powered: Bool? = nil, volume: Int? = nil, muted: Bool? = nil, group: Group? = nil, groupVolume: Int? = nil, canGroupWith: [String] = [], playback: Playback? = nil, nowPlaying: NowPlaying? = nil, context: Context? = nil, relay: Relay? = nil, revision: Int64 = 0) {
        self.id = id
        self.name = name
        self.kind = kind
        self.link = link
        self.manufacturer = manufacturer
        self.model = model
        self.availability = availability
        self.capabilities = capabilities
        self.powered = powered
        self.volume = volume
        self.muted = muted
        self.group = group
        self.groupVolume = groupVolume
        self.canGroupWith = canGroupWith
        self.playback = playback
        self.nowPlaying = nowPlaying
        self.context = context
        self.relay = relay
        self.revision = revision
    }

    enum CodingKeys: String, CodingKey {
        case id, name, kind, manufacturer, model, availability, capabilities, powered, volume, muted, group, playback, context, relay, revision
        case link = "protocol"
        case groupVolume = "group_volume"
        case canGroupWith = "can_group_with"
        case nowPlaying = "now_playing"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id).lowercased()
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        kind = (try? container.decodeIfPresent(Kind.self, forKey: .kind)) ?? .unknown
        link = (try? container.decodeIfPresent(Link.self, forKey: .link)) ?? .unknown
        manufacturer = try? container.decodeIfPresent(String.self, forKey: .manufacturer)
        model = try? container.decodeIfPresent(String.self, forKey: .model)
        availability = (try? container.decodeIfPresent(Availability.self, forKey: .availability)) ?? .unknown
        let listed = (try? container.decodeIfPresent([TracksideLenientElement<Capability>].self, forKey: .capabilities))?.compactMap(\.value) ?? []
        capabilities = Set(listed.filter { $0 != .unknown })
        powered = try? container.decodeIfPresent(Bool.self, forKey: .powered)
        volume = try? container.decodeIfPresent(Int.self, forKey: .volume)
        muted = try? container.decodeIfPresent(Bool.self, forKey: .muted)
        group = try? container.decodeIfPresent(Group.self, forKey: .group)
        groupVolume = try? container.decodeIfPresent(Int.self, forKey: .groupVolume)
        canGroupWith = (try? container.decodeIfPresent([TracksideLenientElement<String>].self, forKey: .canGroupWith))?.compactMap { $0.value?.lowercased() } ?? []
        playback = try? container.decodeIfPresent(Playback.self, forKey: .playback)
        nowPlaying = try? container.decodeIfPresent(NowPlaying.self, forKey: .nowPlaying)
        context = try? container.decodeIfPresent(Context.self, forKey: .context)
        relay = try? container.decodeIfPresent(Relay.self, forKey: .relay)
        revision = (try? container.decodeIfPresent(Int64.self, forKey: .revision)) ?? 0
    }

    public func can(_ capability: Capability) -> Bool { capabilities.contains(capability) }

    public var isPlaying: Bool { playback?.state == .playing || playback?.state == .buffering }

    public var hasSong: Bool { nowPlaying != nil }

    /// A member of a group plays what its leader plays.
    public var isGroupMember: Bool { group?.role == .member }

    public var isGroupLeader: Bool { group?.role == .leader }

    /// The other speakers playing with this one, when it leads them.
    public var memberIDs: [String] {
        guard let group, group.role == .leader else { return [] }
        return group.memberIDs.map { $0.lowercased() }.filter { $0 != id }
    }

    /// The speaker's own controls aren't there: it can't be reached, or it's off.
    public var isReachable: Bool { availability == .available }

    /// Where it's got to at `serverNow`, by the server's clock: on from the last report while
    /// it plays, never past the end. Nil with nothing loaded.
    public func position(at serverNow: Date) -> TimeInterval? {
        guard let playback, nowPlaying != nil else { return nil }
        let reported = TimeInterval(playback.positionMS) / 1_000
        var position = reported
        if playback.state == .playing {
            position += max(0, serverNow.timeIntervalSince(playback.positionAt))
        }
        if let duration = nowPlaying?.duration { position = min(position, duration) }
        return max(0, position)
    }
}

/// `GET /speakers`.
public struct TracksideSpeakerList: Codable, Sendable, Hashable {
    public enum State: String, TracksideOpenEnum {
        case ok, connecting, unavailable, unknown
    }

    public var speakers: [TracksideSpeaker]
    public var state: State
    /// When they stopped being reachable.
    public var since: Date?

    public init(speakers: [TracksideSpeaker], state: State = .ok, since: Date? = nil) {
        self.speakers = speakers
        self.state = state
        self.since = since
    }

    enum CodingKeys: String, CodingKey {
        case speakers, state, since
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        speakers = (try? container.decodeIfPresent([TracksideLenientElement<TracksideSpeaker>].self, forKey: .speakers))?.compactMap(\.value) ?? []
        state = (try? container.decodeIfPresent(State.self, forKey: .state)) ?? .unknown
        since = try? container.decodeIfPresent(Date.self, forKey: .since)
    }
}

public enum TracksideSpeakersAnswer: Sendable, Equatable {
    /// The list `etag` stands for is still the list.
    case unchanged
    /// - Parameter serverDate: the server's clock as it answered, for live positions.
    case changed(TracksideSpeakerList, etag: String?, serverDate: Date?)
}

/// A command's `202`: what the server expects the speaker to be once it's done.
public struct TracksideSpeakerCommand: Codable, Sendable, Hashable {
    public var action: String?
    public var commandID: String
    /// Past this, with no outcome heard, the expectation is dropped and the speaker read again.
    public var settleBy: Date?
    public var speaker: TracksideSpeaker

    public init(action: String? = nil, commandID: String, settleBy: Date?, speaker: TracksideSpeaker) {
        self.action = action
        self.commandID = commandID
        self.settleBy = settleBy
        self.speaker = speaker
    }

    enum CodingKeys: String, CodingKey {
        case action, speaker
        case commandID = "command_id"
        case settleBy = "settle_by"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        action = try? container.decodeIfPresent(String.self, forKey: .action)
        commandID = try container.decode(String.self, forKey: .commandID)
        settleBy = try? container.decodeIfPresent(Date.self, forKey: .settleBy)
        speaker = try container.decode(TracksideSpeaker.self, forKey: .speaker)
    }
}

/// What a speaker's transport is asked to do.
public enum TracksideSpeakerAction: Sendable, Equatable {
    case play, pause, stop, next, previous
    case seek(TimeInterval)
    case jump(itemID: String)

    var body: TracksideSpeakerPlaybackBody {
        switch self {
        case .play: TracksideSpeakerPlaybackBody(action: "play")
        case .pause: TracksideSpeakerPlaybackBody(action: "pause")
        case .stop: TracksideSpeakerPlaybackBody(action: "stop")
        case .next: TracksideSpeakerPlaybackBody(action: "next")
        case .previous: TracksideSpeakerPlaybackBody(action: "previous")
        case .seek(let position): TracksideSpeakerPlaybackBody(action: "seek", positionMS: Int((max(0, position) * 1_000).rounded()))
        case .jump(let itemID): TracksideSpeakerPlaybackBody(action: "jump", itemID: itemID)
        }
    }
}

struct TracksideSpeakerPlaybackBody: Encodable, Equatable {
    var action: String
    var positionMS: Int?
    var itemID: String?

    enum CodingKeys: String, CodingKey {
        case action
        case positionMS = "position_ms"
        case itemID = "item_id"
    }
}

/// `PATCH /speakers/{id}`: only what's set is sent, as one command.
public struct TracksideSpeakerChange: Codable, Sendable, Hashable {
    public var volume: Int?
    public var muted: Bool?
    public var powered: Bool?
    public var shuffle: Bool?
    public var repeatMode: TracksideSpeaker.Repeat?
    public var groupVolume: Int?

    public init(volume: Int? = nil, muted: Bool? = nil, powered: Bool? = nil, shuffle: Bool? = nil, repeatMode: TracksideSpeaker.Repeat? = nil, groupVolume: Int? = nil) {
        self.volume = volume.map { min(100, max(0, $0)) }
        self.muted = muted
        self.powered = powered
        self.shuffle = shuffle
        self.repeatMode = repeatMode
        self.groupVolume = groupVolume.map { min(100, max(0, $0)) }
    }

    enum CodingKeys: String, CodingKey {
        case volume, muted, powered, shuffle
        case repeatMode = "repeat"
        case groupVolume = "group_volume"
    }
}

struct TracksideSpeakerMembers: Encodable {
    let memberIDs: [String]

    enum CodingKeys: String, CodingKey {
        case memberIDs = "member_ids"
    }
}

/// `POST /speakers/{id}/play`: a queue for the speaker to play.
public struct TracksideSpeakerPlayRequest: Codable, Sendable, Hashable {
    public enum Mode: String, TracksideOpenEnum {
        case replace, next, add, unknown
    }

    /// Exactly one of the two.
    public enum Item: Codable, Sendable, Hashable {
        case song(String)
        case ref(String)

        enum CodingKeys: String, CodingKey {
            case songID = "song_id"
            case ref
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            if let id = try container.decodeIfPresent(String.self, forKey: .songID) {
                self = .song(id)
            } else {
                self = .ref(try container.decode(String.self, forKey: .ref))
            }
        }

        public func encode(to encoder: any Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            switch self {
            case .song(let id): try container.encode(id, forKey: .songID)
            case .ref(let ref): try container.encode(ref, forKey: .ref)
            }
        }
    }

    public var context: TracksideSpeaker.Context?
    public var items: [Item]
    public var mode: Mode
    public var positionMS: Int?
    public var repeatMode: TracksideSpeaker.Repeat?
    public var shuffle: Bool?
    public var startIndex: Int?

    /// The most items one request takes.
    public static let itemLimit = 1_000

    public init(items: [Item], mode: Mode = .replace, startIndex: Int? = nil, position: TimeInterval? = nil, shuffle: Bool? = nil, repeatMode: TracksideSpeaker.Repeat? = nil, context: TracksideSpeaker.Context? = nil) {
        self.items = items
        self.mode = mode
        self.startIndex = startIndex
        self.positionMS = position.map { Int((max(0, $0) * 1_000).rounded()) }
        self.shuffle = shuffle
        self.repeatMode = repeatMode
        self.context = context
    }

    enum CodingKeys: String, CodingKey {
        case context, items, mode, shuffle
        case positionMS = "position_ms"
        case repeatMode = "repeat"
        case startIndex = "start_index"
    }
}

struct TracksideSpeakerTransferBody: Encodable {
    let to: String
    let keepPlayingHere: Bool

    enum CodingKeys: String, CodingKey {
        case to
        case keepPlayingHere = "keep_playing_here"
    }
}

/// `GET /speakers/{id}/queue`: from the song playing on.
public struct TracksideSpeakerQueue: Codable, Sendable, Hashable {
    public struct Item: Codable, Sendable, Hashable, Identifiable {
        public var index: Int
        public var itemID: String
        public var title: String
        public var artist: String?
        public var album: String?
        public var durationMS: Int?
        public var artworkID: String?
        public var songID: String?
        public var ref: String?
        public var serviceID: String?
        public var songKey: String?

        public var id: String { itemID }

        enum CodingKeys: String, CodingKey {
            case index, title, artist, album, ref
            case itemID = "item_id"
            case durationMS = "duration_ms"
            case artworkID = "artwork_id"
            case songID = "song_id"
            case serviceID = "service_id"
            case songKey = "song_key"
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            index = (try? container.decodeIfPresent(Int.self, forKey: .index)) ?? 0
            itemID = try container.decode(String.self, forKey: .itemID)
            title = (try? container.decodeIfPresent(String.self, forKey: .title)) ?? ""
            artist = try? container.decodeIfPresent(String.self, forKey: .artist)
            album = try? container.decodeIfPresent(String.self, forKey: .album)
            durationMS = try? container.decodeIfPresent(Int.self, forKey: .durationMS)
            artworkID = try? container.decodeIfPresent(String.self, forKey: .artworkID)
            songID = try? container.decodeIfPresent(String.self, forKey: .songID)
            ref = try? container.decodeIfPresent(String.self, forKey: .ref)
            serviceID = try? container.decodeIfPresent(String.self, forKey: .serviceID)
            songKey = try? container.decodeIfPresent(String.self, forKey: .songKey)
        }
    }

    public var id: String
    public var currentIndex: Int?
    public var items: [Item]
    public var nextCursor: String?
    public var repeatMode: TracksideSpeaker.Repeat?
    public var shuffle: Bool?
    public var revision: Int64?
    public var total: Int?

    enum CodingKeys: String, CodingKey {
        case id, items, shuffle, revision, total
        case currentIndex = "current_index"
        case nextCursor = "next_cursor"
        case repeatMode = "repeat"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id).lowercased()
        currentIndex = try? container.decodeIfPresent(Int.self, forKey: .currentIndex)
        items = (try? container.decodeIfPresent([TracksideLenientElement<Item>].self, forKey: .items))?.compactMap(\.value) ?? []
        nextCursor = try? container.decodeIfPresent(String.self, forKey: .nextCursor)
        repeatMode = try? container.decodeIfPresent(TracksideSpeaker.Repeat.self, forKey: .repeatMode)
        shuffle = try? container.decodeIfPresent(Bool.self, forKey: .shuffle)
        revision = try? container.decodeIfPresent(Int64.self, forKey: .revision)
        total = try? container.decodeIfPresent(Int.self, forKey: .total)
    }

    /// The songs after the one playing.
    public var upNext: [Item] {
        guard let currentIndex else { return items }
        return items.filter { $0.index > currentIndex }
    }
}

// MARK: - Events

/// SSE `speaker_progress`: a new place in the song, without the rest of the speaker.
public struct TracksideSpeakerProgress: Codable, Sendable, Hashable {
    public var id: String
    public var positionMS: Int
    public var positionAt: Date
    public var revision: Int64?
    public var state: TracksideSpeaker.Playback.State

    public init(id: String, positionMS: Int, positionAt: Date, revision: Int64?, state: TracksideSpeaker.Playback.State) {
        self.id = id
        self.positionMS = positionMS
        self.positionAt = positionAt
        self.revision = revision
        self.state = state
    }

    enum CodingKeys: String, CodingKey {
        case id, revision, state
        case positionMS = "position_ms"
        case positionAt = "position_at"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id).lowercased()
        positionMS = try container.decode(Int.self, forKey: .positionMS)
        positionAt = try container.decode(Date.self, forKey: .positionAt)
        revision = try? container.decodeIfPresent(Int64.self, forKey: .revision)
        state = (try? container.decodeIfPresent(TracksideSpeaker.Playback.State.self, forKey: .state)) ?? .unknown
    }
}

/// SSE `speaker_command`: how a command this device sent went.
public struct TracksideSpeakerCommandOutcome: Codable, Sendable, Hashable {
    public enum Outcome: String, TracksideOpenEnum {
        case done, failed, unknown
    }

    public struct Problem: Codable, Sendable, Hashable {
        public var code: String
        public var message: String?
    }

    public var commandID: String
    public var speakerID: String?
    public var outcome: Outcome
    public var error: Problem?

    public init(commandID: String, speakerID: String?, outcome: Outcome, error: Problem? = nil) {
        self.commandID = commandID
        self.speakerID = speakerID
        self.outcome = outcome
        self.error = error
    }

    enum CodingKeys: String, CodingKey {
        case outcome, error
        case commandID = "command_id"
        case speakerID = "speaker_id"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        commandID = try container.decode(String.self, forKey: .commandID)
        speakerID = (try? container.decodeIfPresent(String.self, forKey: .speakerID))?.lowercased()
        outcome = (try? container.decodeIfPresent(Outcome.self, forKey: .outcome)) ?? .unknown
        error = try? container.decodeIfPresent(Problem.self, forKey: .error)
    }
}

/// SSE `speakers`: whether the server can reach its speakers now.
public struct TracksideSpeakersState: Codable, Sendable, Hashable {
    public var state: TracksideSpeakerList.State
    public var since: Date?

    public init(state: TracksideSpeakerList.State, since: Date? = nil) {
        self.state = state
        self.since = since
    }

    enum CodingKeys: String, CodingKey {
        case state, since
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = (try? container.decodeIfPresent(TracksideSpeakerList.State.self, forKey: .state)) ?? .unknown
        since = try? container.decodeIfPresent(Date.self, forKey: .since)
    }
}

// MARK: - Asking

extension TracksideClient {
    /// The speakers, unless they're still the ones `etag` stands for. Never refused for the
    /// speakers being out of reach: the list says so in its `state`.
    public func speakers(ifNoneMatch etag: String? = nil) async throws -> TracksideSpeakersAnswer {
        var attempt = 1
        while true {
            do {
                let response = try await exchange { base in
                    var request = try Self.request(base, "speakers", authorization: bearer)
                    request.setValue(etag, forHTTPHeaderField: "If-None-Match")
                    return request
                }
                if response.1.statusCode == 304 { return .unchanged }
                let list: TracksideSpeakerList = try Self.decode(response)
                let date = response.1.value(forHTTPHeaderField: "Date").flatMap(TracksideClock.httpDate)
                return .changed(list, etag: response.1.value(forHTTPHeaderField: "ETag"), serverDate: date)
            } catch let error where attempt < Self.readRetry.attempts && Self.isRetryable(error) {
                try await Task.sleep(for: Self.readRetry.delay(after: attempt, jitter: Double.random(in: 0...1)))
                attempt += 1
            }
        }
    }

    public func speaker(id: String) async throws -> TracksideSpeaker {
        try await get("speakers/\(id)")
    }

    /// Up Next on a speaker, from the song playing.
    public func speakerQueue(id: String, cursor: String? = nil, limit: Int = 100) async throws -> TracksideSpeakerQueue {
        var query = ["limit": String(min(500, max(1, limit)))]
        query["cursor"] = cursor
        return try await get("speakers/\(id)/queue", query)
    }

    /// - Parameter retryKey: only when sending again after a network failure, never for a
    ///   repeat someone asked for.
    public func control(speaker id: String, _ action: TracksideSpeakerAction, retryKey: String? = nil) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)/playback", method: "POST", body: action.body, headers: Self.idempotency(retryKey))
    }

    public func change(speaker id: String, _ change: TracksideSpeakerChange, retryKey: String? = nil) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)", method: "PATCH", body: change, headers: Self.idempotency(retryKey))
    }

    /// Groups the speakers in `memberIDs` with `id` as their leader: the whole list of the
    /// others, so an empty one ungroups them all.
    public func group(speaker id: String, with memberIDs: [String], retryKey: String? = nil) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)/members", method: "PUT", body: TracksideSpeakerMembers(memberIDs: memberIDs), headers: Self.idempotency(retryKey))
    }

    /// Takes a speaker out of whatever group it's in.
    public func ungroup(speaker id: String, retryKey: String? = nil) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)/group", method: "DELETE", headers: Self.idempotency(retryKey))
    }

    /// Plays a queue on a speaker. The same `key` for the same queue is answered once.
    public func play(on id: String, _ request: TracksideSpeakerPlayRequest, key: String) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)/play", method: "POST", body: request, headers: ["Idempotency-Key": key])
    }

    /// Moves what one speaker plays to another, where it's got to.
    public func transfer(from id: String, to target: String, keepPlayingHere: Bool = false, key: String) async throws -> TracksideSpeakerCommand {
        try await send("speakers/\(id)/transfer", method: "POST", body: TracksideSpeakerTransferBody(to: target, keepPlayingHere: keepPlayingHere), headers: ["Idempotency-Key": key])
    }

    private static func idempotency(_ key: String?) -> [String: String] {
        key.map { ["Idempotency-Key": $0] } ?? [:]
    }
}

/// The server's clock as the client sees it, for live positions.
public enum TracksideClock {
    /// A `Date` header counts whole seconds, so a difference under this is the header's own
    /// rounding, not a clock that's off.
    public static let tolerance: TimeInterval = 2

    /// How far the server's clock is ahead of this one, from a `Date` header read as the answer
    /// arrived. Zero within the header's own precision.
    public static func skew(serverDate: Date, receivedAt: Date) -> TimeInterval {
        let skew = serverDate.timeIntervalSince(receivedAt)
        return abs(skew) < tolerance ? 0 : skew
    }

    /// "Sat, 14 Nov 2026 09:30:00 GMT".
    static func httpDate(_ text: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: text.trimmingCharacters(in: .whitespaces))
    }
}
