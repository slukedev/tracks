import SwiftUI
import TracksCore

/// A row of chips on a room's page, to choose one of a few, as a genre's kinds and decades, or
/// what a mood is made of. The chosen one wears the room's colour; tapping it again lets it go
/// where `allowsNone` says it can.
struct RoomChips<Item: Identifiable>: View where Item.ID: Hashable {
    let items: [Item]
    let chosen: Item.ID?
    let style: RoomStyle
    let title: (Item) -> String
    var allowsNone = false
    let choose: (Item.ID?) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // Deep in light, lifted in dark, so the chosen one stands off either page.
        let chosenFill = colorScheme == .dark ? style.base.mix(with: .white, by: 0.18) : style.base.mix(with: .black, by: 0.2)
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(items) { item in
                    let isChosen = item.id == chosen
                    Button {
                        choose(isChosen && allowsNone ? nil : item.id)
                    } label: {
                        Text(title(item))
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .lineLimit(1)
                            .foregroundStyle(isChosen ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                            .padding(.horizontal, 14)
                            .frame(minHeight: Self.height)
                            .background(isChosen ? AnyShapeStyle(chosenFill) : AnyShapeStyle(.fill.tertiary), in: .capsule)
                            .padding(.vertical, Self.reach)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isChosen ? .isSelected : [])
                }
            }
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: chosen)
    }

    #if os(macOS)
    private static var height: CGFloat { 28 }
    private static var reach: CGFloat { 0 }
    #else
    private static var height: CGFloat { 36 }
    /// Above and below, so a finger's target is 44 points tall.
    private static var reach: CGFloat { 4 }
    #endif
}

/// When you play a mood: the four parts of the day as columns, each as tall as its share of
/// the plays, the one you play it in most in the mood's colour. Only drawn once there are
/// enough plays to say.
struct DayPartBars: View {
    let shares: [Double]
    let style: RoomStyle
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let peak = shares.indices.max { shares[$0] < shares[$1] }
        HStack(alignment: .bottom, spacing: Self.spacing) {
            ForEach(DayPart.allCases, id: \.self) { part in
                let share = shares[part.rawValue]
                let isPeak = part.rawValue == peak
                VStack(spacing: 8) {
                    Text(share.formatted(.percent.precision(.fractionLength(0))))
                        .font(.caption.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(isPeak ? AnyShapeStyle(style.ink) : AnyShapeStyle(.secondary))
                    UnevenRoundedRectangle(topLeadingRadius: 8, topTrailingRadius: 8, style: .continuous)
                        .fill(isPeak ? AnyShapeStyle(style.base) : AnyShapeStyle(.fill.tertiary))
                        .frame(height: max(6, Self.tallest * share / max(shares.max() ?? 1, 0.01)))
                    Image(systemName: part.symbol)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(isPeak ? AnyShapeStyle(style.ink) : AnyShapeStyle(.secondary))
                    // The symbols say it alone at the largest sizes, where four names can't fit.
                    if !typeSize.isAccessibilitySize {
                        Text(part.name)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(part.name))
                .accessibilityValue(Text(share.formatted(.percent.precision(.fractionLength(0)))))
            }
        }
        .frame(maxWidth: Self.width)
    }

    #if os(macOS)
    private static let tallest: CGFloat = 96
    private static let spacing: CGFloat = 16
    private static let width: CGFloat = 520
    #else
    private static let tallest: CGFloat = 88
    private static let spacing: CGFloat = 12
    private static let width: CGFloat = .infinity
    #endif
}

extension ListeningRoom {
    /// The share of its plays in each part of the day, by ``DayPart`` raw value. Nil until
    /// there are enough plays for the shares to mean something.
    var dayPartShares: [Double]? {
        guard plays >= 12 else { return nil }
        var byPart = [Int](repeating: 0, count: DayPart.allCases.count)
        for song in songs {
            for (index, count) in song.partPlays.enumerated() { byPart[index] += count }
        }
        let total = Double(byPart.reduce(0, +))
        guard total > 0 else { return nil }
        return byPart.map { Double($0) / total }
    }
}
