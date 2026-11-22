import SwiftUI
import TracksCore

/// Play's measures. Read ``Design`` in new code.
enum PlayMetrics {
    static let margin = Design.Margin.page
    static let shelfSpacing = Design.Shelf.spacing
    static let tile = Design.Shelf.tile
    static let sectionSpacing = Design.Margin.section
}

/// Play's motion. Read ``Design/Motion`` in new code.
enum PlayMotion {
    static let press = Design.Motion.press
    static let hover = Design.Motion.hover
    static let value = Design.Motion.value
    static let panel = Design.Motion.panel
    static let row = Design.Motion.row
    static let tint = Design.Motion.tint
    static let flip = Design.Motion.flip
}

/// A titled row of tiles that scrolls sideways, as on Music's Home. On the Mac, arrows at
/// either end page through it while the pointer is over it, as Music's shelves do.
struct Shelf<Items: RandomAccessCollection, Tile: View, Trailing: View>: View where Items.Element: Identifiable {
    let title: String
    let items: Items
    /// Opens the shelf's own page, with a chevron after its title.
    var open: (() -> Void)?
    @ViewBuilder var trailing: Trailing
    @ViewBuilder var tile: (Items.Element) -> Tile

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Margin.header) {
            AdaptiveStack(verticalAlignment: .firstTextBaseline, spacing: Design.Spacing.xSmall) {
                SectionTitle(title: Text(verbatim: title), action: open)
                Spacer(minLength: Design.Spacing.small)
                trailing
                    .font(.subheadline)
            }
            .padding(.horizontal, Design.Margin.page)

            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: Design.Shelf.spacing) {
                        ForEach(items) { item in
                            tile(item)
                                .id(item.id)
                        }
                    }
                    .scrollTargetLayout()
                }
                .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
                .scrollTargetBehavior(.viewAligned)
                .scrollIndicators(.hidden)
                #if os(macOS)
                .modifier(ShelfPaging(ids: items.map(\.id), proxy: proxy))
                #endif
            }
        }
    }
}

#if os(macOS)
/// The shelf's arrows, with the hover and the tiles in view kept here: on the shelf itself,
/// every tile scrolled into view and every pass of the pointer drew all its tiles again.
private struct ShelfPaging<ID: Hashable>: ViewModifier {
    let ids: [ID]
    let proxy: ScrollViewProxy
    /// The tiles wholly in view, in order, for the arrows to page from.
    @State private var visible: [ID] = []
    @State private var isHovering = false

    func body(content: Content) -> some View {
        content
            .onScrollTargetVisibilityChange(idType: ID.self, threshold: 0.95) { visible = $0 }
            .overlay {
                if isHovering {
                    pageArrows
                }
            }
            // After the arrows, so moving onto one doesn't count as leaving the shelf.
            .onHover { hovering in
                withAnimation(Design.Motion.hover) { isHovering = hovering }
            }
    }

    private var pageArrows: some View {
        let first = visible.first.flatMap { ids.firstIndex(of: $0) }
        let last = visible.last.flatMap { ids.firstIndex(of: $0) }
        return HStack {
            if let first, first > 0 {
                ShelfArrow(systemImage: "chevron.backward", label: "Previous") {
                    // The tile before the first in view comes in at the far edge: a page back.
                    proxy.scrollTo(ids[first - 1], anchor: .trailing)
                }
            }
            Spacer()
            if let last, last < ids.count - 1 {
                ShelfArrow(systemImage: "chevron.forward", label: "Next") {
                    proxy.scrollTo(ids[last + 1], anchor: .leading)
                }
            }
        }
        .padding(.horizontal, 8)
        .transition(.opacity)
    }
}
#endif

#if os(macOS)
/// A round glass arrow at the end of a shelf.
private struct ShelfArrow: View {
    let systemImage: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(label, systemImage: systemImage) {
            withAnimation(Design.Motion.panel, action)
        }
        .labelStyle(.iconOnly)
        .font(.body.weight(.semibold))
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(.large)
        .help(Text(label))
    }
}
#endif

extension Shelf where Trailing == EmptyView {
    init(title: String, items: Items, open: (() -> Void)? = nil, @ViewBuilder tile: @escaping (Items.Element) -> Tile) {
        self.title = title
        self.items = items
        self.open = open
        self.trailing = EmptyView()
        self.tile = tile
    }
}

/// A square cover with a title and a line under it, for a shelf.
struct TileLabel<Cover: View>: View {
    let title: String
    let subtitle: String?
    @ScaledMetric(relativeTo: .subheadline) private var width = Design.Shelf.tile
    @ViewBuilder var cover: (CGFloat) -> Cover

    var body: some View {
        let side = min(width, Design.Shelf.maximumTile)
        VStack(alignment: .leading, spacing: Design.Shelf.captionGap) {
            cover(side)
            VStack(alignment: .leading, spacing: Design.Shelf.lineGap) {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .frame(width: side, alignment: .leading)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

/// Covers and cards shrink slightly under a finger, as Music's do.
struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PressableBody(configuration: configuration)
    }
}

private struct PressableBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(Design.Motion.press, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableButtonStyle {
    static var pressable: PressableButtonStyle { PressableButtonStyle() }
}

/// "LIVE", on a live station's cover, in Music's red.
struct LiveBadge: View {
    var body: some View {
        Text("Live")
            .textCase(.uppercase)
            .font(.caption2.weight(.heavy))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(.red, in: .rect(cornerRadius: 4, style: .continuous))
    }
}

#if DEBUG
private struct ShelfPreviewItem: Identifiable {
    let id: Int
    var title: String { ["Coastlines", "Late Night Drives and the Long Way Home", "Hour Satellite", "Golden", "Fieldnotes"][id % 5] }
}

#Preview("Shelf") {
    ScrollView {
        VStack(alignment: .leading, spacing: Design.Margin.section) {
            Shelf(title: "Recently Played", items: (0..<6).map(ShelfPreviewItem.init), open: {}) { item in
                Button {} label: {
                    TileLabel(title: item.title, subtitle: "Juniper Lane") { side in
                        CoverImage(cover: .url(nil, seed: item.title), size: side)
                            .coverPlayButton {}
                    }
                }
                .buttonStyle(.pressable)
            }
            Shelf(title: "Top Songs Over Everything You Played This Summer", items: (0..<1).map(ShelfPreviewItem.init)) { item in
                TileLabel(title: item.title, subtitle: nil) { side in
                    CoverImage(cover: .url(nil, seed: item.title), size: side)
                }
            }
        }
    }
}

#Preview("Shelf, accessibility size") {
    Shelf(title: "Recently Played", items: (0..<4).map(ShelfPreviewItem.init), open: {}) { item in
        TileLabel(title: item.title, subtitle: "Juniper Lane") { side in
            CoverImage(cover: .url(nil, seed: item.title), size: side)
        }
    }
    .dynamicTypeSize(.accessibility5)
}
#endif
