import SwiftUI
import TracksCore

struct CategoryChip: View {
    let title: String
    let symbol: String
    let color: Color
    var fillsWidth = false
    @ScaledMetric(relativeTo: .subheadline) private var height = CategoryChipMetrics.height
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let height = min(height, CategoryChipMetrics.height * 2)
        let disc = min(height - CategoryChipMetrics.inset * 2, CategoryChipMetrics.largestDisc)
        HStack(spacing: CategoryChipMetrics.gap) {
            Image(systemName: symbol)
                .font(.system(size: (disc * 0.48).rounded(), weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: disc, height: disc)
                .background(color, in: .circle)
                .overlay {
                    if colorScheme == .dark {
                        Circle().strokeBorder(.white.opacity(0.16), lineWidth: 1)
                    }
                }
                .accessibilityHidden(true)
            Text(title)
                .font(CategoryChipMetrics.font)
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .fixedSize(horizontal: !fillsWidth, vertical: false)
        }
        .padding(.leading, (height - disc) / 2)
        .padding(.trailing, CategoryChipMetrics.trailing)
        .frame(height: height)
        .frame(maxWidth: fillsWidth ? .infinity : nil, alignment: .leading)
    }
}

enum CategoryChipMetrics {
    #if os(macOS)
    static let height: CGFloat = 40
    static let inset: CGFloat = 6
    static let trailing: CGFloat = 16
    static let gap: CGFloat = 8
    static let font = Font.body.weight(.semibold)
    #else
    static let height: CGFloat = 50
    static let inset: CGFloat = 8
    static let trailing: CGFloat = 20
    static let gap: CGFloat = 10
    static let font = Font.subheadline.weight(.semibold)
    #endif
    static let largestDisc: CGFloat = 48
    static let spacing: CGFloat = 10
}

struct CategoryChipStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        CategoryChipBody(configuration: configuration)
    }
}

private struct CategoryChipBody: View {
    let configuration: ButtonStyleConfiguration
    @State private var isHovering = false
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let isPressed = configuration.isPressed
        configuration.label
            .background(fill, in: .capsule)
            .overlay {
                if contrast == .increased {
                    Capsule().strokeBorder(.primary.opacity(0.35), lineWidth: 1)
                }
            }
            .contentShape(.capsule)
            .scaleEffect(isPressed && !reduceMotion ? 0.96 : 1)
            .animation(PlayMotion.press, value: isPressed)
            #if os(macOS)
            .contentShape(.focusEffect, .capsule)
            .onHover { hovering in
                withAnimation(PlayMotion.hover) { isHovering = hovering }
            }
            #else
            .contentShape(.hoverEffect, .capsule)
            #endif
    }

    private var fill: AnyShapeStyle {
        if configuration.isPressed { return AnyShapeStyle(.fill) }
        return isHovering ? AnyShapeStyle(.fill.secondary) : AnyShapeStyle(.fill.tertiary)
    }
}

extension ButtonStyle where Self == CategoryChipStyle {
    static var categoryChip: CategoryChipStyle { CategoryChipStyle() }
}

struct ChipShelf<Items: RandomAccessCollection, Chip: View>: View where Items.Element: Identifiable {
    let title: String
    let items: Items
    @ViewBuilder var chip: (Items.Element) -> Chip

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.title3.bold())
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, PlayMetrics.margin)
            ChipScroller {
                ChipRows(spacing: CategoryChipMetrics.spacing) {
                    ForEach(items) { item in
                        chip(item)
                    }
                }
            }
        }
    }
}

struct ChipShelfPlaceholder: View {
    let title: String
    @ScaledMetric(relativeTo: .subheadline) private var height = CategoryChipMetrics.height

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.title3.bold())
                .padding(.horizontal, PlayMetrics.margin)
            ChipRows(spacing: CategoryChipMetrics.spacing) {
                ForEach(Self.widths.indices, id: \.self) { index in
                    Capsule()
                        .fill(Color.placeholderFill)
                        .frame(width: Self.widths[index], height: min(height, CategoryChipMetrics.height * 2))
                }
            }
            .padding(.horizontal, PlayMetrics.margin)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Loading \(title)"))
    }

    private static let widths: [CGFloat] = [132, 112, 150, 124, 104, 140, 118, 128, 146, 110]
}

private struct ChipScroller<Content: View>: View {
    @ViewBuilder var content: Content
    #if os(macOS)
    @State private var position = ScrollPosition()
    @State private var reach = ScrollReach()
    @State private var isHovering = false
    #endif

    var body: some View {
        ScrollView(.horizontal) {
            content
        }
        .contentMargins(.horizontal, PlayMetrics.margin, for: .scrollContent)
        .scrollIndicators(.hidden)
        #if os(macOS)
        .scrollPosition($position)
        .onScrollGeometryChange(for: ScrollReach.self, of: ScrollReach.init) { _, new in reach = new }
        .overlay {
            if isHovering {
                arrows
            }
        }
        .onHover { hovering in
            withAnimation(PlayMotion.hover) { isHovering = hovering }
        }
        #endif
    }

    #if os(macOS)
    private var arrows: some View {
        HStack {
            if reach.offset > reach.start + 1 {
                ShelfArrow(systemImage: "chevron.backward", label: "Previous") {
                    position.scrollTo(x: max(reach.start, reach.offset - reach.page))
                }
            }
            Spacer()
            if reach.offset < reach.end - 1 {
                ShelfArrow(systemImage: "chevron.forward", label: "Next") {
                    position.scrollTo(x: min(reach.end, reach.offset + reach.page))
                }
            }
        }
        .padding(.horizontal, 8)
        .transition(.opacity)
    }
    #endif
}

#if os(macOS)
/// Where a sideways scroll stands, in its offsets, which begin before zero by the leading margin.
private struct ScrollReach: Equatable {
    var offset: CGFloat = 0
    var start: CGFloat = 0
    var end: CGFloat = 0
    var page: CGFloat = 0

    init() {}

    init(_ geometry: ScrollGeometry) {
        offset = geometry.contentOffset.x
        start = -geometry.contentInsets.leading
        end = max(start, geometry.contentSize.width + geometry.contentInsets.trailing - geometry.containerSize.width)
        page = max(1, geometry.containerSize.width - geometry.contentInsets.leading - geometry.contentInsets.trailing - 80)
    }
}
#endif

struct ChipRows: Layout {
    var rows = 2
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let arrangement = arrange(subviews)
        for (subview, frame) in zip(subviews, arrangement.frames) {
            subview.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY), proposal: ProposedViewSize(frame.size))
        }
    }

    func explicitAlignment(of guide: HorizontalAlignment, in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGFloat? {
        nil
    }

    func explicitAlignment(of guide: VerticalAlignment, in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGFloat? {
        nil
    }

    private func arrange(_ subviews: Subviews) -> (frames: [CGRect], size: CGSize) {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let height = sizes.map(\.height).max() ?? 0
        var ends = [CGFloat?](repeating: nil, count: rows)
        var frames: [CGRect] = []
        for size in sizes {
            let row = ends.indices.min { (ends[$0] ?? -spacing) < (ends[$1] ?? -spacing) } ?? 0
            let x = ends[row].map { $0 + spacing } ?? 0
            frames.append(CGRect(x: x, y: CGFloat(row) * (height + spacing), width: size.width, height: height))
            ends[row] = x + size.width
        }
        let used = ends.compactMap(\.self).count
        let width = ends.compactMap(\.self).max() ?? 0
        let totalHeight = used == 0 ? 0 : CGFloat(used) * height + CGFloat(used - 1) * spacing
        return (frames, CGSize(width: width, height: totalHeight))
    }
}
