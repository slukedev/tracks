import UIKit
import CarPlay
import os
import TracksCore

/// Moving between screens in the car. CarPlay throws, and takes the app down with it, when a
/// screen is pushed twice, a second sheet is presented over the first, or the trail goes past
/// the depth it allows. Every move goes through the connection's `CarMover`: one at a time,
/// each checked against the trail as it runs rather than as it was asked for, since a move can
/// wait behind another for seconds over a wireless connection.
extension CarPlaySceneDelegate {
    /// The most screens an audio app's trail can hold, the tab bar included.
    static let deepest = 5

    /// How many screens can sit under Now Playing and still leave one above it, for Up Next or
    /// the album.
    static let deepestBeforeNowPlaying = deepest - 1

    static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Tracks", category: "CarPlay")

    /// The mover for a car that's just connected, reading that car's trail.
    static func mover(for interface: CPInterfaceController) -> CarMover {
        CarMover(
            trail: { [weak interface] in interface?.templates.map(ObjectIdentifier.init) ?? [] },
            onError: { what, error in
                log.error("CarPlay couldn't \(what, privacy: .public): \(error.localizedDescription, privacy: .public)")
            },
            onStuck: { what in
                log.error("CarPlay hasn't finished \(what, privacy: .public); the row stops waiting")
            }
        )
    }

    /// Pushes a screen. Already on the trail or on its way, or while another move is under way
    /// (a second tap before the first page opened), it's left alone. At CarPlay's depth, the
    /// trail starts again from the tab, under Now Playing when it was there.
    func push(_ template: CPTemplate, completion: (() -> Void)? = nil) {
        guard let interface, let mover, !mover.isOnTrail(ObjectIdentifier(template)) else {
            completion?()
            return
        }
        mover.run("push", { steps in
            if steps.depth >= Self.deepest {
                let nowPlaying = CPNowPlayingTemplate.shared
                let keepsNowPlaying = template !== nowPlaying && interface.templates.contains { $0 === nowPlaying }
                guard try await steps.pop({ _ = try await interface.popToRootTemplate(animated: false) }) else { return }
                if keepsNowPlaying {
                    try await steps.push(ObjectIdentifier(nowPlaying)) { _ = try await interface.pushTemplate(nowPlaying, animated: false) }
                }
            }
            guard steps.depth < Self.deepest else { return }
            try await steps.push(ObjectIdentifier(template)) { _ = try await interface.pushTemplate(template, animated: true) }
        }, completion: { completion?() })
    }

    /// Brings Now Playing up: back to it when it's already on the trail, since CarPlay won't
    /// take the same screen twice. Played from deep in a tab, it starts a fresh trail from the
    /// tab, leaving room above it. Asked for while another move runs, it's shown once that one
    /// is done, if `wanted` still says so then.
    func showNowPlaying(if wanted: @escaping () -> Bool = { true }) {
        guard let interface, let mover else { return }
        mover.run("show Now Playing", whenBusy: .wait("nowPlaying"), { steps in
            guard wanted() else { return }
            let nowPlaying = CPNowPlayingTemplate.shared
            let id = ObjectIdentifier(nowPlaying)
            guard interface.topTemplate !== nowPlaying else { return }
            if interface.templates.contains(where: { $0 === nowPlaying }) {
                try await steps.pop { _ = try await interface.pop(to: nowPlaying, animated: true) }
                return
            }
            guard !steps.isOnTrail(id) else { return }
            if steps.depth >= Self.deepestBeforeNowPlaying {
                guard try await steps.pop({ _ = try await interface.popToRootTemplate(animated: false) }) else { return }
            }
            try await steps.push(id) { _ = try await interface.pushTemplate(nowPlaying, animated: true) }
        })
    }

    /// Goes back a screen.
    func popScreen() {
        guard let interface, let mover else { return }
        mover.run("pop", { steps in
            guard interface.templates.count > 1 else { return }
            try await steps.pop { _ = try await interface.popTemplate(animated: true) }
        })
    }

    /// Goes back to the screen under `page`, wherever it is on the trail. Retried a moment
    /// later, a few times, while CarPlay can't (a push still landing).
    func popAway(_ page: CPTemplate, attempt: Int = 0, then gone: @escaping () -> Void) {
        guard let interface, let mover else { return }
        let popped = Flag()
        mover.run("close a page", whenBusy: .wait("close.\(ObjectIdentifier(page).hashValue)"), { steps in
            guard let index = interface.templates.firstIndex(where: { $0 === page }), index > 0 else {
                popped.value = true
                return
            }
            let below = interface.templates[index - 1]
            popped.value = try await steps.pop { _ = try await interface.pop(to: below, animated: true) }
        }, completion: { [weak self] in
            guard let self else { return }
            if popped.value || interface.templates.contains(where: { $0 === page }) == false { return gone() }
            guard attempt < 3, self.interface === interface else { return }
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(500))
                self?.popAway(page, attempt: attempt + 1, then: gone)
            }
        })
    }

    /// Shows a sheet, closing any sheet already up first: CarPlay shows one at a time. Asked for
    /// while another move runs, it waits, and a newer sheet takes its place.
    func present(_ template: CPTemplate) {
        guard let interface, let mover else { return }
        mover.run("present", whenBusy: .wait("sheet"), { steps in
            if let shown = interface.presentedTemplate {
                guard shown !== template else { return }
                guard try await steps.present({ _ = try await interface.dismissTemplate(animated: false) }) else { return }
            }
            try await steps.present { _ = try await interface.presentTemplate(template, animated: true) }
        })
    }

    /// Closes the sheet that's up, then does what's next.
    func dismissSheet(then next: (() -> Void)? = nil) {
        guard let interface, let mover else {
            next?()
            return
        }
        mover.run("dismiss", whenBusy: .wait("sheet"), { steps in
            guard interface.presentedTemplate != nil else { return }
            try await steps.present { _ = try await interface.dismissTemplate(animated: true) }
        }, completion: { next?() })
    }

    /// An alert's OK: closes it.
    func okAction() -> CPAlertAction {
        CPAlertAction(title: String(localized: "OK"), style: .cancel) { [weak self] _ in
            self?.dismissSheet()
        }
    }
}

/// Whether a move got as far as it meant to, read once it's done.
private final class Flag {
    var value = false
}
