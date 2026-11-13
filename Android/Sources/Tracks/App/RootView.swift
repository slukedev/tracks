import SwiftUI

enum AppTab: String, CaseIterable, Hashable {
    case home, library, search, stats

    var title: String {
        switch self {
        case .home: "Home"
        case .library: "Library"
        case .search: "Search"
        case .stats: "Stats"
        }
    }

    var symbol: Symbol {
        switch self {
        case .home: .home
        case .library: .library
        case .search: .search
        case .stats: .stats
        }
    }
}

/// Four destinations in a Material navigation bar, each with its own stack, and the mini
/// player docked above it.
struct RootView: View {
    @Environment(AppModel.self) var model
    @Environment(ServerStore.self) var servers
    @State var tab: AppTab = .home
    @State var showsNowPlaying = false
    /// Home's pages, so its carousel can open an album.
    @State var homePath: [Route] = []

    var body: some View {
        if !model.isLoaded {
            MorphingLoadingIndicator()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if !servers.isConnected {
            WelcomeScreen()
        } else {
            tabs
                .fullScreenPage(isPresented: $showsNowPlaying) {
                    NowPlayingScreen()
                }
        }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
<<<<<<< HEAD:Android/Sources/Tracks/App/RootView.swift
            NavigationStack {
                HomeScreen().tracksDestinations()
=======
            NavigationStack(path: $homePath) {
                HomeScreen(open: { homePath.append($0) }).tracksDestinations()
>>>>>>> 167ff4b (feat(android): Material 3 Expressive components, encrypted secrets, and screen updates):Android/Sources/Tracks/App/RootView.swift
            }
            .docksPlayer { showsNowPlaying = true }
            .tabItem { Label(AppTab.home.title, systemImage: AppTab.home.symbol.sfSymbol) }
            .tag(AppTab.home)

            NavigationStack {
                LibraryScreen().tracksDestinations()
            }
            .docksPlayer { showsNowPlaying = true }
            .tabItem { Label(AppTab.library.title, systemImage: AppTab.library.symbol.sfSymbol) }
            .tag(AppTab.library)

            NavigationStack {
                SearchScreen().tracksDestinations()
            }
            .docksPlayer { showsNowPlaying = true }
            .tabItem { Label(AppTab.search.title, systemImage: AppTab.search.symbol.sfSymbol) }
            .tag(AppTab.search)

            NavigationStack {
                SummaryScreen().tracksDestinations()
            }
            .docksPlayer { showsNowPlaying = true }
            .tabItem { Label(AppTab.stats.title, systemImage: AppTab.stats.symbol.sfSymbol) }
            .tag(AppTab.stats)
        }
        #if os(Android)
        // Material icons in the bar, filled for the selected destination as Material specifies.
        .composeModifier {
            NavigationBarIconsModifier(
                names: AppTab.allCases.map(\.symbol.materialName).joined(separator: ","),
                selected: AppTab.allCases.firstIndex(of: tab) ?? 0
            )
        }
        #endif
    }
}

extension View {
    /// The mini player under a tab's stack, above the navigation bar, so it stays in place as
    /// pages are pushed.
    func docksPlayer(open: @escaping () -> Void) -> some View {
        VStack(spacing: 0) {
            self.frame(maxHeight: .infinity)
            MiniPlayer(open: open)
        }
    }
}

#if SKIP
struct NavigationBarIconsModifier : ContentModifier {
    let names: String
    let selected: Int

    func modify(view: any View) -> any View {
        let icons = names.split(separator: ",").map { String($0) }
        return view.material3NavigationBar { options in
            options.copy(itemIcon: { index in
                androidx.compose.material3.Icon(
                    imageVector: MaterialIcons.vector(icons[index], filled: index == selected),
                    contentDescription: nil
                )
            })
        }
    }
}
#endif
