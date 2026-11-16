import SwiftUI
import TracksCore

struct RoomChips<Item: Identifiable>: View where Item.ID: Hashable {
    let items: [Item]
    let chosen: Item.ID?
    let style: RoomStyle
    let title: (Item) -> String
    var allowsNone = false
    let choose: (Item.ID?) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
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
