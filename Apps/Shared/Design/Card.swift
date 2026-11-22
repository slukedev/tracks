import SwiftUI

/// A content panel: insights, a featured release, a well of figures. An opaque surface a step
/// off the page with continuous corners, never glass and never a border on one side. Children
/// can round concentrically inside it with `ConcentricRectangle()`.
struct Card<Content: View>: View {
    var padding: CGFloat = Design.Card.padding
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background { CardBackground() }
            .containerShape(.rect(cornerRadius: Design.Card.radius, style: .continuous))
    }
}

/// The card's surface alone. Under Increase Contrast it gains an edge all the way round.
struct CardBackground: View {
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Design.Card.radius, style: .continuous)
        shape
            .fill(Color.cardFill)
            .overlay {
                if contrast == .increased {
                    shape.strokeBorder(.separator, lineWidth: 1)
                }
            }
    }
}

/// A card's fill alone, for something that has to match the card it sits on.
struct CardFill: View {
    var body: some View {
        Color.cardFill
    }
}

extension View {
    /// The page behind a column of cards: the plain page, as Health and Fitness are on 27, so
    /// the scroll edge effect softens the cards under the bar.
    ///
    /// On the Mac it's the scroll view's own background. Painting a colour behind the scroll
    /// view instead gave the title bar a band of its own, a different shade with a hairline
    /// under it; a scroll view that draws its background lets the title bar blend into it.
    func groupedBackground() -> some View {
        #if os(iOS)
        background(GroupedBackground())
        #else
        scrollContentBackground(.visible)
        #endif
    }
}

/// The page behind a column of cards.
struct GroupedBackground: View {
    var body: some View {
        Color.pageBackground.ignoresSafeArea()
    }
}

/// A section's title, with a chevron when it opens a page of its own, as Music's "Recently
/// Played ›" does. The title and chevron are one button.
struct SectionTitle: View {
    let title: Text
    var action: (() -> Void)?

    var body: some View {
        if let action {
            Button(action: action) {
                HStack(alignment: .firstTextBaseline, spacing: Design.Spacing.xSmall) {
                    title
                    Image(systemName: "chevron.forward")
                        .font(.title3.weight(.semibold))
                        .imageScale(.small)
                        .foregroundStyle(.secondary)
                }
                .font(.sectionTitle)
                .foregroundStyle(.primary)
                .contentShape(.rect)
            }
            .buttonStyle(SectionLinkButtonStyle())
            .accessibilityAddTraits(.isHeader)
        } else {
            title
                .font(.sectionTitle)
                .accessibilityAddTraits(.isHeader)
        }
    }
}

/// A section title with an optional trailing link, in the style of Health and Fitness.
struct SectionHeader<Trailing: View>: View {
    let title: LocalizedStringKey
    /// Opens the section's own page, with a chevron after the title.
    var action: (() -> Void)?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        AdaptiveStack(verticalAlignment: .firstTextBaseline, spacing: Design.Spacing.xSmall) {
            SectionTitle(title: Text(title), action: action)
            Spacer(minLength: 0)
            trailing
                .font(.subheadline)
                .buttonStyle(SectionLinkButtonStyle())
                .foregroundStyle(.tint)
        }
        .padding(.horizontal, Design.Spacing.xSmall)
    }
}

/// Plain text that's easy to hit. "See All" in `.subheadline` is about 20 points tall, and a
/// finger needs 44. The extra room is an invisible area behind the text, so the header
/// doesn't get any taller or move.
struct SectionLinkButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.4 : 1)
            #if os(iOS)
            .background {
                // Centred on the text and allowed to spill past it. SwiftUI hit-tests the
                // spill-over, though layout ignores it.
                Color.clear
                    .frame(minWidth: Design.Control.minimumTarget, minHeight: Design.Control.minimumTarget)
                    .padding(.horizontal, Design.Spacing.small)
                    .contentShape(.rect)
            }
            #endif
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: LocalizedStringKey, action: (() -> Void)? = nil) {
        self.title = title
        self.action = action
        self.trailing = EmptyView()
    }
}

/// Small grey caption above a card's main value, like "LISTENING" in Screen Time.
struct CardLabel: View {
    let title: LocalizedStringKey
    var systemImage: String?
    var tint: Color = .secondary

    var body: some View {
        Group {
            if let systemImage {
                Label(title, systemImage: systemImage)
            } else {
                Text(title)
            }
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(tint)
    }
}

#Preview("Cards") {
    ScrollView {
        VStack(alignment: .leading, spacing: Design.Card.spacing) {
            SectionHeader("Recently Played") {}
            Card {
                VStack(alignment: .leading, spacing: Design.Spacing.xSmall) {
                    CardLabel(title: "Listening", systemImage: "headphones")
                    Text("12 hr 40 min").font(.statFigure)
                    Text("2 hr more than last week").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            SectionHeader(title: "Top Songs Over Everything You Played This Summer") {
                Button("See All") {}
            }
            Card {
                ConcentricRectangle()
                    .fill(.tint.opacity(0.2))
                    .frame(height: 80)
            }
        }
        .padding(Design.Margin.page)
    }
    .groupedBackground()
}

#Preview("Cards, dark") {
    VStack(spacing: Design.Card.spacing) {
        Card { Text("Biggest Day").font(.headline) }
        Card { Text("Longest Session").font(.headline) }
    }
    .padding(Design.Margin.page)
    .groupedBackground()
    .preferredColorScheme(.dark)
}
