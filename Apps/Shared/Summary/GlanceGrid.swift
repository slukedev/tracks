import SwiftUI
import TracksCore

/// Four figures for the range in one panel: artists, songs, what was new and the streak.
/// Plain labels over the figures, as Fitness and Screen Time set them: colour would mean
/// nothing here, since none of them is a category.
struct GlanceGrid: View {
    let summary: StatsSummary
    var columns = 2

    var body: some View {
        Card {
            FigureGrid(figures: figures, columns: columns)
        }
    }

    private var figures: [GlanceFigure] {
        [
            GlanceFigure(title: "Artists", value: Text(summary.uniqueArtistCount.formatted()), number: summary.uniqueArtistCount),
            GlanceFigure(title: "Songs", value: Text(summary.uniqueSongCount.formatted()), number: summary.uniqueSongCount),
            // Over all time every song was new once, so this would only repeat Songs.
            summary.range == .allTime
                ? GlanceFigure(title: "Albums", value: Text(summary.uniqueAlbumCount.formatted()), number: summary.uniqueAlbumCount)
                : GlanceFigure(
                    title: "New to You",
                    value: Text(summary.firstTimeHeardCount.formatted()),
                    number: summary.firstTimeHeardCount,
                    detail: summary.newArtistCount > 0 ? Text("^[\(summary.newArtistCount) new artist](inflect: true)") : nil
                ),
            GlanceFigure(
                title: "Streak",
                value: Text("^[\(summary.streak.current) day](inflect: true)"),
                number: summary.streak.current,
                detail: summary.streak.longest > summary.streak.current
                    ? Text("Best: ^[\(summary.streak.longest) day](inflect: true)")
                    : nil
            ),
        ]
    }
}

/// Figures in rows of `columns`, one column at accessibility text sizes, each row's cells
/// as tall as the tallest.
struct FigureGrid: View {
    let figures: [GlanceFigure]
    var columns = 2
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let columns = typeSize.isAccessibilitySize ? 1 : max(1, self.columns)
        let rows = stride(from: 0, to: figures.count, by: columns).map { Array(figures[$0..<min($0 + columns, figures.count)]) }
        Grid(alignment: .topLeading, horizontalSpacing: Design.Spacing.large, verticalSpacing: Design.Spacing.large) {
            ForEach(rows.indices, id: \.self) { row in
                if row > 0 {
                    Divider().gridCellUnsizedAxes(.horizontal)
                }
                GridRow(alignment: .top) {
                    ForEach(rows[row]) { figure in
                        figure.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if rows[row].count < columns {
                        ForEach(rows[row].count..<columns, id: \.self) { _ in
                            Color.clear.gridCellUnsizedAxes(.vertical)
                        }
                    }
                }
            }
        }
    }
}

/// A label, a figure and an optional line under it.
struct GlanceFigure: View, Identifiable {
    let title: LocalizedStringKey
    let value: Text
    /// The figure as a number, so a change rolls its digits.
    var number: Double?
    var detail: Text?
    var valueFont: Font = .statFigure

    init(title: LocalizedStringKey, value: Text, number: Int? = nil, detail: Text? = nil, valueFont: Font = .statFigure) {
        self.title = title
        self.value = value
        self.number = number.map(Double.init)
        self.detail = detail
        self.valueFont = valueFont
    }

    var id: String { "\(title)" }

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            value
                .font(valueFont)
                .monospacedDigit()
                .contentTransition(number.map { .numericText(value: $0) } ?? .numericText())
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let detail {
                detail
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

#if DEBUG
#Preview("Glance") {
    ScrollView {
        GlanceGrid(summary: .preview())
            .padding(Design.Margin.page)
    }
}

#Preview("Glance, four across") {
    GlanceGrid(summary: .preview(), columns: 4)
        .padding(Design.Margin.page)
        .frame(width: 800)
}

#Preview("Glance, AX5") {
    ScrollView {
        GlanceGrid(summary: .preview(.allTime))
            .padding(Design.Margin.page)
    }
    .dynamicTypeSize(.accessibility5)
}
#endif
