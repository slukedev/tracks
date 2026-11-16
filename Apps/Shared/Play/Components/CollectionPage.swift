import SwiftUI
import TracksCore

#if os(iOS)
extension View {
    func collectionPage(title: String) -> some View {
        modifier(CollectionChrome(title: title))
    }
}

private struct CollectionChrome: ViewModifier {
    let title: String
    @State private var isArtworkUnderBar = true
    /// Where the header's title is in the list's frame, as it scrolls. The list's, not the
    /// screen's: a zoom from a cover scales the whole page while it opens.
    @State private var titleTop: CGFloat = .greatestFiniteMagnitude
    @State private var barBottom: CGFloat = 0
    @ScaledMetric(relativeTo: .title2) private var artworkDepth: CGFloat = 420

    func body(content: Content) -> some View {
        let handoff = Self.handoff(titleTop: titleTop, barBottom: barBottom)
        content
            .environment(\.collectionTitleTop, $titleTop)
            .environment(\.collectionTitleHandoff, handoff)
            .listStyle(.plain)
            .ignoresSafeArea(edges: .top)
            .scrollEdgeEffectStyle(.soft, for: .top)
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top < artworkDepth
            } action: { _, under in
                isArtworkUnderBar = under
            }
            .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { barBottom = $0 }
            .navigationTitle(title)
            .toolbarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(title)
                        .font(.headline)
                        .lineLimit(1)
                        .opacity(handoff)
                        .offset(y: (1 - handoff) * Self.rise)
                        .accessibilityHidden(handoff < 0.5)
                }
            }
            .toolbarColorScheme(isArtworkUnderBar ? .dark : nil, for: .navigationBar)
    }

    /// How far the title has gone from the header into the bar: 0 while the header's title is
    /// clear of the bar, 1 once it's passed under it, in twentieths so a slow scroll doesn't
    /// redraw the bar every point.
    static func handoff(titleTop: CGFloat, barBottom: CGFloat) -> Double {
        let travelled = (barBottom + lead - titleTop) / travel
        return (Double(min(1, max(0, travelled))) * 20).rounded() / 20
    }

    private static let lead: CGFloat = 24
    private static let travel: CGFloat = 36
    private static let rise: CGFloat = 8
}

extension EnvironmentValues {
    @Entry var collectionTitleTop: Binding<CGFloat>?
    @Entry var collectionTitleHandoff: Double = 0
}
#endif

#if DEBUG
/// Sample playlists for screenshots, with sample data only. `-TracksDemoPlaylists YES` makes
/// them; `list` opens them all, and a number one of them: 1 a long one, 2 a short one, 3 an
/// empty one with a long name, 4 a smart one. `-TracksCollectionSheet add` then opens Add Songs on it, and
/// `pick` the sheet that adds songs to a playlist. Debug builds only.
enum CollectionDemo {
    @MainActor
    static func seedPlaylists(music: YourMusic, isDemo: Bool, open: OpenPlayRouteAction) {
        guard isDemo, let value = UserDefaults.standard.string(forKey: "TracksDemoPlaylists"),
              music.playlists.all.isEmpty, !music.index.tracks.isEmpty
        else { return }
        let tracks = music.index.tracks
        let made = [
            music.playlists.create(name: "Late Night Drives", tracks: Array(tracks.prefix(18))),
            music.playlists.create(name: "Sunday Morning", tracks: Array(tracks.dropFirst(18).prefix(3))),
            music.playlists.create(name: "Everything I Meant to Say on the Drive Home, the Long Way"),
            music.playlists.create(
                name: "Juniper Lane, Most Played",
                rules: SmartRules(source: .yourMusic, conditions: [SmartRules.Condition(field: .artist, text: "Juniper Lane")], order: .mostPlayed)
            ),
        ]
        if value == "list" {
            open(.yourMusic(.playlists))
        } else if let number = Int(value), made.indices.contains(number - 1) {
            open(.tracksPlaylist(made[number - 1].id))
        }
    }

    static var sheet: String? { UserDefaults.standard.string(forKey: "TracksCollectionSheet") }
}
#endif
