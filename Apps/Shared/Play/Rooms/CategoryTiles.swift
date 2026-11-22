import SwiftUI
import TracksCore

/// A mood or a genre to open: its covers melted into one field over its colour, its symbol and
/// name at the bottom leading corner, as Music's category tiles are.
struct CategoryCard: View {
    let title: String
    let symbol: String
    let color: Color
    let covers: [CoverArt]
    var placement = CategoryCardPlacement.shelf

    @ScaledMetric(relativeTo: .headline) private var shelfHeight = CategoryCardMetrics.shelfHeight
    @ScaledMetric(relativeTo: .headline) private var gridHeight = CategoryCardMetrics.gridHeight
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    /// The symbol and name's height with their padding: the part of the art kept dark enough
    /// for them.
    @State private var nameHeight: CGFloat = 0

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: CategoryCardMetrics.radius, style: .continuous)
        let isSolid = reduceTransparency || contrast == .increased
        name
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .background {
                CoverBlend(covers: covers, color: color, legibleHeight: nameHeight)
            }
            .frame(width: width, height: height)
            .frame(maxWidth: placement == .grid ? .infinity : nil)
            .clipShape(shape)
            .overlay {
                if !isSolid {
                    shape.strokeBorder(Self.rim, lineWidth: 0.5)
                }
            }
    }

    private var name: some View {
        VStack(alignment: .leading, spacing: Design.Spacing.xSmall) {
            Image(systemName: symbol)
                .font(CategoryCardMetrics.symbolFont)
                .symbolRenderingMode(.monochrome)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .accessibilityHidden(true)
            Text(title)
                .font(CategoryCardMetrics.font)
                .lineLimit(lineLimit)
                .minimumScaleFactor(typeSize.isAccessibilitySize ? 0.5 : 0.85)
                .multilineTextAlignment(.leading)
        }
        .foregroundStyle(.white)
        .padding(CategoryCardMetrics.padding)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { nameHeight = $0 }
    }

    /// Two lines; at accessibility sizes a line a word, at most three, so a word shrinks to
    /// fit rather than breaking in the middle.
    private var lineLimit: Int {
        guard typeSize.isAccessibilitySize else { return 2 }
        return min(max(title.split(whereSeparator: \.isWhitespace).count, 1), 3)
    }

    private var height: CGFloat {
        switch placement {
        case .shelf: min(shelfHeight, CategoryCardMetrics.shelfHeight * 1.4)
        case .grid: min(gridHeight, CategoryCardMetrics.gridHeight * 1.6)
        }
    }

    private var width: CGFloat? {
        placement == .shelf ? (height * CategoryCardMetrics.aspect).rounded() : nil
    }

    /// Light catching the card's top edge and fading down its sides.
    private static let rim = LinearGradient(
        colors: [.white.opacity(0.3), .white.opacity(0.06), .white.opacity(0)],
        startPoint: .top,
        endPoint: .bottom
    )
}

enum CategoryCardPlacement {
    /// In a row to scroll through: as wide as its height allows.
    case shelf
    /// In a grid: as wide as its column.
    case grid
}

enum CategoryCardMetrics {
    #if os(macOS)
    static let shelfHeight: CGFloat = 128
    static let gridHeight: CGFloat = 108
    static let padding: CGFloat = 12
    static let font = Font.title3.weight(.semibold)
    static let symbolFont = Font.body.weight(.semibold)
    #else
    static let shelfHeight: CGFloat = 112
    static let gridHeight: CGFloat = 105
    static let padding: CGFloat = 12
    static let font = Font.headline
    static let symbolFont = Font.subheadline.weight(.semibold)
    #endif
    static let radius: CGFloat = 12
    static let aspect: CGFloat = 1.6
}

struct CategoryCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        CategoryCardBody(configuration: configuration)
    }
}

