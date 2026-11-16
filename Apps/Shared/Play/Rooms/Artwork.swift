import SwiftUI
import TracksCore

struct ArtworkMosaic: View {
    let covers: [CoverArt]
    let columns: Int
    var spacing: CGFloat = 2
    let fallback: Color
    let symbol: String

    var body: some View {
        GeometryReader { proxy in
            let side = max(1, (proxy.size.width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
            let rows = max(1, Int(((proxy.size.height + spacing) / (side + spacing)).rounded(.up)))
            // Each row starts one cover along, so a short list never stacks a cover in a column.
            if covers.isEmpty {
                fallback
                    .overlay(alignment: .bottomTrailing) {
                        Image(systemName: symbol)
                            .font(.system(size: min(proxy.size.height, proxy.size.width) * 0.7, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.22))
                            .offset(x: proxy.size.height * 0.12, y: proxy.size.height * 0.14)
                    }
            } else {
                VStack(spacing: spacing) {
                    ForEach(0..<rows, id: \.self) { row in
                        HStack(spacing: spacing) {
                            ForEach(0..<columns, id: \.self) { column in
                                CoverImage(cover: covers[(row * columns + column + row) % covers.count], size: side, isBare: true)
                            }
                        }
                    }
                }
                .background(.black)
            }
        }
        .clipped()
        .accessibilityHidden(true)
    }
}

struct ArtworkScrim: View {
    var reach: Double = 1

    var body: some View {
        let start = 1 - reach
        ZStack {
            Rectangle()
                .fill(.ultraThinMaterial)
                .environment(\.colorScheme, .dark)
                .mask {
                    LinearGradient(stops: [
                        .init(color: .clear, location: start),
                        .init(color: .black, location: start + reach * 0.55),
                    ], startPoint: .top, endPoint: .bottom)
                }
            LinearGradient(stops: [
                .init(color: .black.opacity(0), location: start),
                .init(color: .black.opacity(0.35), location: start + reach * 0.55),
                .init(color: .black.opacity(0.6), location: 1),
            ], startPoint: .top, endPoint: .bottom)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct ArtworkHero<Content: View>: View {
    let covers: [CoverArt]
    let columns: Int
    let fallback: Color
    let symbol: String
    @ViewBuilder var content: Content

    var body: some View {
        content
            .foregroundStyle(.white)
            .padding(.horizontal, PlayMetrics.margin)
            .padding(.top, Self.scrimLead)
            .padding(.bottom, Self.foot)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { ArtworkScrim() }
            .frame(maxWidth: .infinity, minHeight: Self.height, alignment: .bottom)
            .background(alignment: .bottom) {
                ArtworkMosaic(covers: covers, columns: columns, fallback: fallback, symbol: symbol)
                    .backgroundExtensionEffect()
                    .padding(.top, -Self.underBar)
                    .visualEffect { content, proxy in
                        let pull = max(0, proxy.frame(in: .scrollView).minY)
                        return content.scaleEffect(1 + pull / max(proxy.size.height, 1), anchor: .bottom)
                    }
            }
    }

    private static var scrimLead: CGFloat { 110 }

    #if os(macOS)
    static var height: CGFloat { 420 }
    static var underBar: CGFloat { 60 }
    static var foot: CGFloat { 28 }
    #else
    static var height: CGFloat { 470 }
    static var underBar: CGFloat { 120 }
    static var foot: CGFloat { 22 }
    #endif
}

struct CategoryTile: View {
    let title: String
    let symbol: String
    let covers: [CoverArt]
    let color: Color
    let height: CGFloat
    var fillsWidth = false

    var body: some View {
        let side = (height * 0.72).rounded()
        ZStack(alignment: .topLeading) {
            color
            Group {
                if let cover = covers.first {
                    CoverImage(cover: cover, size: side)
                        .shadow(color: .black.opacity(0.3), radius: 8, y: 3)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: side * 0.7, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.28))
                        .frame(width: side, height: side)
                }
            }
            .offset(x: side * 0.18, y: side * 0.22)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .accessibilityHidden(true)
            Text(title)
                .font(.headline)
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.horizontal, 12)
                .padding(.top, 10)
        }
        .frame(width: fillsWidth ? nil : height * 1.75, height: height)
        .frame(maxWidth: fillsWidth ? .infinity : nil)
        .clipShape(.rect(cornerRadius: Self.radius, style: .continuous))
        .contentShape(.rect(cornerRadius: Self.radius, style: .continuous))
    }

    static let radius: CGFloat = 14
}

struct ArtworkPlayRow<Extra: View>: View {
    let title: String
    let symbol: String
    let tint: Color
    let canPlay: Bool
    let shuffle: (() -> Void)?
    let play: () -> Void
    @ViewBuilder var extra: Extra

    var body: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                Button(action: play) {
                    Label(title, systemImage: symbol)
                        .foregroundStyle(.white)
                        .contentTransition(.symbolEffect(.replace))
                        .lineLimit(1)
                        .frame(maxWidth: Self.fills ? .infinity : nil)
                        .padding(.horizontal, Self.fills ? 0 : 6)
                }
                .buttonStyle(.glassProminent)
                .tint(tint)
                .disabled(!canPlay)
                if let shuffle {
                    Button(action: shuffle) {
                        Image(systemName: "shuffle")
                            .accessibilityLabel("Shuffle")
                    }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .tint(nil)
                    .help("Shuffle")
                }
                extra
            }
        }
        .controlSize(Self.size)
        .font(Self.font)
        .buttonBorderShape(.capsule)
    }

    #if os(macOS)
    private static var fills: Bool { false }
    private static var font: Font { .body.weight(.semibold) }
    private static var size: ControlSize { .extraLarge }
    #else
    private static var fills: Bool { true }
    private static var font: Font { .headline }
    private static var size: ControlSize { .large }
    #endif
}

extension ArtworkPlayRow where Extra == EmptyView {
    init(title: String, symbol: String, tint: Color, canPlay: Bool, shuffle: (() -> Void)?, play: @escaping () -> Void) {
        self.init(title: title, symbol: symbol, tint: tint, canPlay: canPlay, shuffle: shuffle, play: play) { EmptyView() }
    }
}

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
                .accessibilityLabel(Text(title))
        }
        .menuIndicator(.hidden)
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .tint(nil)
        .fixedSize()
        .help(Text(title))
    }
}
