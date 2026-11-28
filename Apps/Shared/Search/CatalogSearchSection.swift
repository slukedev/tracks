import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import TracksCore

/// The music services' results for a search, in a section of their own after your library:
/// their songs, then albums, artists and playlists, with a chip for each service to narrow
/// them to one. A song your library has comes as your own copy, and one already listed above
/// isn't listed again.
///
/// Typing answers at once above; the services are asked only once typing pauses, a newer
/// search cancels an older one, and what's shown stays until the new answer replaces it.
struct CatalogSearchSection: View {
    let query: String
    /// The song keys of the library rows shown above, so the services don't repeat them.
    let listedKeys: Set<String>
    /// Keeps the search, once a result is acted on.
    var remember: () -> Void = {}

    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    @State private var answer: CatalogSearchAnswer?
    /// The rows of the answer's songs, folded into the library's off the main actor.
    @State private var rows: [Row] = []
    @State private var filter: CatalogServiceAddress?
    @State private var showsAllSongs = false

    /// How many songs show before See All.
    private static let songsShown = 6

    nonisolated struct Row: Identifiable, Sendable {
        let id: String
        let serverID: String
        let track: TracksideCatalogTrack
        /// Your own copy, which plays as it does in your library.
        let owned: LocalTrack?
    }

    var body: some View {
        let streaming = model.streaming
        if streaming.hasServices, !trimmed.isEmpty {
            VStack(alignment: .leading, spacing: Design.Margin.section) {
                VStack(alignment: .leading, spacing: Design.Margin.header) {
                    title
                    if streaming.searchable.count > 1 {
                        chips(streaming)
                    }
                    songs
                }
                .padding(.horizontal, Design.Margin.page)
                shelves
                footnote
            }
            .task(id: SearchKey(query: trimmed, filter: filter)) { await search() }
            .task(id: FoldKey(query: answer?.query, keys: listedKeys)) { await fold() }
            .onChange(of: query) { showsAllSongs = false }
        }
    }

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The answer, when it's for what's typed now.
    private var current: CatalogSearchAnswer? {
        answer?.query.trimmingCharacters(in: .whitespacesAndNewlines) == trimmed ? answer : nil
    }

    private var isAsking: Bool { current == nil }

    // MARK: - Header

    private var title: some View {
        HStack(alignment: .firstTextBaseline) {
            SearchSectionTitle("Music Services")
            Spacer()
            if rows.count > Self.songsShown {
                Button(showsAllSongs ? "Show Less" : "See All") {
                    withAnimation(Design.Motion.row) { showsAllSongs.toggle() }
                }
                .buttonStyle(.plain)
                .font(.subheadline)
                .foregroundStyle(.tint)
            }
        }
    }

