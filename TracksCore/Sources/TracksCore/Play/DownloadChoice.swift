import Foundation

public enum DownloadService: String, Sendable, CaseIterable, Codable {
    case appleMusic, yourMusic
}

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

public enum AppleMusicReach: Sendable, Equatable {
    case ready
    case unavailable
    case notInCatalog
}

public enum YourMusicReach: Sendable, Equatable {
    case ready
    case notSetUp
    case offline
    case cannotGet
}

public enum AppleMusicHolding: Sendable, Equatable {
    case unknown
    case missing
    case adding
    case inLibrary
    case downloaded
    case failed(String)
}

public enum YourMusicHolding: Sendable, Equatable {
    case missing
    case partial
    case fetching(Double?)
    case held
    case failed(String)
}

public struct DownloadOption: Sendable, Equatable {
    public enum Action: Sendable, Equatable {
        case keep
        case keepRest
        case openInMusic
        case retry
        case inProgress(Double?)
        case done
        case partlyDone
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

    public var keepsSomething: Bool {
        switch action {
        case .keep, .keepRest, .retry: true
        default: false
        }
    }
}

public struct DownloadPlan: Sendable, Equatable {
    public let options: [DownloadOption]
    public let tap: Tap?

    public enum Tap: Sendable, Equatable {
        case perform(DownloadService)
        case choose
    }

    public var isEmpty: Bool { options.isEmpty }

    public func option(for service: DownloadService) -> DownloadOption? {
        options.first { $0.service == service }
    }

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
