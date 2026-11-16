import SwiftUI
import TracksCore

enum CategoryTileStyle: String {
    case colour
    case chips = "a"
    case albums = "b"
    case now = "c"
    case cards = "d"

    static var current: CategoryTileStyle {
        #if DEBUG
        UserDefaults.standard.string(forKey: "TracksMoodTiles").flatMap(CategoryTileStyle.init) ?? .colour
        #else
        .colour
        #endif
    }
}

enum CategoryTileRole {
    case colour, chip, album, feature, card
}

extension EnvironmentValues {
    @Entry var categoryTileRole: CategoryTileRole = .colour
}

struct CategorySubject {
    let title: String
    let symbol: String
    let color: Color
    let covers: [CoverArt]
}

struct CategoryLabel: View {
    let subject: CategorySubject
    let height: CGFloat
    var fillsWidth = false
    @Environment(\.categoryTileRole) private var role

    var body: some View {
        switch role {
        case .colour:
            CategoryTile(title: subject.title, symbol: subject.symbol, covers: subject.covers, color: subject.color, height: height, fillsWidth: fillsWidth)
        case .chip:
            CategoryChip(subject: subject)
        case .album:
            TileLabel(title: subject.title, subtitle: nil) { side in
                CategoryArt(subject: subject, side: side)
            }
        case .feature:
            CategoryFeature(subject: subject)
        case .card:
            CategoryCard(subject: subject)
        }
    }
}

struct CategoryRow<Items: RandomAccessCollection, Tile: View>: View where Items.Element: Identifiable {
    let title: String
    let items: Items
    let style: CategoryTileStyle
    var featured: Items.Element.ID?
    @ViewBuilder var tile: (Items.Element) -> Tile
    @ScaledMetric(relativeTo: .subheadline) private var chipHeight: CGFloat = CategoryChip.height
    @ScaledMetric(relativeTo: .subheadline) private var cardHeight: CGFloat = CategoryCard.height

    var body: some View {
        switch style {
        case .colour:
            Shelf(title: title, items: items) { tile($0) }
        case .albums:
            Shelf(title: title, items: items) { tile($0).environment(\.categoryTileRole, .album) }
        case .chips:
            grid(rowHeight: min(chipHeight, 96), role: .chip, feature: nil)
        case .cards:
            grid(rowHeight: min(cardHeight, 120), role: .card, feature: nil)
        case .now:
            grid(rowHeight: min(chipHeight, 96), role: .chip, feature: items.first { $0.id == featured } ?? items.first)
        }
    }

    private func grid(rowHeight: CGFloat, role: CategoryTileRole, feature: Items.Element?) -> some View {
        let rows = Array(repeating: GridItem(.fixed(rowHeight), spacing: Self.rowSpacing), count: 2)
        let rest = items.filter { $0.id != feature?.id }
        return VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.title3.bold())
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, PlayMetrics.margin)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: Self.columnSpacing) {
                    if let feature {
                        tile(feature)
                            .environment(\.categoryTileRole, .feature)
                            .frame(width: ((rowHeight * 2 + Self.rowSpacing) * 1.5).rounded(), height: rowHeight * 2 + Self.rowSpacing)
                    }
                    LazyHGrid(rows: rows, spacing: Self.columnSpacing) {
                        ForEach(rest) { item in
                            tile(item)
                                .environment(\.categoryTileRole, role)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                    .scrollTargetLayout()
                }
            }
            .contentMargins(.horizontal, PlayMetrics.margin, for: .scrollContent)
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
        }
    }

    private static var rowSpacing: CGFloat { 10 }
    private static var columnSpacing: CGFloat { 10 }
}

struct CategoryChip: View {
    let subject: CategorySubject
    @ScaledMetric(relativeTo: .subheadline) private var glyph: CGFloat = 30

