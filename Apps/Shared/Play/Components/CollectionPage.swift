import SwiftUI
import TracksCore

#if os(iOS)
extension View {
    /// The chrome of an album's, playlist's or mix's page on iPhone: the cover under the bars,
    /// the page in the cover's colour all the way down its songs, and the title passing into
    /// the bar as the header's scrolls under it. Apply it to the page's `List`.
    ///
    /// Without `cover`, the page takes its colour from the ``CollectionHeader`` in it. Passing
    /// it lets the colour be there from the first frame.
    func collectionPage(title: String, cover: CoverArt? = nil) -> some View {
        modifier(CollectionChrome(title: title, cover: cover))
    }
}

private struct CollectionChrome: ViewModifier {
    let title: String
    let cover: CoverArt?
    /// The cover the header hands up, for pages that don't pass one.
    @State private var headerCover: CoverArt?
    /// Where the header's title is in the list's frame, as it scrolls. The list's, not the
    /// screen's: a zoom from a cover scales the whole page while it opens.
    @State private var titleTop: CGFloat = .greatestFiniteMagnitude
    @State private var barBottom: CGFloat = 0

    func body(content: Content) -> some View {
        let handoff = Self.handoff(titleTop: titleTop, barBottom: barBottom)
        content
            .environment(\.collectionTitleTop, $titleTop)
            .environment(\.collectionTitleHandoff, handoff)
            .environment(\.collectionPageCover, $headerCover)
            .environment(\.collectionBarBottom, barBottom)
            .listStyle(.plain)
            .ignoresSafeArea(edges: .top)
            .scrollEdgeEffectStyle(.soft, for: .top)
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
            .pageColor(from: cover ?? headerCover)
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
    /// Where a collection's header hands its cover up to the page around it.
    @Entry var collectionPageCover: Binding<CoverArt?>?
    /// Where the bars end over a collection's page, which runs on under them.
    @Entry var collectionBarBottom: CGFloat = 0
}
#endif

/// Share and More over a collection's page, together in one capsule of glass, as Music
/// groups them; the back button stands alone. Without an address to share, More is alone.
struct CollectionToolbar<Items: View>: ToolbarContent {
    var share: URL?
    @ViewBuilder var more: Items

    var body: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            // Untinted, so the glyphs follow the glass under them as Back's do; the page's own
            // colour scheme can disagree with the glass's and wash them out.
            if let share {
                ShareLink(item: share)
                    .help("Share")
            }
            Menu("More", systemImage: "ellipsis") { more }
                .help("More")
        }
    }
}

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
