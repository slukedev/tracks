import SwiftUI

/// A row that leaves the app, marked with a trailing arrow and tinted like the rows around
/// it rather than like a blue web link.
struct SettingsLinkRow: View {
    var title: LocalizedStringKey
    var systemImage: String
    var tint: Color
    var destination: URL

    var body: some View {
        Link(destination: destination) {
            HStack {
                // The Mac's settings list their links as words, as System Settings does; glyphs
                // of different widths left the titles ragged.
                #if os(macOS)
                Text(title)
                #else
                Label {
                    Text(title)
                } icon: {
                    SettingsIconTile(systemImage: systemImage, color: tint)
                }
                #endif
                Spacer()
                Image(systemName: "arrow.up.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .tint(.primary)
        #if os(macOS)
        .buttonStyle(.plain)
        #endif
    }
}