    var body: some View {
        let glyph = min(glyph, 56)
        HStack(spacing: 10) {
            Image(systemName: subject.symbol)
                .font(.system(size: (glyph * 0.48).rounded(), weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: glyph, height: glyph)
                .background(subject.color, in: .circle)
                .accessibilityHidden(true)
            Text(subject.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.leading, 9)
        .padding(.trailing, 16)
        .frame(minWidth: Self.minimumWidth, maxWidth: .infinity, maxHeight: .infinity)
        .modifier(CategoryHover(shape: Capsule()))
    }

    static let height: CGFloat = 48
    static let minimumWidth: CGFloat = 148
}

struct CategoryCard: View {
    let subject: CategorySubject
    @ScaledMetric(relativeTo: .subheadline) private var art: CGFloat = 48
    @ScaledMetric(relativeTo: .subheadline) private var width: CGFloat = 216

    var body: some View {
        let art = min(art, 88)
        HStack(spacing: 12) {
            CategoryArt(subject: CategorySubject(title: subject.title, symbol: subject.symbol, color: subject.color, covers: Array(subject.covers.prefix(1))), side: art, showsBadge: false)
            VStack(alignment: .leading, spacing: 2) {
                Text(subject.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if !subject.covers.isEmpty {
                    Image(systemName: subject.symbol)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(subject.color)
                        .accessibilityHidden(true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(8)
        .frame(width: min(width, 360))
        .frame(maxHeight: .infinity)
        .modifier(CategoryHover(shape: RoundedRectangle(cornerRadius: CoverImage.radius(for: art) + 8, style: .continuous)))
    }

    static let height: CGFloat = 64
}

struct CategoryArt: View {
    let subject: CategorySubject
    let side: CGFloat
    var showsBadge = true

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: CoverImage.radius(for: side), style: .continuous)
        art
            .frame(width: side, height: side)
            .overlay(alignment: .bottomLeading) {
                if showsBadge, !subject.covers.isEmpty {
                    let badge = (side * 0.2).rounded()
                    Image(systemName: subject.symbol)
                        .font(.system(size: (badge * 0.5).rounded(), weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: badge, height: badge)
                        .background(subject.color, in: .circle)
                        .shadow(color: .black.opacity(0.25), radius: 3, y: 1)
                        .padding((side * 0.06).rounded())
                }
            }
            .clipShape(shape)
            .overlay { shape.stroke(.primary.opacity(0.08), lineWidth: 1) }
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var art: some View {
        let covers = subject.covers
        if covers.count >= 4 {
            let half = side / 2
            Grid(horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    CoverImage(cover: covers[0], size: half, isBare: true)
                    CoverImage(cover: covers[1], size: half, isBare: true)
                }
                GridRow {
                    CoverImage(cover: covers[2], size: half, isBare: true)
                    CoverImage(cover: covers[3], size: half, isBare: true)
                }
            }
        } else if let cover = covers.first {
            CoverImage(cover: cover, size: side, isBare: true)
        } else {
            ZStack {
                Rectangle().fill(subject.color.opacity(0.16))
                Image(systemName: subject.symbol)
                    .font(.system(size: (side * 0.38).rounded(), weight: .semibold))
                    .foregroundStyle(subject.color)
            }
        }
    }
}

struct CategoryFeature: View {
    let subject: CategorySubject

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
            ZStack(alignment: .bottomLeading) {
                if subject.covers.isEmpty {
                    Rectangle().fill(subject.color.opacity(0.16))
                    Image(systemName: subject.symbol)
                        .font(.system(size: (size.height * 0.42).rounded(), weight: .semibold))
                        .foregroundStyle(subject.color)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        .padding(14)
                    name.foregroundStyle(.primary)
                } else {
                    mosaic(size: size)
                    ArtworkScrim(reach: 0.7)
                    name.foregroundStyle(.white)
                }
            }
            .frame(width: size.width, height: size.height)
            .clipShape(shape)
            .overlay { shape.stroke(.primary.opacity(0.08), lineWidth: 1) }
        }
    }

    private var name: some View {
        Label {
            Text(subject.title).lineLimit(1).minimumScaleFactor(0.7)
        } icon: {
            Image(systemName: subject.symbol).accessibilityHidden(true)
        }
        .font(.title3.bold())
        .padding(12)
    }

    private func mosaic(size: CGSize) -> some View {
        let side = size.width / 3
        let covers = subject.covers
        return VStack(spacing: 0) {
            ForEach(0..<2, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<3, id: \.self) { column in
                        CoverImage(cover: covers[(row * 3 + column) % covers.count], size: side, isBare: true)
                    }
                }
            }
        }
        .frame(width: size.width, height: size.height, alignment: .top)
        .clipped()
        .accessibilityHidden(true)
    }
}

private struct CategoryHover<S: Shape>: ViewModifier {
    let shape: S
    @State private var isHovering = false

    func body(content: Content) -> some View {
        content
            .background(isHovering ? AnyShapeStyle(.fill.secondary) : AnyShapeStyle(.fill.tertiary), in: shape)
            .contentShape(shape)
            #if os(macOS)
            .onHover { hovering in
                withAnimation(PlayMotion.hover) { isHovering = hovering }
            }
            #endif
    }
}

extension Mood {
    static func forNow(_ date: Date = .now, calendar: Calendar = .current, covers: RoomCovers?) -> Mood {
        let candidates: [Mood] = switch DayPart(hour: calendar.component(.hour, from: date)) {
        case .morning: [.feelGood, .focus, .energy]
        case .afternoon: [.focus, .energy, .workout]
        case .evening: [.chill, .drive, .love]
        case .night: [.sleep, .chill, .heartbreak]
        }
        return candidates.max { (covers?.byMood[$0]?.count ?? 0) < (covers?.byMood[$1]?.count ?? 0) } ?? candidates[0]
    }
}
