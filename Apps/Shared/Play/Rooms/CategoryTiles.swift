import SwiftUI
import TracksCore

struct CategoryCard: View {
    let title: String
    let symbol: String
    let color: Color
    let covers: [CoverArt]
    var fillsWidth = false
    @ScaledMetric(relativeTo: .headline) private var height = CategoryCardMetrics.height
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
                        CategoryArtwork(symbol: symbol, color: color, covers: covers, size: proxy.size)
                        Text(title)
                            .font(CategoryCardMetrics.font)
                            .foregroundStyle(.white)
                            .lineLimit(2)
                            .minimumScaleFactor(typeSize.isAccessibilitySize ? 0.6 : 0.8)
                            .multilineTextAlignment(.leading)
                            .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
                            .frame(width: proxy.size.width * nameShare - CategoryCardMetrics.padding * (typeSize.isAccessibilitySize ? 2 : 1), alignment: .leading)
                            .padding(CategoryCardMetrics.padding)
                    }
                }
            }
            .clipShape(.rect(cornerRadius: CategoryCardMetrics.radius, style: .continuous))
    }
}

enum CategoryCardMetrics {
    #if os(macOS)
    static let height: CGFloat = 140
    static let radius: CGFloat = 12
    static let padding: CGFloat = 14
    static let font = Font.title3.bold()
    #else
    static let height: CGFloat = 120
    static let radius: CGFloat = 14
    static let padding: CGFloat = 12
    static let font = Font.headline.bold()
    #endif
    static let aspect: CGFloat = 1.6
}

private struct CategoryArtwork: View {
    let symbol: String
    let color: Color
    let covers: [CoverArt]
    let size: CGSize

    var body: some View {
        let side = (size.height * 0.78).rounded()
        let front = CGPoint(x: size.width - side * 0.34, y: size.height * 0.74)
        ZStack {
            color
            if covers.isEmpty {
                RoundedRectangle(cornerRadius: CoverImage.radius(for: side), style: .continuous)
                    .fill(.white.opacity(0.16))
                    .overlay {
                        Image(systemName: symbol)
                            .font(.system(size: (side * 0.4).rounded(), weight: .semibold))
                            .foregroundStyle(.white.opacity(0.92))
                    }
                    .frame(width: side, height: side)
                    .rotationEffect(.degrees(Self.angles[0]))
                    .position(x: size.width - side * 0.58, y: size.height * 0.6)
            } else {
                ForEach(Array(covers.prefix(Self.angles.count).enumerated().reversed()), id: \.offset) { index, cover in
                    CoverImage(cover: cover, size: side)
                        .shadow(color: .black.opacity(0.3), radius: 6, y: 3)
                        .rotationEffect(.degrees(Self.angles[index]))
                        .position(x: front.x - side * 0.17 * CGFloat(index), y: front.y + side * 0.03 * CGFloat(index))
                }
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
