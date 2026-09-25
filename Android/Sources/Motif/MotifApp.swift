import Foundation
import SkipFuse
import SwiftUI

/// A logger for the Motif module.
let logger: Logger = Logger(subsystem: "dev.luke.motif", category: "Motif")

/// The app's root, loaded by `MainActivity` on Android.
/* SKIP @bridge */public struct MotifRootView : View {
    @State var model = AppModel()
    @State var servers: ServerStore
    @State var player: PlayerModel
    @AppStorage("appearance") var appearance = ""
    @Environment(\.scenePhase) var scenePhase

    /* SKIP @bridge */public init() {
        let servers = ServerStore()
        _servers = State(initialValue: servers)
        _player = State(initialValue: PlayerModel(servers: servers))
    }

    public var body: some View {
        RootView()
            .environment(model)
            .environment(servers)
            .environment(player)
            .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
            // While Motif is on screen: where the player has got to, a few times a second, and
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

/// Global application delegate functions.
///
/// These functions can update a shared observable object to communicate app state changes to interested views.
/* SKIP @bridge */public final class MotifAppDelegate : Sendable {
    /* SKIP @bridge */public static let shared = MotifAppDelegate()

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
