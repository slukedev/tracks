import SwiftUI
import TracksCore

/// Play's page as the source filter has it: one source's own page, or, with every source on
/// and more than one with music, one page built from what each source offers.
struct PlayPage: View {
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    #if os(iOS)
    @AppStorage(OfflineMode.storageKey) private var isOfflineModeOn = false
    #endif

    var body: some View {
        let players = Set(model.playerSources)
        if model.effectiveSourceSelection == .all, players.count > 1 {
            merged(players)
        } else {
            page(for: model.musicSource)
        }
    }

    /// Apple Music's page leads where it can play, with the others' shelves in each section;
    /// offline, your own music's, which has what still plays.
    @ViewBuilder
    private func merged(_ players: Set<MusicSource>) -> some View {
        if players.contains(.appleMusic), !isOffline {
            PlayScreen(merging: players.subtracting([.appleMusic]))
        } else if players.contains(.yourMusic) {
            YourMusicScreen()
        } else {
            page(for: .appleMusic)
        }
    }

    @ViewBuilder
    private func page(for source: MusicSource) -> some View {
        switch source {
        case .yourMusic:
            YourMusicScreen()
        case .appleMusic:
            appleMusic
        }
    }

    @ViewBuilder
    private var appleMusic: some View {
        #if os(iOS)
        AppleMusicPlayScreen()
        #else
        PlayScreen()
        #endif
    }

    private var isOffline: Bool {
        #if os(iOS)
        isOfflineModeOn || !music.network.isOnline
        #else
        !music.network.isOnline
        #endif
    }
}
