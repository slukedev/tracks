import SwiftUI
import WidgetKit

/// A section's title, with its symbol.
struct WidgetSectionHeader: View {
    let title: LocalizedStringKey
    let systemImage: String

    /// A column for the symbol, so titles line up when symbols differ in width.
    @ScaledMetric(relativeTo: .caption) private var symbolWidth: CGFloat = 18

    var body: some View {
        // When it doesn't fit, the title drops its symbol.
        ViewThatFits(in: .horizontal) {
            header(showsSymbol: true)
            header(showsSymbol: false)
        }
    }

    private func header(showsSymbol: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            if showsSymbol {
                Image(systemName: systemImage)
                    .frame(width: symbolWidth)
                    .accessibilityHidden(true)
            }
            Text(title)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .lineLimit(1)
        // Takes the accent on a tinted Home Screen, so headers stand out from the songs.
        .widgetAccentable()
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
