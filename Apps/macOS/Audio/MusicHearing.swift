import AppKit
import Observation
import os

/// Whether Stage and the backgrounds that move with the music may hear Apple Music as it
/// plays on this Mac (see ``AppleMusicListener``), and whether macOS lets them.
///
/// Off until the person says yes. The first time something that moves with the music opens
/// while Apple Music plays, Tracks asks (see ``View/asksToHearMusic(when:)``); then macOS
/// asks its own question. Said no to either, the visualizer follows each song's preview
/// instead, as on iPhone, and Settings offers the way back.
///
/// Kept on this Mac only: another Mac has its own permission to give.
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

    /// Nil until asked.
    private(set) var choice: Choice?
    /// Whether macOS hasn't let Tracks hear Apple Music, as last found while it played.
    private(set) var isRefused: Bool
    /// The view asking, so two windows that open together ask once.
    @ObservationIgnored private var asker: UUID?
    /// Sample songs make no sound, so the demo never asks or listens.
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

    /// Whether `id` should ask now: nobody's answered, and no other view is asking.
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

    /// The pane in System Settings where Tracks can be let to hear other apps.
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
