import SwiftUI
import WidgetKit
import TracksCore

/// One song in a widget list: its cover, its title, and who it is by.
struct WidgetSongRow: View {
    /// How much room a row gets, which the widget's size decides.
    enum Style {
        /// The medium widget, where three rows and a header fill the height.
        case compact
        /// The large widget, with room for a slightly bigger cover and type.
        case regular

        var artworkSize: CGFloat {
            switch self {
            case .compact: 28
            case .regular: 32
            }
        }

        /// Between rows, and between a section's header and its first row.
        var spacing: CGFloat {
            switch self {
            case .compact: 6
            case .regular: 8
            }
        }

        /// From the leading edge to the titles, so a line under the rows can line up with
        /// the text rather than the covers.
        var textInset: CGFloat { artworkSize + Self.artworkToText }

        static let artworkToText: CGFloat = 8

        fileprivate var titleFont: Font {
            switch self {
            case .compact: .caption
            case .regular: .footnote
            }
        }
    }

    let capture: WidgetCapture
    let style: Style

    var body: some View {
        // Opens the song's page. Medium and large widgets can have a link per row.
        Link(destination: DeepLink.song(title: capture.title, artistName: capture.artistName).url) {
            HStack(spacing: Style.artworkToText) {
                WidgetArtwork(data: capture.artwork, size: style.artworkSize)
                VStack(alignment: .leading, spacing: 0) {
                    Text(capture.title)
                        .font(style.titleFont)
                        .lineLimit(1)
                    HStack(spacing: 3) {
                        if capture.source == .spotify {
                            SpotifyWidgetMark(size: 9)
                        }
                        Text(capture.artistName)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                // Redacted on a locked device when that's asked for. One line each either
                // way, so the rows keep their height.
                .privacySensitive()
                Spacer(minLength: 0)
            }
            .contentShape(.rect)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(capture.source == .spotify ? Text("\(capture.title) by \(capture.artistName), Spotify") : Text("\(capture.title) by \(capture.artistName)"))
    }
}

/// Spotify's mark on a play from Spotify, in its own colours, or tinted with the widget in
/// accented and clear modes.
struct SpotifyWidgetMark: View {
    let size: CGFloat
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        Group {
            if renderingMode == .fullColor {
                Image("SpotifyIcon")
                    .resizable()
            } else {
                Image("SpotifyIconMono")
                    .renderingMode(.template)
                    .resizable()
                    .widgetAccentable()
            }
        }
        .scaledToFit()
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
