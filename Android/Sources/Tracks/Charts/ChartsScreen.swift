import SwiftUI
import TracksStats

enum ChartKind: String, CaseIterable, Hashable {
    case songs, artists, albums

    var title: String {
        switch self {
        case .songs: "Songs"
        case .artists: "Artists"
        case .albums: "Albums"
        }
    }
}

/// Top songs, artists and albums for a period, with how far each moved since the last one.
struct ChartsScreen: View {
    @State var kind: ChartKind
    @Environment(AppModel.self) var model
    @AppStorage("chartsRange") var range: StatsRange = .month
    @State var rows: [ChartRow] = []
    @State var isLoading = true

    var body: some View {
        VStack(spacing: 0) {
            PrimaryTabs(
                titles: ChartKind.allCases.map(\.title),
                selection: Binding(
                    get: { ChartKind.allCases.firstIndex(of: kind) ?? 0 },
                    set: { kind = ChartKind.allCases[$0] }
                )
            )
            List {
                ForEach(rows) { row in
                    NavigationLink(value: row.route) {
                        ChartRowView(row: row, top: rows.first?.count ?? 1)
                    }
                    .materialListRow()
                }
            }
            .listStyle(.plain)
            .overlay {
                if isLoading {
                    ProgressView()
                } else if rows.isEmpty {
                    EmptyState(symbol: .charts, title: "No plays in this period", message: "Pick a longer period from the menu above.")
                }
            }
        }
        .navigationTitle("Charts")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Picker("Period", selection: $range) {
                        ForEach(StatsRange.allCases, id: \.self) { range in
                            Text(range.title).tag(range)
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        SymbolImage(.calendar, size: 18, role: .onSurfaceVariant)
                        Text(range.title)
                            .materialType(.labelLarge)
                            .materialForeground(.onSurfaceVariant)
                    }
                    .frame(minHeight: Space.touchTarget)
                }
                .accessibilityLabel("Period: \(range.title)")
            }
        }
        .task(id: "\(kind.rawValue)|\(range.rawValue)|\(model.revision)") {
            isLoading = rows.isEmpty
            let (kind, range, history) = (kind, range, model.history)
            let next = await Task.detached(priority: .userInitiated) {
                ChartRow.chart(kind, range: range, history: history)
            }.value
            rows = next
            isLoading = false
        }
    }
}

/// A chart entry, whatever it ranks.
struct ChartRow: Identifiable, Equatable {
    let id: String
    let route: Route
    let rank: Int
    let movement: ChartMovement
    let title: String
    let subtitle: String
    let artworkURL: String?
    let count: Int
    let seconds: TimeInterval
    let isArtist: Bool

    static func chart(_ kind: ChartKind, range: StatsRange, history: ListeningHistory) -> [ChartRow] {
        switch kind {
        case .songs:
            return StatsCalculator.songChart(range: range, history: history).map {
                ChartRow(id: $0.item.id, route: .song($0.item.id), rank: $0.rank, movement: $0.movement,
                         title: $0.item.title, subtitle: $0.item.artistName, artworkURL: $0.item.artworkURL,
                         count: $0.item.count, seconds: $0.item.listeningSeconds, isArtist: false)
            }
        case .artists:
            return StatsCalculator.artistChart(range: range, history: history).map {
                ChartRow(id: $0.item.id, route: .artist($0.item.id), rank: $0.rank, movement: $0.movement,
                         title: $0.item.name, subtitle: $0.item.songCount == 1 ? "1 song" : "\($0.item.songCount) songs",
                         artworkURL: $0.item.artworkURL, count: $0.item.count, seconds: $0.item.listeningSeconds, isArtist: true)
            }
        case .albums:
            return StatsCalculator.albumChart(range: range, history: history).map {
                ChartRow(id: $0.item.id, route: .album($0.item.id), rank: $0.rank, movement: $0.movement,
                         title: $0.item.title, subtitle: $0.item.artistName, artworkURL: $0.item.artworkURL,
                         count: $0.item.count, seconds: $0.item.listeningSeconds, isArtist: false)
            }
        }
    }
}

