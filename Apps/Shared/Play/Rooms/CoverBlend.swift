import SwiftUI
import TracksCore

/// A category's covers melted into one field over its colour, as Music's category tiles and
/// editorial art are. It's drawn once for its covers, size and colours and kept, so scrolling
/// past it again costs nothing; until then, and with no covers, it's the colour's own field.
/// The first drawing fades in; one already kept is there in the first frame.
struct CoverBlend: View {
    let covers: [CoverArt]
    let color: Color
    /// Points from the bottom, above `fade`, that carry white type: nothing is drawn until
    /// it's known, so the first drawing is the right one.
    let legibleHeight: CGFloat
    /// Points at the bottom that dissolve into the page.
    var fade: CGFloat = 0

    @Environment(\.self) private var environment
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var size: CGSize = .zero
    /// The last drawing, kept while a new size or set of covers is drawn.
    @State private var drawn: (key: CoverBlendKey, picture: CGImage)?

    var body: some View {
        if reduceTransparency || contrast == .increased {
            CoverBlendSolid(color: color)
                .mask { fadeMask }
                .accessibilityHidden(true)
        } else {
            blend
        }
    }

    private var blend: some View {
        let key = key
        let picture = key.flatMap(CoverBlendCache.picture(for:)) ?? drawn?.picture
        return ZStack {
            // Dissolving as the drawing will, so the page doesn't meet a hard edge before it's in.
            CoverBlendField(color: color)
                .mask { fadeMask }
            if let picture {
                Image(decorative: picture, scale: displayScale)
                    .resizable()
                    .transition(.opacity)
            }
        }
        .animation(Design.Motion.fade, value: drawn?.key)
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
        .task(id: key) {
            guard let key, CoverBlendCache.picture(for: key) == nil else { return }
            let pictures = await CoverBlendPictures.load(key.covers, pixels: CoverImage.pixels(for: min(size.width, size.height), scale: displayScale))
            guard !Task.isCancelled else { return }
            let request = CoverBlendRenderer.Request(
                pictures: pictures,
                color: key.color,
                size: CGSize(width: key.width, height: key.height),
                legible: CGFloat(key.legible),
                fade: CGFloat(key.fade),
                page: key.page
            )
            guard let picture = await CoverBlendRenderer.draw(request), !Task.isCancelled else { return }
            CoverBlendCache.keep(picture, for: key)
            drawn = (key, picture)
        }
        .accessibilityHidden(true)
    }

    private var fadeMask: some View {
        VStack(spacing: 0) {
            Color.black
            LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: fade)
        }
    }

    /// Nil until it has a size and knows where its type is. Type heights are rounded to a few steps, so a line of text
    /// that settles by a point doesn't draw it again.
    private var key: CoverBlendKey? {
        guard size.width >= 1, size.height >= 1, legibleHeight > 0,
              let color = CoverBlendRenderer.RGB(color.resolve(in: environment).cgColor)
        else { return nil }
        let pixels = { (points: CGFloat) in Int((points * displayScale).rounded()) }
        let step: CGFloat = 8
        return CoverBlendKey(
            covers: Array(covers.prefix(CoverBlendPictures.limit)),
            color: color,
            page: fade > 0 ? CoverBlendRenderer.RGB(Color.pageBackground.resolve(in: environment).cgColor) : nil,
            width: pixels(size.width),
            height: pixels(size.height),
            legible: pixels(min(size.height, (legibleHeight / step).rounded(.up) * step)),
            fade: pixels(fade)
        )
    }
}

/// The category's colour lit from the top leading corner: what a blend is drawn over, shown
/// while it's drawn.
struct CoverBlendField: View {
    let color: Color
    @Environment(\.self) private var environment

    var body: some View {
        let resolved = CoverBlendRenderer.RGB(color.resolve(in: environment).cgColor)
        LinearGradient(
            colors: [Color(blend: resolved?.light) ?? color, Color(blend: resolved?.shade) ?? color],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

/// The category's colour, deep enough for white type at 4.5:1 or more: what a blend is under
/// Reduce Transparency and Increase Contrast, with no covers to read through.
struct CoverBlendSolid: View {
    let color: Color
    @Environment(\.self) private var environment

    var body: some View {
        let resolved = CoverBlendRenderer.RGB(color.resolve(in: environment).cgColor)
        Rectangle().fill(Color(blend: resolved?.deepened(to: CoverBlendRenderer.legibleLuminance)) ?? color)
    }
}

private extension Color {
    init?(blend: CoverBlendRenderer.RGB?) {
        guard let blend else { return nil }
        self.init(.sRGB, red: blend.red, green: blend.green, blue: blend.blue)
    }
}

nonisolated struct CoverBlendKey: Hashable, Sendable {
    let covers: [CoverArt]
    let color: CoverBlendRenderer.RGB
    let page: CoverBlendRenderer.RGB?
    let width: Int
    let height: Int
    let legible: Int
    let fade: Int
}

#if DEBUG
#Preview("Blends") {
    let covers: [CoverArt] = ["Tides", "Glasshouse", "Northbound"].map { .url(nil, seed: $0) }
    VStack(spacing: Design.Spacing.medium) {
        CoverBlend(covers: covers, color: Mood.chill.base, legibleHeight: 48)
            .frame(width: 180, height: 112)
        CoverBlend(covers: [], color: Mood.feelGood.base, legibleHeight: 48)
            .frame(width: 180, height: 112)
        CoverBlendSolid(color: Mood.feelGood.base)
            .frame(width: 180, height: 112)
    }
    .clipShape(.rect(cornerRadius: CategoryCardMetrics.radius, style: .continuous))
    .padding()
}
#endif
