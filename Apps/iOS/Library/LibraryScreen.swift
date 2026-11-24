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
                .id(model.musicSource)
                .transition(.opacity)
                .padding(.horizontal, Design.Margin.page)
                .padding(.top, Design.Spacing.xSmall)
                .padding(.bottom, Design.Margin.section)
        }
        .animation(Design.Motion.reduced(Design.Motion.fade, reduceMotion: reduceMotion), value: model.musicSource)
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
        } else {
            switch model.musicSource {
            case .appleMusic:
                LibraryOverview(showsTitle: false)
            case .yourMusic:
                LibraryList(showsTitle: false)
            case .spotify:
                #if SPOTIFY
                SpotifyLibraryList()
                #else
                CollectionMessage(text: String(localized: "Nothing from Spotify yet"), systemImage: nil) {
                    EmptyView()
                }
                #endif
            }
        }
    }
}
