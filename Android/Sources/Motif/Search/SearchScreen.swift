import SwiftUI
import MotifStats

/// Your server's music, found as you type: artists, albums and songs.
struct SearchScreen: View {
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @State var query = ""
    @State var results: Loadable<SubsonicSearchResult>?

    var body: some View {
        VStack(spacing: 0) {
            SearchField(text: $query, prompt: "Search your music")
                .padding(.horizontal, Space.screen)
                .padding(.bottom, 8)
            ScrollView {
                content
                    .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .navigationTitle("Search")
        // Waits for a pause in the typing before asking the server.
        .task(id: query) {
            let text = query.trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { results = nil; return }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let client = servers.client else { return }
            if results == nil { results = .loading }
            do {
                let found = try await client.search(text, artists: 6, albums: 12, songs: 30)
                guard !Task.isCancelled else { return }
                results = .loaded(found)
            } catch {
                guard !Task.isCancelled else { return }
                results = .failed(error.localizedDescription)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch results {
        case nil:
            EmptyState(symbol: .search, title: "Search your server", message: "Find artists, albums and songs to play.")
        case .loading:
            LoadingView()
        case .failed(let message):
            FailedView(message: message) { let text = query; query = ""; query = text }
        case .loaded(let found):
            if found.artists.isEmpty && found.albums.isEmpty && found.songs.isEmpty {
                EmptyState(symbol: .search, title: "No matches", message: "Nothing on your server matches “\(query)”.")
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    if !found.songs.isEmpty {
                        sectionTitle("Songs")
                        let songs = found.songs.map { QueueSong($0, serverID: servers.server?.id ?? "") }
                        ForEach(Array(songs.prefix(8).enumerated()), id: \.element.id) { index, song in
                            TrackRow(song: song, isCurrent: player.current?.id == song.id) {
                                player.play(songs, startAt: index, from: "Search")
                            }
                        }
                    }
                    if !found.albums.isEmpty {
                        sectionTitle("Albums")
                        AlbumShelf(albums: found.albums)
                    }
                    if !found.artists.isEmpty {
                        sectionTitle("Artists")
                        ForEach(found.artists) { artist in
                            NavigationLink(value: Route.serverArtist(artist.id)) {
                                ArtistRow(artist: artist)
                                    .padding(.horizontal, Space.screen)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title)
            .materialType(.titleSmall)
            .materialForeground(.primary)
            .padding(.horizontal, Space.screen)
            .padding(.top, 16)
    }
}

/// A Material 3 search bar: a 56 dp pill in `surfaceContainerHigh` with a search icon, the
/// field, and a clear button once there's text.
struct SearchField: View {
    @Binding var text: String
    let prompt: String

    var body: some View {
        HStack(spacing: 0) {
            SymbolImage(.search, role: .onSurfaceVariant)
                .frame(width: Space.touchTarget, height: Space.touchTarget)
                .padding(.leading, 4)
            TextField(prompt, text: $text)
                .materialType(.bodyLarge)
                .autocorrectionDisabled()
                #if !os(macOS)
                .textInputAutocapitalization(.never)
                #endif
                .submitLabel(.search)
                #if os(Android)
                .composeModifier { BorderlessTextFieldModifier() }
                #endif
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    SymbolImage(.close, role: .onSurfaceVariant)
                        .frame(width: Space.touchTarget, height: Space.touchTarget)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
                .padding(.trailing, 4)
            }
        }
        .frame(height: 56)
        .materialBackground(.surfaceContainerHigh, cornerRadius: 28)
    }
}

#if SKIP
import androidx.compose.material3.TextFieldDefaults

/// Strips the field's own container and underline, so it sits inside the search bar's pill.
struct BorderlessTextFieldModifier : ContentModifier {
    func modify(view: any View) -> any View {
        view.material3TextField { options in
            options.copy(
                colors: TextFieldDefaults.colors(
                    focusedContainerColor: androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor: androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor: androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor: androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor: androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor: androidx.compose.ui.graphics.Color.Transparent
                ),
                singleLine: true
            )
        }
    }
}
#endif