/// Rank and movement, the item, its plays and a bar for its share of the leader's.
struct ChartRowView: View {
    let row: ChartRow
    let top: Int

    var body: some View {
        HStack(spacing: Space.iconGap) {
            VStack(spacing: 0) {
                Text("\(row.rank)")
                    .materialType(.titleMedium)
                    .materialForeground(.onSurface)
                MovementBadge(movement: row.movement)
            }
            .frame(width: 32)

            if row.isArtist {
                Monogram(name: row.title, size: 56)
            } else {
                Artwork(url: row.artworkURL, size: 56, placeholder: row.route.isAlbum ? .album : .song)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .materialType(.bodyLarge)
                    .materialForeground(.onSurface)
                    .lineLimit(1)
                Text(row.subtitle)
                    .materialType(.bodyMedium)
                    .materialForeground(.onSurfaceVariant)
                    .lineLimit(1)
                ShareBar(fraction: Double(row.count) / Double(max(top, 1)))
                    .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 2) {
                Text(Format.plays(row.count))
                    .materialType(.labelLarge)
                    .materialForeground(.onSurface)
                Text(Format.listening(row.seconds))
                    .materialType(.labelMedium)
                    .materialForeground(.onSurfaceVariant)
            }
        }
        .padding(.vertical, 12)
        .combinedAccessibility()
    }
}

/// Up or down since the last period, new, or unchanged.
struct MovementBadge: View {
    let movement: ChartMovement

    var body: some View {
        switch movement {
        case .up(let places):
            HStack(spacing: 0) {
                SymbolImage(.rankUp, size: 16, role: .tertiary)
                Text("\(places)").materialType(.labelSmall).materialForeground(.tertiary)
            }
            .accessibilityLabel("Up \(places)")
        case .down(let places):
            HStack(spacing: 0) {
                SymbolImage(.rankDown, size: 16, role: .error)
                Text("\(places)").materialType(.labelSmall).materialForeground(.error)
            }
            .accessibilityLabel("Down \(places)")
        case .new:
            Text("NEW")
                .materialType(.labelSmall)
                .materialForeground(.onTertiaryContainer)
                .padding(.horizontal, 4)
                .materialBackground(.tertiaryContainer, cornerRadius: Corner.extraSmall)
        case .same:
            Text("–").materialType(.labelSmall).materialForeground(.onSurfaceVariant)
        case .none:
            EmptyView()
        }
    }
}

extension Route {
    var isAlbum: Bool {
        if case .album = self { return true }
        return false
    }
}

/// Material 3 primary tabs: labels in a row, the selected one in the primary colour over a
/// 3 dp indicator, and a divider under the row.
///
/// Takes titles and an index rather than being generic over the tab type: Skip Fuse can't
/// bridge a view generic over anything but views, and overflows the stack trying.
struct PrimaryTabs: View {
    let titles: [String]
    @Binding var selection: Int

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                    PrimaryTab(title: title, isSelected: index == selection) {
                        withAnimation(.easeOut(duration: 0.2)) { selection = index }
                    }
                }
            }
            Color.clear.frame(height: 1).materialBackground(.surfaceContainerHighest)
        }
    }
}

struct PrimaryTab: View {
    let title: String
    let isSelected: Bool
    let select: () -> Void

    var body: some View {
        Button(action: select) {
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                Text(title)
                    .materialType(.titleSmall)
                    .materialForeground(isSelected ? .primary : .onSurfaceVariant)
                Spacer(minLength: 0)
                Color.clear
                    .frame(width: 48, height: 3)
                    .materialBackground(isSelected ? .primary : .surface, cornerRadius: 1.5)
            }
            .frame(maxWidth: .infinity)
            .frame(height: Space.touchTarget)
            .fullTapArea()
        }
        .buttonStyle(.plain)
        .selectedTrait(isSelected)
    }
}
