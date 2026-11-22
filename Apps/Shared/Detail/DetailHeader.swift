import SwiftUI
import TracksCore

/// The top of an artist's, album's or song's stats page: the art, its colour washed up under
/// the bar and fading into the page, then the name and what it is. The panels below sit on
/// the plain page, as Health's do.
struct DetailHeader<Words: View, Actions: View>: View {
    let artworkURL: String?
    let seed: String
    var isCircle = false
    @ViewBuilder var words: Words
    @ViewBuilder var actions: Actions

    @ScaledMetric(relativeTo: .largeTitle) private var scaledSide: CGFloat = 200
    /// More than any bar and status bar together, and the stretch of a pull past the top.
    private var reach: CGFloat { 400 }

    var body: some View {
        VStack(spacing: Design.Spacing.large) {
            Group {
                if isCircle, artworkURL?.isEmpty ?? true {
                    // An artist with no picture gets their initials, never a made-up picture.
                    ArtistMonogram(name: seed, size: side)
                } else {
                    ArtworkView(url: artworkURL, seed: seed, size: side, isCircle: isCircle)
                }
            }
            .accessibilityHidden(true)
            VStack(spacing: Design.Spacing.xSmall) {
                words
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            actions
        }
        .padding(.top, Design.Spacing.small)
        .padding(.horizontal, Design.Margin.page)
        .background(alignment: .bottom) {
            // Reaches up past the header under the bar, so the colour starts at the screen's
            // top edge and scrolls away with the header.
            CoverWash(artworkURL: artworkURL, seed: seed)
                .padding(.top, -reach)
        }
    }

    /// Smaller for an artist's round picture, as Music sets them, and capped so the largest
    /// text sizes leave room for the words.
    private var side: CGFloat {
        min(isCircle ? scaledSide * 0.8 : scaledSide, Design.Shelf.maximumTile + 40)
    }
}

extension View {
    /// The page's name in the bar only once the header's own name has scrolled under it, as
    /// Music's album and artist pages do. It stays the stack's title throughout, for Back.
    func detailTitle(_ title: String) -> some View {
        modifier(DetailTitle(title: title))
    }
}

private struct DetailTitle: ViewModifier {
    let title: String
    #if os(iOS)
    @State private var showsTitle = false
    #endif

    func body(content: Content) -> some View {
        #if os(iOS)
        content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > Self.headerHeight
            } action: { _, shows in
                showsTitle = shows
            }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(title)
                        .font(.headline)
                        .lineLimit(1)
                        .opacity(showsTitle ? 1 : 0)
                        .animation(Design.Motion.fade, value: showsTitle)
                        .accessibilityHidden(!showsTitle)
                }
            }
        #else
        content.navigationTitle(title)
        #endif
    }

    #if os(iOS)
    /// About where the header's name passes under the bar.
    private static let headerHeight: CGFloat = 280
    #endif
}

/// The cover softened into a field of its own colours, fading into the page at the bottom.
/// It's drawn once: nothing in it moves.
struct CoverWash: View {
    let artworkURL: String?
    let seed: String
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        if contrast == .standard {
            ArtworkView(url: artworkURL, seed: seed, size: nil)
                .scaledToFill()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .blur(radius: 60)
                .saturation(1.3)
                .opacity(colorScheme == .dark ? 0.6 : 0.55)
                .clipped()
                // Blurred once into a picture of its own, not again on every frame of a scroll.
                .drawingGroup()
                .mask {
                    LinearGradient(stops: [
                        .init(color: .black, location: 0),
                        .init(color: .black, location: 0.55),
                        .init(color: .clear, location: 1),
                    ], startPoint: .top, endPoint: .bottom)
                }
                .accessibilityHidden(true)
        }
    }
}

/// "Alternative · 1994", the line under a name in Music's headers.
struct DetailFacts: View {
    let parts: [String?]

    var body: some View {
        if let line = CollectionFacts.line(parts) {
            line
                .eyebrowStyle()
                .foregroundStyle(.secondary)
        }
    }
}

/// The page's shape while its figures are worked out: never a spinner in the middle of it.
struct DetailPlaceholder: View {
    var isCircle = false

    var body: some View {
        VStack(spacing: Design.Card.sectionSpacing) {
            VStack(spacing: Design.Spacing.large) {
                Group {
                    if isCircle {
                        Circle().fill(Color.placeholderFill).frame(width: 160, height: 160)
                    } else {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(Color.placeholderFill)
                            .frame(width: 200, height: 200)
                    }
                }
                VStack(spacing: Design.Spacing.xSmall) {
                    Text(verbatim: "Placeholder Title").font(.collectionTitle)
                    Text(verbatim: "Artist Name").font(.title3)
                }
                .redacted(reason: .placeholder)
            }
            .padding(.top, Design.Spacing.small)
            CardBackground()
                .frame(height: 150)
                .padding(.horizontal, Design.Margin.page)
        }
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }
}

/// Figures under a detail header, in one panel: two across on iPhone, four on the Mac.
struct StatStrip: View {
    struct Item: Identifiable {
        let title: LocalizedStringKey
        let value: String
        var detail: Text?
        var id: String { "\(title)" }
    }

    let items: [Item]

    var body: some View {
        Card {
            #if os(macOS)
            FigureGrid(figures: figures, columns: 4)
            #else
            FigureGrid(figures: figures, columns: 2)
            #endif
        }
    }

    /// A step under the Summary's figures: four facts about one thing, none of them the point.
    private static let valueFont = Font.system(.title3, design: .rounded, weight: .bold)

    private var figures: [GlanceFigure] {
        items.map {
            GlanceFigure(title: $0.title, value: Text($0.value), detail: $0.detail, valueFont: Self.valueFont)
        }
    }
}

/// Under a panel cut short: shows the rest in place, or folds it back.
struct ShowAllButton: View {
    @Binding var isShowingAll: Bool
    let count: Int
    let limit: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if count > limit {
            Button(isShowingAll ? "Show Less" : "Show All \(count.formatted())") {
                withAnimation(Design.Motion.reduced(Design.Motion.panel, reduceMotion: reduceMotion)) {
                    isShowingAll.toggle()
                }
            }
            .font(.subheadline.weight(.semibold))
            .buttonStyle(.borderless)
            .frame(maxWidth: .infinity, minHeight: Design.Control.minimumTarget)
        }
    }
}

#if DEBUG
#Preview("Header") {
    ScrollView {
        DetailHeader(artworkURL: nil, seed: "Coastlines") {
            Text(verbatim: "Coastlines").font(.collectionTitle)
            Text(verbatim: "Mara Solis").font(.title3).foregroundStyle(.tint)
            DetailFacts(parts: ["Indie Pop", "2024"])
        } actions: {
            PlayButton {}
        }
        StatStrip(items: [
            .init(title: "Plays", value: "116"),
            .init(title: "Listening", value: "6 hr 20 min"),
            .init(title: "Songs", value: "14"),
            .init(title: "First Heard", value: "Mar 4"),
        ])
        .padding(Design.Margin.page)
    }
}

#Preview("Header, artist, dark") {
    ScrollView {
        DetailHeader(artworkURL: nil, seed: "Mara Solis", isCircle: true) {
            Text(verbatim: "Mara Solis").font(.pageTitle)
        } actions: {
            EmptyView()
        }
    }
    .preferredColorScheme(.dark)
}

#Preview("Placeholder") {
    DetailPlaceholder()
}
#endif
