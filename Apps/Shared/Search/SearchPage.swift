import SwiftUI
import TracksCore

/// Search, as a page of its own: the Mac sidebar's first row and the iPhone's Search tab.
/// Before you type, what you searched lately and things to browse; then results from the
/// scope chosen above them: Apple Music, your library, or your history.
struct SearchPage: View {
    @Environment(AppModel.self) private var model
    @FocusState private var isFieldFocused: Bool
    /// Whether the page is the one showing: a hidden tab can't take the cursor.
    @State private var isShowing = false
    #if os(iOS)
    /// Whether the field is in use: the system's scope bar and recent searches show only then.
    @State private var isSearching = LaunchScene.opensSearch || LaunchScene.searchText != nil
    #endif

    var body: some View {
        @Bindable var session = model.search
        let source = model.musicSource
        #if os(macOS)
        results
            .navigationTitle("Search")
            .searchable(text: $session.query, placement: .toolbar, prompt: session.scope.prompt(for: source))
            .modifier(SearchBehaviour(isFieldFocused: $isFieldFocused, isShowing: $isShowing, takeFocus: takeFocusIfAsked))
            // Where the title would be: the scope says what the page is.
            .toolbar(removing: .title)
            .toolbar {
                ToolbarItem(placement: .navigation) { scopePicker }
                ToolbarItem(placement: .primaryAction) { SearchSourceMenu() }
                ToolbarItem(placement: .primaryAction) {
                    identifyButton
                        .popover(isPresented: $session.isIdentifying, arrowEdge: .bottom) {
                            IdentifySongView()
                                .padding(20)
                                .frame(width: 320)
                        }
                }
            }
        #else
        phoneContent
            .searchable(
                text: $session.query,
                isPresented: $isSearching,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: session.scope.prompt(for: source)
            )
            // The system's scope bar, under the field only while searching, as Music's is.
            .searchScopes($session.scope, activation: .onSearchPresentation) {
                ForEach(SearchScope.scopes(for: source)) { scope in
                    Text(scope.title(for: source)).tag(scope)
                }
            }
            .modifier(SearchBehaviour(isFieldFocused: $isFieldFocused, isShowing: $isShowing, takeFocus: takeFocusIfAsked))
            .tabRoot("Search") {
                ToolbarItem(placement: .primaryAction) { identifyButton }
                ToolbarItem(placement: .primaryAction) { SearchSourceMenu() }
            }
            .onChange(of: isSearching) { _, searching in
                // Cancel leaves Search as it opens, rather than on the last results.
                if !searching { session.query = "" }
            }
            .sheet(isPresented: $session.isIdentifying) {
                NavigationStack {
                    ScrollView {
                        IdentifySongView()
                            .padding(.horizontal, Design.Margin.page)
                            .padding(.bottom, Design.Spacing.xxLarge)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .navigationTitle("Identify Song")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close", systemImage: "xmark", role: .close) { session.isIdentifying = false }
                        }
                    }
                }
                .presentationDetents([.medium, .large])
            }
        #endif
    }

    #if os(iOS)
    /// Before the field is touched, things to browse, as Music's Search opens on its
    /// categories; once it is, the scope's recent searches, then results as you type.
    @ViewBuilder
    private var phoneContent: some View {
        if !isSearching, model.search.query.isEmpty, BrowseSections.isOffered(model) {
            ScrollView {
                BrowseSections()
                    .padding(.top, Design.Spacing.small)
                    .padding(.bottom, Design.Margin.section)
            }
        } else {
            results
        }
    }
    #endif

    /// Puts the cursor in the field when something asked for it, once the page is showing.
    /// The request waits while it isn't, for the tab or the page to come forward.
    private func takeFocusIfAsked() {
        guard model.search.wantsFocus, isShowing else { return }
        Task {
            // After the page has settled in the window, or the field isn't there to focus.
            try? await Task.sleep(for: .milliseconds(150))
            isFieldFocused = true
            model.search.wantsFocus = false
        }
    }

    @ViewBuilder
    private var results: some View {
        let session = model.search
        let search: (String) -> Void = { session.query = $0 }
        switch session.scope {
        case .history:
            HistorySearchResults(query: session.query, search: search)
        case .library where model.musicSource == .yourMusic:
            YourMusicSearchResults(query: session.query, search: search)
        case .library:
            #if os(macOS)
            LibrarySearchResults(query: session.query)
            #else
            MusicSearchResults(query: session.query, scope: .library, browses: Self.browsesInResults, search: search)
            #endif
        case .appleMusic:
            MusicSearchResults(query: session.query, scope: .appleMusic, browses: Self.browsesInResults, search: search)
        }
    }

    /// Names the song playing nearby, as Control Center's Music Recognition does.
    private var identifyButton: some View {
        Button("Identify Song", systemImage: "shazam.logo") { model.search.isIdentifying = true }
            .help("Identify the song playing near you")
    }

    #if os(macOS)
    private var scopePicker: some View {
        @Bindable var session = model.search
        let source = model.musicSource
        return Picker("Search In", selection: $session.scope) {
            ForEach(SearchScope.scopes(for: source)) { scope in
                Text(scope.title(for: source)).tag(scope)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .fixedSize()
    }

    /// The Mac's page has no resting state apart from the field, so its blank results browse.
    private static let browsesInResults = true
    #else
    /// The iPhone browses before the field is touched, so its blank results don't.
    private static let browsesInResults = false
    #endif
}

/// What both platforms do around the field: Return keeps the search, the cursor comes when
/// asked, and the scope follows the music source.
private struct SearchBehaviour: ViewModifier {
    @FocusState.Binding var isFieldFocused: Bool
    @Binding var isShowing: Bool
    let takeFocus: () -> Void
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        let session = model.search
        let source = model.musicSource
        content
            .onSubmit(of: .search) { session.remember(source: source) }
            .searchFocused($isFieldFocused)
            .onAppear {
                isShowing = true
                session.keepScope(for: source)
                takeFocus()
            }
            .onDisappear { isShowing = false }
            .onChange(of: source) { session.keepScope(for: source) }
            .onChange(of: session.wantsFocus) { takeFocus() }
    }
}

#if DEBUG
#Preview("Search") {
    PlayPreviewHost { SearchPage() }
}

#Preview("Search, dark, AX5") {
    PlayPreviewHost { SearchPage() }
        .preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility5)
}
#endif

/// Which sources search draws from: all of them, or one, as Play and Library do. Shown once
/// there's more than one.
private struct SearchSourceMenu: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let entries = model.sourceEntries.filter(\.canPlay)
        if entries.count > 1 {
            let selection = model.effectiveSourceSelection
            Menu {
                Picker("Source", selection: Binding(get: { model.effectiveSourceSelection }, set: { model.chooseSources($0) })) {
                    Text("All Sources").tag(SourceSelection.all)
                    Section {
                        ForEach(entries) { entry in
                            Label {
                                Text(verbatim: entry.name)
                            } icon: {
                                SourceGlyph(source: entry.id)
                            }
                            .tag(SourceSelection.only(entry.id))
                        }
                    }
                }
                .pickerStyle(.inline)
            } label: {
                Image(systemName: selection == .all ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill")
            }
            .accessibilityLabel("Source")
            .accessibilityValue(Text(verbatim: entries.first { selection == .only($0.id) }?.name ?? String(localized: "All Sources")))
        }
    }
}
