import SwiftUI
import TracksCore

/// A page's hero: its covers melted into one field over its colour, running up under the bars
/// and dissolving into the page at the foot, with the page's name and actions on it in white.
struct ArtworkHero<Content: View>: View {
    let covers: [CoverArt]
    let color: Color
    @ViewBuilder var content: Content
    /// The words and actions with their foot: the part of the art kept dark enough for them.
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        content
            .foregroundStyle(.white)
            .padding(.horizontal, Design.Margin.page)
            .padding(.bottom, Self.foot)
            .frame(maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
            .padding(.bottom, Self.fade)
            .frame(maxWidth: .infinity, minHeight: Self.height, alignment: .bottom)
            .background(alignment: .bottom) {
                CoverBlend(covers: covers, color: color, legibleHeight: contentHeight + Self.headroom, fade: Self.fade)
                    .backgroundExtensionEffect()
                    .padding(.top, -Self.underBar)
                    .visualEffect { content, proxy in
                        let pull = max(0, proxy.frame(in: .scrollView).minY)
                        return content.scaleEffect(1 + pull / max(proxy.size.height, 1), anchor: .bottom)
                    }
            }
    }

    /// Above the words, still dark enough for them, easing out from there.
    private static var headroom: CGFloat { Design.Spacing.xxLarge }
    /// Where the art gives way to the page, under the actions.
    private static var fade: CGFloat { 56 }

    #if os(macOS)
    static var height: CGFloat { 420 }
    static var underBar: CGFloat { 60 }
    static var foot: CGFloat { Design.Spacing.small }
    #else
    static var height: CGFloat { 470 }
    static var underBar: CGFloat { 120 }
    static var foot: CGFloat { Design.Spacing.small }
    #endif
}

/// Play over a hero of covers: opaque white with the hero's colour for its label, Shuffle and
/// anything else beside it in glass.
struct ArtworkPlayRow<Extra: View>: View {
    let title: String
    let symbol: String
    let tint: Color
    let canPlay: Bool
    let shuffle: (() -> Void)?
    let play: () -> Void
    @ViewBuilder var extra: Extra

    var body: some View {
        GlassEffectContainer(spacing: Design.Control.spacing) {
            HStack(spacing: Self.spacing) {
                PlayButton(verbatim: title, systemImage: symbol, tint: tint, fillsWidth: Self.fills, action: play)
                    .contentTransition(.symbolEffect(.replace))
                    .disabled(!canPlay)
                if let shuffle {
                    GlassCircleButton(title: "Shuffle", systemImage: "shuffle", action: shuffle)
                }
                extra
            }
        }
    }

    #if os(macOS)
    private static var fills: Bool { false }
    private static var spacing: CGFloat { Design.Spacing.small }
    #else
    private static var fills: Bool { true }
    private static var spacing: CGFloat { Design.Control.spacing }
    #endif
}

extension ArtworkPlayRow where Extra == EmptyView {
    init(title: String, symbol: String, tint: Color, canPlay: Bool, shuffle: (() -> Void)?, play: @escaping () -> Void) {
        self.init(title: title, symbol: symbol, tint: tint, canPlay: canPlay, shuffle: shuffle, play: play) { EmptyView() }
    }
}

/// A menu in a glass circle over a hero, filled while what it sets is on.
struct ArtworkRoundMenu<Items: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    var isOn = false
    @ViewBuilder var items: Items

    var body: some View {
        Menu {
            items
        } label: {
            Image(systemName: symbol)
                .symbolVariant(isOn ? .fill : .none)
                .contentTransition(.symbolEffect(.replace))
                .glassCircle()
                .accessibilityLabel(Text(title))
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .fixedSize()
        .help(Text(title))
    }
}

#if DEBUG
#Preview("Hero, light") {
    ScrollView {
        ArtworkHero(covers: ["Tides", "Glasshouse", "Northbound"].map { .url(nil, seed: $0) }, color: Mood.sleep.base) {
            VStack(alignment: .leading, spacing: Design.Spacing.medium) {
                Text("Chill").font(.pageTitle)
                ArtworkPlayRow(title: "Play Chill Mix", symbol: "play.fill", tint: .indigo, canPlay: true, shuffle: {}, play: {}) {
                    ArtworkRoundMenu(title: "Sleep Timer", symbol: "moon.zzz") {
                        Button("15 Minutes") {}
                    }
                }
            }
        }
    }
    .ignoresSafeArea(edges: .top)
}

#Preview("Hero, dark, long title") {
    ScrollView {
        ArtworkHero(covers: [], color: Mood.feelGood.base) {
            VStack(alignment: .leading, spacing: Design.Spacing.medium) {
                Text("Everything That Feels Like a Summer Evening").font(.pageTitle)
                ArtworkPlayRow(title: "Play", symbol: "play.fill", tint: .orange, canPlay: false, shuffle: nil, play: {})
            }
        }
    }
    .ignoresSafeArea(edges: .top)
    .preferredColorScheme(.dark)
}
#endif
