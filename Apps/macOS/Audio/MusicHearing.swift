import AppKit
import Observation
import os

@MainActor
@Observable
final class MusicHearing {
    static let shared = MusicHearing()

    nonisolated static let choiceKey = "hearsAppleMusic"
    nonisolated static let refusedKey = "hearsAppleMusicRefused"

    enum Choice: String {
        case on
        case off
    }

    private(set) var choice: Choice?
    private(set) var isRefused: Bool
    @ObservationIgnored private var asker: UUID?
    @ObservationIgnored private var isDemo = false
    @ObservationIgnored private let defaults: UserDefaults

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        choice = defaults.string(forKey: Self.choiceKey).flatMap(Choice.init(rawValue:))
        isRefused = defaults.bool(forKey: Self.refusedKey)
        AppleMusicListener.shared.observeAccess { access in
            MusicHearing.shared.found(access)
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            AppleMusicListener.shared.restart()
        }
        updateListener()
    }

    var isOn: Bool {
        get { choice == .on }
        set { choose(newValue ? .on : .off) }
    }

    func choose(_ choice: Choice) {
        self.choice = choice
        defaults.set(choice.rawValue, forKey: Self.choiceKey)
        asker = nil
        updateListener()
    }

    func claimsQuestion(_ id: UUID) -> Bool {
        guard choice == nil, !isDemo else { return false }
        if asker == nil { asker = id }
        return asker == id
    }

    func releaseQuestion(_ id: UUID) {
        if asker == id { asker = nil }
    }

    func playerChanged(isDemo: Bool) {
        guard isDemo != self.isDemo else { return }
        self.isDemo = isDemo
        updateListener()
    }

    func openPrivacySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AudioCapture") else { return }
        NSWorkspace.shared.open(url)
    }

    private func found(_ access: AppleMusicListener.Access) {
        let refused = access == .blocked
        guard refused != isRefused else { return }
        isRefused = refused
        defaults.set(refused, forKey: Self.refusedKey)
    }

    private func updateListener() {
        AppleMusicListener.shared.setAllowed(choice == .on && !isDemo)
    }
}
