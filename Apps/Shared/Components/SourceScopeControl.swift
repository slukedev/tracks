import SwiftUI
import TracksCore

/// Whether the statistics count each source apart, and which one is on show.
///
/// The choice is offered only once the setting is on and plays came from more than Apple
/// Music; until then everything counts together, whatever was chosen before.
struct SourceScopeSetting: DynamicProperty {
    @AppStorage(SourceScope.separatesKey) private var separates = false
    @AppStorage(SourceScope.storageKey) private var chosen: SourceScope = .all
    @Environment(AppModel.self) private var model

    var isOffered: Bool {
        separates && !offered.isEmpty
    }

    /// All, then each source with plays.
    var offered: [SourceScope] {
        SourceScope.offered(played: model.library.history.playedSources)
    }

    /// The plays to count.
    var scope: SourceScope {
        SourceScope.effective(chosen, separates: separates, played: model.library.history.playedSources)
    }

    var selection: Binding<SourceScope> { $chosen }
}

extension SourceScope {
    var title: LocalizedStringKey {
        switch self {
        case .all: "All Sources"
        case .appleMusic: "Apple Music"
        case .yourMusic: "Files and Servers"
        case .spotify: "Spotify"
        }
    }

    /// The same symbols as the music source's, so the two read as one idea.
    var symbol: String {
        switch self {
        case .all: "music.note.list"
        case .appleMusic: MusicSource.appleMusic.symbol
        case .yourMusic: MusicSource.yourMusic.symbol
        case .spotify: MusicSource.spotify.symbol
        }
    }

    /// Radio stations are Apple Music's, so their sessions count only in All and Apple Music.
    var includesStations: Bool { self == .all || self == .appleMusic }

    /// The scope as plain text, for a subtitle. Empty for everything.
    var subtitle: String {
        switch self {
        case .all: ""
        case .appleMusic: String(localized: "Apple Music")
        case .yourMusic: MusicSource.ownMusicName
        case .spotify: String(localized: "Spotify")
        }
    }
}

/// All Sources or one of them, for a statistics page's toolbar. A pop-up on the Mac; on iPhone
/// a menu whose symbol is the scope on show.
struct SourceScopeMenu: View {
    @Binding var scope: SourceScope
    let offered: [SourceScope]

    var body: some View {
        #if os(macOS)
        picker
            .pickerStyle(.menu)
            .fixedSize()
            .help("Count every source, or one alone")
        #else
        Menu {
            picker
        } label: {
            Label("Music", systemImage: scope.symbol)
        }
        #endif
    }

    private var picker: some View {
        Picker("Music", selection: $scope) {
            ForEach(offered) { scope in
                Label(scope.title, systemImage: scope.symbol).tag(scope)
            }
        }
    }
}

extension View {
    /// On iPhone, names the scope under the title while it isn't everything. The Mac's pop-up
    /// already shows it.
    func sourceScopeSubtitle(_ scope: SourceScope) -> some View {
        #if os(iOS)
        navigationSubtitle(scope.subtitle)
        #else
        self
        #endif
    }
}
