import SwiftUI
import TracksCore

struct CategoryCard: View {
    let title: String
    let symbol: String
    let color: Color
    let covers: [CoverArt]
    var fillsWidth = false
    @ScaledMetric(relativeTo: .headline) private var height = CategoryCardMetrics.height
    @ScaledMetric(relativeTo: .headline) private var glyphHeight = CategoryCardMetrics.glyphHeight
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let height = min(height, CategoryCardMetrics.height * 1.4)
        let nameShare = typeSize.isAccessibilitySize ? 1 : 0.62
        Color.clear
            .aspectRatio(CategoryCardMetrics.aspect, contentMode: .fit)
            .frame(width: fillsWidth ? nil : (height * CategoryCardMetrics.aspect).rounded())
            .frame(maxWidth: fillsWidth ? .infinity : nil)
            .overlay {
                GeometryReader { proxy in
                    ZStack(alignment: .topLeading) {
                        CategoryArtwork(color: color, covers: covers, size: proxy.size)
                        name
                            .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
                            .frame(width: proxy.size.width * nameShare - CategoryCardMetrics.padding * (typeSize.isAccessibilitySize ? 2 : 1), alignment: .leading)
                            .padding(CategoryCardMetrics.padding)
                    }
                }
            }
            .clipShape(.rect(cornerRadius: CategoryCardMetrics.radius, style: .continuous))
    }

    @ViewBuilder
    private var name: some View {
        let line = min(glyphHeight, CategoryCardMetrics.glyphHeight * 1.3)
        let glyph = Image(systemName: symbol)
            .font(.system(size: (line * 0.82).rounded(), weight: .bold))
            .symbolRenderingMode(.hierarchical)
            .foregroundStyle(.white)
            .frame(height: line, alignment: .leading)
            .accessibilityHidden(true)
        let text = Text(title)
            .font(CategoryCardMetrics.font)
            .foregroundStyle(.white)
            .lineLimit(2)
            .minimumScaleFactor(typeSize.isAccessibilitySize ? 0.6 : 0.8)
            .multilineTextAlignment(.leading)
        VStack(alignment: .leading, spacing: 6) {
            text
            glyph
        }
    }
}

enum CategoryCardMetrics {
    #if os(macOS)
    static let height: CGFloat = 140
    static let radius: CGFloat = 12
    static let padding: CGFloat = 14
    static let font = Font.title3.bold()
    static let glyphHeight: CGFloat = 31
    #else
    static let height: CGFloat = 120
    static let radius: CGFloat = 14
    static let padding: CGFloat = 12
    static let font = Font.headline.bold()
    static let glyphHeight: CGFloat = 34
    #endif
    static let aspect: CGFloat = 1.6
}

private struct CategoryArtwork: View {
    let color: Color
    let covers: [CoverArt]
    let size: CGSize

    var body: some View {
        let side = (size.height * 0.7).rounded()
        let front = CGPoint(x: size.width - side * 0.28, y: size.height * 0.82)
        ZStack {
            color
            ForEach(Self.angles.indices.reversed(), id: \.self) { index in
                Group {
                    if covers.isEmpty {
                        RoundedRectangle(cornerRadius: CoverImage.radius(for: side), style: .continuous)
                            .fill(.white.opacity(0.14 + 0.05 * Double(Self.angles.count - index)))
                            .frame(width: side, height: side)
                    } else if index < covers.count {
                        CoverImage(cover: covers[index], size: side)
                            .shadow(color: .black.opacity(0.3), radius: 6, y: 3)
                    }
                }
                .rotationEffect(.degrees(Self.angles[index]))
                .position(x: front.x - side * 0.16 * CGFloat(index), y: front.y + side * 0.03 * CGFloat(index))
            }
        }
        .frame(width: size.width, height: size.height)
        .accessibilityHidden(true)
    }

    private static let angles: [Double] = [12, 0, -12]
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
            .brightness(isPressed && reduceMotion ? -0.08 : isHovering ? 0.04 : 0)
            .scaleEffect(isPressed && !reduceMotion ? 0.97 : 1)
            .animation(PlayMotion.press, value: isPressed)
            .contentShape(shape)
            #if os(macOS)
            .contentShape(.focusEffect, shape)
            .onHover { hovering in
                withAnimation(PlayMotion.hover) { isHovering = hovering }
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
    @ScaledMetric(relativeTo: .headline) private var height = CategoryCardMetrics.height

    var body: some View {
        let height = min(height, CategoryCardMetrics.height * 1.4)
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.title3.bold())
                .padding(.horizontal, PlayMetrics.margin)
            ScrollView(.horizontal) {
                HStack(spacing: PlayMetrics.shelfSpacing) {
                    ForEach(0..<6, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: CategoryCardMetrics.radius, style: .continuous)
                            .fill(Color.placeholderFill)
                            .frame(width: (height * CategoryCardMetrics.aspect).rounded(), height: height)
                    }
                }
            }
            .contentMargins(.horizontal, PlayMetrics.margin, for: .scrollContent)
            .scrollDisabled(true)
            .scrollIndicators(.hidden)
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Loading \(title)"))
    }
}
