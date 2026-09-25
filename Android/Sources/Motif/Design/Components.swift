import SwiftUI

/// A Material 3 filled card: `surfaceContainerHighest`, 12 dp corners, 16 dp padding.
struct MaterialCard<Content: View>: View {
    var role: MaterialRole = .surfaceContainerHighest
    var padding: CGFloat = Space.screen
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content()
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .materialBackground(role, cornerRadius: Corner.medium)
    }
}

/// A symbol in a 48 dp circle of a container colour, as Material lists and cards lead with.
struct IconBadge: View {
    let symbol: Symbol
    var container: MaterialRole = .primaryContainer
    var content: MaterialRole = .onPrimaryContainer
    var size: CGFloat = 48

    var body: some View {
        SymbolImage(symbol, size: size / 2, role: content)
            .frame(width: size, height: size)
            .materialBackground(container, cornerRadius: size / 2)
    }
}

/// A section's title with an optional "View all" link, as in Material's feed layouts.
struct SectionHeader: View {
    let title: String
    var destination: Route?
    var actionTitle = "View all"

    var body: some View {
        HStack(alignment: .center) {
            Text(title)
                .materialType(.titleLarge)
                .materialForeground(.onSurface)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if let destination {
                NavigationLink(value: destination) {
                    Text(actionTitle)
                        .materialType(.labelLarge)
                        .materialForeground(.primary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: Space.touchTarget)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(minHeight: Space.touchTarget)
    }
}

/// A cover, or a neutral tile with a note on it while there's none. Never a made-up cover.
///
/// Takes a cover reference from the history or a queue (see ``ServerStore/coverRef``), which
/// becomes an address on the connected server here, or a plain address.
struct Artwork: View {
    let url: String?
    var size: CGFloat = 56
    var corner: CGFloat = Corner.small
    var placeholder: Symbol = .song
    @Environment(ServerStore.self) var servers

    var body: some View {
        ZStack {
            placeholderTile
            if let parsed = servers.coverURL(url, size: Int(size * 3)) {
                // The phases rather than content and placeholder: Coil can't tell on the first
                // frame whether a cached cover will draw, and the tile underneath covers the wait.
                AsyncImage(url: parsed) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        Color.clear
                    }
                }
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: corner))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private var placeholderTile: some View {
        SymbolImage(placeholder, size: size * 0.42, role: .onSurfaceVariant)
            .frame(width: size, height: size)
            .materialBackground(.surfaceContainerHighest, cornerRadius: corner)
    }
}

/// An artist's initials in a tonal circle, as Android's contacts show people without a photo.
struct Monogram: View {
    let name: String
    var size: CGFloat = 56

    var body: some View {
        Text(Self.initials(of: name))
            .materialType(size >= 96 ? .displaySmall : size >= 64 ? .headlineSmall : .titleMedium)
            .materialForeground(.onSecondaryContainer)
            .frame(width: size, height: size)
            .materialBackground(.secondaryContainer, cornerRadius: size / 2)
            .accessibilityHidden(true)
    }

    static func initials(of name: String) -> String {
        let words = name
            .split(whereSeparator: { $0 == " " || $0 == "&" || $0 == "-" })
            .filter { $0.first?.isLetter == true || $0.first?.isNumber == true }
        let letters = words.prefix(2).compactMap(\.first).map { String($0).uppercased() }
        return letters.isEmpty ? "♪" : letters.joined()
    }
}

/// A Material list item: leading visual, headline, supporting text, trailing content.
///
/// 16 dp from the edges, 16 dp between the leading visual and the text, and at least 56 dp
/// tall (72 with an image), so every row is comfortably above the 48 dp touch target.
struct ListRow<Leading: View, Trailing: View>: View {
    let headline: String
    var supporting: String?
    var overline: String?
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: Space.iconGap) {
            leading()
            VStack(alignment: .leading, spacing: 2) {
                if let overline {
                    Text(overline)
                        .materialType(.labelMedium)
                        .materialForeground(.onSurfaceVariant)
                        .lineLimit(1)
                }
                Text(headline)
                    .materialType(.bodyLarge)
                    .materialForeground(.onSurface)
                    .lineLimit(1)
                if let supporting {
                    Text(supporting)
                        .materialType(.bodyMedium)
                        .materialForeground(.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.horizontal, Space.screen)
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .fullTapArea()
        .combinedAccessibility()
    }
}

extension ListRow where Trailing == EmptyView {
    init(headline: String, supporting: String? = nil, overline: String? = nil, @ViewBuilder leading: @escaping () -> Leading) {
        self.init(headline: headline, supporting: supporting, overline: overline, leading: leading, trailing: { EmptyView() })
    }
}

/// The chevron Material puts at the end of a row that opens something.
struct Chevron: View {
    var body: some View {
        SymbolImage(.chevron, size: 24, role: .onSurfaceVariant)
    }
}

/// Nothing to show yet: a 64 dp symbol, a title, a line of explanation and what to do about it.
struct EmptyState<Actions: View>: View {
    let symbol: Symbol
    let title: String
    let message: String
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        VStack(spacing: 0) {
            SymbolImage(symbol, size: 64, role: .onSurfaceVariant)
            Text(title)
                .materialType(.titleMedium)
                .materialForeground(.onSurface)
                .multilineTextAlignment(.center)
                .padding(.top, 16)
            Text(message)
                .materialType(.bodyMedium)
                .materialForeground(.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            VStack(spacing: 8) {
                actions()
            }
            .padding(.top, 24)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

extension EmptyState where Actions == EmptyView {
    init(symbol: Symbol, title: String, message: String) {
        self.init(symbol: symbol, title: title, message: message, actions: { EmptyView() })
    }
}

/// A share of something, as a Material linear progress indicator: 4 dp, fully rounded.
struct ShareBar: View {
    let fraction: Double

    var body: some View {
        ProgressView(value: min(max(fraction, 0), 1))
            .progressViewStyle(.linear)
            .accessibilityHidden(true)
    }
}

/// A label and value stacked, for the facts along the foot of a card.
struct Fact: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .materialType(.labelMedium)
                .materialForeground(.onSurfaceVariant)
                .lineLimit(1)
            Text(value)
                .materialType(.titleMedium)
                .materialForeground(.onSurface)
                .lineLimit(1)
        }
        .combinedAccessibility()
    }
}
