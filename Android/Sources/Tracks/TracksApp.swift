import Foundation
import SkipFuse
import SwiftUI

/// A logger for the Tracks module.
let logger: Logger = Logger(subsystem: "dev.luke.tracks", category: "Tracks")

/// The app's root, loaded by `MainActivity` on Android.
/* SKIP @bridge */public struct TracksRootView : View {
    // Held by the process rather than as view state: Compose makes a new TracksRootView whenever
    // it recomposes the activity's content, and new models each time would redraw endlessly.
    let model = AppModels.model
    let servers = AppModels.servers
    let player = AppModels.player
    @AppStorage("appearance") var appearance = ""
    @Environment(\.scenePhase) var scenePhase

    /* SKIP @bridge */public init() {
    }

    public var body: some View {
        RootView()
            .environment(model)
            .environment(servers)
            .environment(player)
            .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
            // While Tracks is on screen: where the player has got to, a few times a second, and
            // every few seconds, the songs it counted, which are also scrobbled to the server.
            .task(id: scenePhase) {
                guard scenePhase == .active else { return }
                await model.load()
                var tick = 0
                while !Task.isCancelled {
                    player.refresh()
                    if tick % 16 == 0 { await keepListens() }
                    tick += 1
                    try? await Task.sleep(for: .milliseconds(500))
                }
            }
    }

    private func keepListens() async {
        let new = model.takeListens()
        guard let client = servers.client else { return }
        for play in new where play.serverID == servers.server?.id {
            guard let songID = play.songID else { continue }
            try? await client.scrobble(songID: songID, at: play.playedAt, submission: true)
        }
    }
}

/// The app's models, one each for the life of the process. The player service outlives the
/// window anyway, so there's nothing to gain from making them per scene.
@MainActor enum AppModels {
    static let model = AppModel()
    static let servers = ServerStore()
    static let player = PlayerModel(servers: servers)
}

/// Global application delegate functions.
///
/// These functions can update a shared observable object to communicate app state changes to interested views.
/* SKIP @bridge */public final class TracksAppDelegate : Sendable {
    /* SKIP @bridge */public static let shared = TracksAppDelegate()

    private init() {
    }

    /* SKIP @bridge */public func onInit() {
        logger.debug("onInit")
    }

    /* SKIP @bridge */public func onLaunch() {
        logger.debug("onLaunch")
    }

    /* SKIP @bridge */public func onResume() {
        logger.debug("onResume")
    }

    /* SKIP @bridge */public func onPause() {
        logger.debug("onPause")
    }

    /* SKIP @bridge */public func onStop() {
        logger.debug("onStop")
    }

    /* SKIP @bridge */public func onDestroy() {
        logger.debug("onDestroy")
    }

    /* SKIP @bridge */public func onLowMemory() {
        logger.debug("onLowMemory")
    }
}