private struct CategoryCardBody: View {
    let configuration: ButtonStyleConfiguration
    @State private var isHovering = false
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: CategoryCardMetrics.radius, style: .continuous)
        let isPressed = configuration.isPressed
        configuration.label
            .overlay {
                if contrast == .increased {
                    shape.strokeBorder(.primary.opacity(0.4), lineWidth: 1)
                }
            }
            .brightness(isPressed && reduceMotion ? -0.08 : isHovering ? 0.05 : 0)
            .scaleEffect(reduceMotion ? 1 : isPressed ? 0.97 : isHovering ? 1.015 : 1)
            .animation(Design.Motion.press, value: isPressed)
            .contentShape(shape)
            #if os(macOS)
            .contentShape(.focusEffect, shape)
            .onHover { hovering in
                withAnimation(Design.Motion.hover) { isHovering = hovering }
            }
            #else
            .contentShape(.hoverEffect, shape)
            #endif
    }
}

extension ButtonStyle where Self == CategoryCardStyle {
    static var categoryCard: CategoryCardStyle { CategoryCardStyle() }
}

struct CategoryShelfPlaceholder: View {
    let title: String
    @ScaledMetric(relativeTo: .headline) private var height = CategoryCardMetrics.shelfHeight

    var body: some View {
        let height = min(height, CategoryCardMetrics.shelfHeight * 1.4)
        VStack(alignment: .leading, spacing: Design.Margin.header) {
            Text(title)
                .font(.sectionTitle)
                .padding(.horizontal, Design.Margin.page)
            ScrollView(.horizontal) {
                HStack(spacing: Design.Shelf.spacing) {
                    ForEach(0..<6, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: CategoryCardMetrics.radius, style: .continuous)
                            .fill(Color.placeholderFill)
                            .frame(width: (height * CategoryCardMetrics.aspect).rounded(), height: height)
                    }
                }
            }
            .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
            .scrollDisabled(true)
            .scrollIndicators(.hidden)
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Loading \(title)"))
    }
}

#if DEBUG
private let previewCovers: [CoverArt] = ["Tides", "Glasshouse", "Northbound"].map { .url(nil, seed: $0) }

#Preview("Shelf") {
    ScrollView(.horizontal) {
        HStack(spacing: Design.Shelf.spacing) {
            CategoryCard(title: Mood.chill.title, symbol: Mood.chill.symbol, color: Mood.chill.base, covers: previewCovers)
            CategoryCard(title: Mood.feelGood.title, symbol: Mood.feelGood.symbol, color: Mood.feelGood.base, covers: Array(previewCovers.prefix(1)))
            CategoryCard(title: Mood.sleep.title, symbol: Mood.sleep.symbol, color: Mood.sleep.base, covers: [])
            CategoryCard(title: "Everything That Feels Like a Summer Evening", symbol: Mood.drive.symbol, color: Mood.drive.base, covers: previewCovers)
        }
        .buttonStyle(.categoryCard)
        .padding(Design.Margin.page)
    }
    #if os(macOS)
    .frame(width: 820)
    #endif
}

#Preview("Grid, dark") {
    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: Design.Spacing.medium)], spacing: Design.Spacing.medium) {
        ForEach(Mood.allCases) { mood in
            CategoryCard(title: mood.title, symbol: mood.symbol, color: mood.base, covers: mood == .sleep ? [] : previewCovers, placement: .grid)
        }
    }
    .padding(Design.Margin.page)
    #if os(macOS)
    .frame(width: 820)
    #endif
    .preferredColorScheme(.dark)
}

#Preview("Accessibility size") {
    VStack(spacing: Design.Spacing.medium) {
        CategoryCard(title: Mood.heartbreak.title, symbol: Mood.heartbreak.symbol, color: Mood.heartbreak.base, covers: previewCovers)
        CategoryCard(title: "Singer/Songwriter", symbol: "guitars.fill", color: .indigo, covers: [], placement: .grid)
    }
    .padding(Design.Margin.page)
    .dynamicTypeSize(.accessibility5)
}

#Preview("Loading") {
    CategoryShelfPlaceholder(title: "Genres")
}
#endif
