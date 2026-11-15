import Foundation

/// Where Download can keep something: Apple Music's library, or your own server.
public enum DownloadService: String, Sendable, CaseIterable, Codable {
    case appleMusic, yourMusic
}

/// Which service Download uses without asking. Ask Each Time by default, which still leads with
/// the last one chosen.
public enum DownloadDefault: String, Sendable, CaseIterable {
    case ask, appleMusic, yourMusic

    public var service: DownloadService? {
        switch self {
        case .ask: nil
        case .appleMusic: .appleMusic
        case .yourMusic: .yourMusic
        }
    }
}

/// Whether Apple Music can keep this item at all.
public enum AppleMusicReach: Sendable, Equatable {
    case ready
    /// Tracks may not use Apple Music, or the account can't play its catalog.
    case unavailable
    /// Apple Music doesn't have it: a song only your server has.
    case notInCatalog
}

/// Whether your server can get this item at all.
public enum YourMusicReach: Sendable, Equatable {
    case ready
    /// No server and no Lidarr: Your Music isn't set up.
    case notSetUp
    /// Set up, but nothing that gets music can be reached right now.
    case offline
    /// Reachable, but nothing set up gets music it doesn't have.
    case cannotGet
}

/// What Apple Music's library has of an item.
public enum AppleMusicHolding: Sendable, Equatable {
    case unknown
    case missing
    case adding
    case inLibrary
    /// In the library and on this device.
    case downloaded
    case failed(String)
}

/// What your server has of an item.
public enum YourMusicHolding: Sendable, Equatable {
    case missing
    /// Some of an album's songs.
    case partial
    case fetching(Double?)
    case held
    case failed(String)
}

/// One row of Download's menu: what it does for its service, or that it's done.
public struct DownloadOption: Sendable, Equatable {
    public enum Action: Sendable, Equatable {
        /// Add to Apple Music's library, or get it on your server.
        case keep
        /// Get the rest of an album your server has some of.
        case keepRest
        /// In Apple Music's library already: open it in Music, which downloads it.
        case openInMusic
        case retry
        /// Under way: shown, not tappable.
        case inProgress(Double?)
        /// Kept already: shown with a tick.
        case done
        /// Some of an album kept, and the rest out of reach.
        case partlyDone
        /// Can't right now: shown dimmed.
        case offline
    }

    public let service: DownloadService
    public let action: Action

    public var isActionable: Bool {
        switch action {
        case .keep, .keepRest, .openInMusic, .retry: true
        case .inProgress, .done, .partlyDone, .offline: false
        }
    }

    /// Whether choosing it keeps something new, as opposed to opening Music or waiting.
    public var keepsSomething: Bool {
        switch action {
        case .keep, .keepRest, .retry: true
        default: false
        }
    }
}

/// What Download offers for one album, song or artist, given what each service can do and
/// already has, and which service you lean on.
public struct DownloadPlan: Sendable, Equatable {
    /// Each service that applies to the item, the one you'd likely choose first.
    public let options: [DownloadOption]
    /// What a single tap does: one service's option, or the menu of both. Nil when nothing
    /// can be done, everything being kept or out of reach.
    public let tap: Tap?

    public enum Tap: Sendable, Equatable {
        case perform(DownloadService)
        case choose
    }

    public var isEmpty: Bool { options.isEmpty }

    public func option(for service: DownloadService) -> DownloadOption? {
        options.first { $0.service == service }
    }

    /// How the control reads at a glance.
    public var summary: Summary {
        let actions = options.map(\.action)
        if actions.contains(where: { if case .inProgress = $0 { true } else { false } }) { return .working }
        if actions.contains(.retry), !actions.contains(.done) { return .failed }
        if !actions.isEmpty, actions.allSatisfy({ $0 == .done || $0 == .offline }), actions.contains(.done) { return .kept }
        if actions.contains(.done) || actions.contains(.partlyDone) { return .partlyKept }
        return .notKept
    }

    public enum Summary: Sendable, Equatable {
        case notKept, partlyKept, working, kept, failed
    }

    /// - Parameters:
    ///   - preferred: the service set to be used without asking, if any.
    ///   - last: the service chosen last, which leads the menu when neither is set.
    public init(
        appleMusic reach: AppleMusicReach,
        appleHolding: AppleMusicHolding,
        yourMusic serverReach: YourMusicReach,
        serverHolding: YourMusicHolding,
        preferred: DownloadService?,
        last: DownloadService?
    ) {
        var options: [DownloadOption] = []
        if let action = Self.appleAction(reach: reach, holding: appleHolding) {
            options.append(DownloadOption(service: .appleMusic, action: action))
        }
        if let action = Self.serverAction(reach: serverReach, holding: serverHolding) {
            options.append(DownloadOption(service: .yourMusic, action: action))
        }
        if let lead = preferred ?? last, options.first?.service != lead,
           let index = options.firstIndex(where: { $0.service == lead }) {
            options.insert(options.remove(at: index), at: 0)
        }
        self.options = options

        let actionable = options.filter(\.isActionable)
        if let preferred, let chosen = options.first(where: { $0.service == preferred }), chosen.keepsSomething {
            tap = .perform(preferred)
        } else if actionable.count == 1 {
            tap = .perform(actionable[0].service)
        } else if actionable.isEmpty {
            tap = nil
        } else {
            tap = .choose
        }
    }

    private static func appleAction(reach: AppleMusicReach, holding: AppleMusicHolding) -> DownloadOption.Action? {
        guard reach == .ready else { return nil }
        switch holding {
        case .unknown, .missing: return .keep
        case .adding: return .inProgress(nil)
        case .inLibrary: return .openInMusic
        case .downloaded: return .done
        case .failed: return .retry
        }
    }

    private static func serverAction(reach: YourMusicReach, holding: YourMusicHolding) -> DownloadOption.Action? {
        switch holding {
        case .held: return reach == .notSetUp ? nil : .done
        case .fetching(let fraction): return .inProgress(fraction)
        case .failed: return reach == .ready ? .retry : (reach == .offline ? .offline : nil)
        case .partial:
            switch reach {
            case .ready: return .keepRest
            case .offline: return .offline
            case .cannotGet: return .partlyDone
            case .notSetUp: return nil
            }
        case .missing:
            switch reach {
            case .ready: return .keep
            case .offline: return .offline
            case .cannotGet, .notSetUp: return nil
            }
        }
    }
}