    /// All, then each service that can be searched now, by the name your server gives it.
    private func chips(_ streaming: StreamingCatalog) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: Design.Spacing.small) {
                ServiceChip(title: String(localized: "All"), logoKey: nil, isSelected: filter == nil) { choose(nil) }
                ForEach(streaming.searchable) { entry in
                    ServiceChip(title: entry.service.name, logoKey: entry.service.logoKey, isSelected: filter == entry.address) { choose(entry.address) }
                }
            }
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
    }

    private func choose(_ address: CatalogServiceAddress?) {
        guard filter != address else { return }
        withAnimation(Design.Motion.row) { filter = address }
    }

    // MARK: - Songs

    @ViewBuilder
    private var songs: some View {
        if let problem = soleProblem, current?.isEmpty != false {
            Label(problem.message, systemImage: "waveform")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.vertical, Design.Spacing.xSmall)
                .transition(.opacity)
        } else if isAsking, rows.isEmpty {
            LoadingRows(count: 3)
                .transition(.opacity)
        } else if !rows.isEmpty {
            let shown = showsAllSongs ? rows : Array(rows.prefix(Self.songsShown))
            SearchSongGrid(items: shown) { row in
                Button {
                    remember()
                    play(from: row)
                } label: {
                    CatalogTrackLabel(track: row.track, serverID: row.serverID, service: serviceName(of: row))
                        .padding(.vertical, 5)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .contextMenu { CatalogTrackMenu(track: row.track, serverID: row.serverID, title: query) }
            }
            .opacity(isAsking ? 0.6 : 1)
            .animation(Design.Motion.fade, value: isAsking)
        } else if current?.isEmpty == true {
            Text("No results on your services.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.vertical, Design.Spacing.xSmall)
        }
    }

    /// Plays the services' songs from the one chosen. A song you have plays from your
    /// library when the rest is too; with any service's song in the queue, all of it plays
    /// through the relay.
    private func play(from row: Row) {
        let chosen = rows.filter { $0.serverID == row.serverID }
        guard let start = chosen.firstIndex(where: { $0.id == row.id }) else { return }
        if chosen.allSatisfy({ $0.owned != nil }), let owned = row.owned {
            let tracks = chosen.compactMap(\.owned)
            player.play(.local(tracks, startingAt: tracks.firstIndex(of: owned) ?? 0), from: .songs(query))
            return
        }
        let queue = music.relayQueue(chosen.map(\.track), startingAt: start, on: row.serverID, context: TracksideSpeaker.Context(kind: .search, name: trimmed))
        model.playThroughRelay(queue, from: .songs(query))
    }

    /// The service's name on a row, where the results mix services.
    private func serviceName(of row: Row) -> String? {
        guard filter == nil, model.streaming.searchable.count > 1 else { return nil }
        return model.streaming.service(CatalogServiceAddress(serverID: row.serverID, serviceID: row.track.serviceID))?.name
    }

    // MARK: - Albums, artists and playlists

    @ViewBuilder
    private var shelves: some View {
        if let current {
            // Server by server in a steady order, each server's in the services' own.
            let order = model.streaming.serverIDs
            let albums = order.flatMap { serverID in (current.found[serverID]?.albums ?? []).map { CatalogRoute.album($0, on: serverID) } }
            let artists = order.flatMap { serverID in (current.found[serverID]?.artists ?? []).map { CatalogRoute.artist($0, on: serverID) } }
            let playlists = order.flatMap { serverID in (current.found[serverID]?.playlists ?? []).map { CatalogRoute.playlist($0, on: serverID) } }
            if !artists.isEmpty {
                Shelf(title: String(localized: "Artists"), items: Array(artists.prefix(10))) { artist in
                    CatalogArtistTile(route: artist)
                        .simultaneousGesture(TapGesture().onEnded { remember() })
                }
            }
            if !albums.isEmpty {
                Shelf(title: String(localized: "Albums"), items: Array(albums.prefix(10))) { album in
                    CatalogCollectionTile(route: album)
                        .simultaneousGesture(TapGesture().onEnded { remember() })
                }
            }
            if !playlists.isEmpty {
                Shelf(title: String(localized: "Playlists"), items: Array(playlists.prefix(10))) { playlist in
                    CatalogCollectionTile(route: playlist)
                        .simultaneousGesture(TapGesture().onEnded { remember() })
                }
            }
        }
    }

    /// A service that didn't answer in time is said so, once, under the rest.
    @ViewBuilder
    private var footnote: some View {
        if let current, current.found.values.contains(where: { !$0.isComplete }), !current.isEmpty {
            Text("Some services didn't answer in time.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal, Design.Margin.page)
        }
    }

    /// What to say when no server's services could be asked: why, in one calm line.
    private var soleProblem: CatalogUnavailable? {
        guard let current, current.found.isEmpty else { return nil }
        return current.problems.values.first
    }

    // MARK: - Asking

    private func search() async {
        let asked = trimmed
        guard asked.count >= 2 else {
            answer = CatalogSearchAnswer(query: query)
            rows = []
            return
        }
        // Only the asking waits for a pause in typing; the field and the library never do.
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled else { return }
        let found = await model.streaming.search(asked, only: filter.map { [$0] })
        guard !Task.isCancelled, asked == trimmed else { return }
        withAnimation(Design.Motion.row) { answer = found }
    }

    /// Folds the answer's songs into the library's, off the main actor.
    private func fold() async {
        guard let answer = current else {
            if self.answer == nil { rows = [] }
            return
        }
        let index = music.index
        let keys = listedKeys
        let order = model.streaming.serverIDs
        let folded = await Self.fold(answer, order: order, keys: keys, index: index)
        guard !Task.isCancelled, answer.query == self.answer?.query else { return }
        withAnimation(Design.Motion.row) { rows = folded }
    }

    @concurrent
    nonisolated private static func fold(_ answer: CatalogSearchAnswer, order: [String], keys: Set<String>, index: LocalLibraryIndex) async -> [Row] {
        var seen = keys
        var rows: [Row] = []
        for serverID in order {
            guard let found = answer.found[serverID] else { continue }
            let folded = CatalogFold.fold(found.tracks, listedKeys: seen) { songID in
                index.track(id: LocalTrack.id(for: .server(serverID: serverID, songID: songID)))
            }
            for row in folded {
                switch row {
                case .owned(let copy, let track):
                    rows.append(Row(id: "owned.\(copy.id)", serverID: serverID, track: track, owned: copy))
                    if let key = track.songKey { seen.insert(key) }
                case .track(let track):
                    rows.append(Row(id: "\(serverID)/\(track.ref)", serverID: serverID, track: track, owned: nil))
                    if let key = track.songKey { seen.insert(key) }
                }
            }
        }
        return rows
    }

    private struct SearchKey: Equatable {
        let query: String
        let filter: CatalogServiceAddress?
    }

    private struct FoldKey: Equatable {
        let query: String?
        let keys: Set<String>
    }
}

/// A chip that narrows the services' results to one service, or All.
private struct ServiceChip: View {
    let title: String
    let logoKey: String?
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Design.Spacing.xSmall) {
                if let logoKey {
                    ServiceGlyph(logoKey: logoKey)
                        .imageScale(.small)
                }
                Text(verbatim: title)
            }
            .font(.subheadline.weight(.medium))
            .foregroundStyle(isSelected ? AnyShapeStyle(Color.white) : AnyShapeStyle(.primary))
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .frame(minHeight: 34)
            .background(isSelected ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary), in: .capsule)
            // A full-height target around the chip.
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.pressable)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// A service's monochrome glyph, by its key: the asset of that name where the app has one,
/// a generic one otherwise. Never a service's own colours.
struct ServiceGlyph: View {
    let logoKey: String
    /// The glyph's side; a symbol follows the font instead.
    var side: CGFloat = 14

    var body: some View {
        if Self.hasAsset(logoKey) {
            Image(logoKey)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: side, height: side)
                .accessibilityHidden(true)
        } else {
            Image(systemName: "waveform")
                .accessibilityHidden(true)
        }
    }

    private static func hasAsset(_ name: String) -> Bool {
        #if canImport(UIKit)
        UIImage(named: name) != nil
        #else
        NSImage(named: name) != nil
        #endif
    }
}
