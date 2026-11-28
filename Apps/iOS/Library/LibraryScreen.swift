import SwiftUI
import TracksCore

/// The Library tab: one library across every source, narrowed by the source filter under the
/// title. Playlists, artists, albums and songs, then what came in lately.
struct LibraryScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var addsSource = false

    var body: some View {
        ScrollView {
            content
                .id(LibraryKey(source: model.musicSource, selection: model.effectiveSourceSelection))
                .transition(.opacity)
                .padding(.horizontal, Design.Margin.page)
                .padding(.top, Design.Spacing.xSmall)
                .padding(.bottom, Design.Margin.section)
        }
        .animation(Design.Motion.reduced(Design.Motion.fade, reduceMotion: reduceMotion), value: LibraryKey(source: model.musicSource, selection: model.effectiveSourceSelection))
        .tabRoot("Library")
        .sourceFilter()
        .sheet(isPresented: $addsSource) {
            NavigationStack {
                AddSourcePage()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel", role: .cancel) { addsSource = false }
                        }
                    }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        let sources = model.sourceEntries.filter(\.canPlay)
        if sources.isEmpty {
            CollectionMessage(text: String(localized: "No Music Yet"), systemImage: "square.stack") {
                Button("Add Source") { addsSource = true }
                    .buttonStyle(.glassProminent)
            }
        } else if model.effectiveSourceSelection == .all, players.count > 1 {
            // Every source, each library under its name, in Play From order.
            VStack(alignment: .leading, spacing: Design.Margin.section) {
                ForEach(players) { player in
                    VStack(alignment: .leading, spacing: Design.Spacing.small) {
                        Text(verbatim: name(of: player))
                            .font(.title2.bold())
                            .accessibilityAddTraits(.isHeader)
                        library(for: player)
                    }
                }
            }
        } else {
            library(for: model.musicSource)
        }
    }

    /// The players with music, your own first, as Play From ranks them by default.
    private var players: [MusicSource] {
        let ranked: [MusicSource] = [.yourMusic, .appleMusic]
        let present = Set(model.playerSources)
        return ranked.filter(present.contains)
    }

    /// Your own music by where it is: "On This iPhone", "Home", "On This iPhone and Home".
    private func name(of player: MusicSource) -> String {
        guard player == .yourMusic else { return player.name }
        let own = model.sourceEntries.filter { $0.canPlay && ($0.id == .device || $0.id.isServer) }
        return own.isEmpty ? player.name : own.map(\.name).formatted(.list(type: .and))
    }

    @ViewBuilder
    private func library(for source: MusicSource) -> some View {
        switch source {
        case .appleMusic:
            LibraryOverview(showsTitle: false)
        case .yourMusic:
            LibraryList(showsTitle: false)
        }
    }
}

private struct LibraryKey: Hashable {
    let source: MusicSource
    let selection: SourceSelection
}
