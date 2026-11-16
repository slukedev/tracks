import SwiftUI
import TracksCore

/// The top of a genre's page: a genre is a scene and its records, so its covers fill the top
/// as a wall, tilted like a shelf of sleeves, melting into its colour, with its name large in
/// its own type over them and its station, the way to hear all of it, right under the name.
struct GenreHero<Actions: View>: View {
    let genre: MusicGenre
    /// When you play it, how long it's been yours, or what it is. Nil while that's read.
    let line: String?
    let covers: [CoverArt]
    var isLoading = false
    @ViewBuilder var actions: Actions

    #if os(iOS)
    @ScaledMetric(relativeTo: .largeTitle) private var titleSize: CGFloat = 58
    #endif

    var body: some View {
        let style = genre.style
        VStack(alignment: .leading, spacing: 0) {
            Text(genre.title)
                .font(style.title.font(size: titleSize))
                .tracking(style.title.tracking)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .shadow(color: .black.opacity(0.25), radius: 12, y: 2)
                .accessibilityAddTraits(.isHeader)
            Text(line ?? " ")
                .font(.title3.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
                .opacity(line == nil ? 0 : 1)
                .animation(.easeOut(duration: 0.2), value: line)
                .accessibilityHidden(line == nil)
                .padding(.top, 2)
            actions
                .padding(.top, 20)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: Self.wordsWidth, alignment: .leading)
        .padding(.horizontal, PlayMetrics.margin)
        .padding(.top, Self.wallHeight)
        .padding(.bottom, RoomField.fade)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .top) {
            ZStack(alignment: .top) {
                RoomField(style: style)
                CoverWall(covers: covers, symbol: genre.symbol, isLoading: isLoading)
                    .frame(height: Self.underBar + Self.wallHeight + Self.wallFade)
                    .mask {
                        LinearGradient(stops: [
                            .init(color: .black, location: 0),
                            .init(color: .black.opacity(0.85), location: 0.6),
                            .init(color: .clear, location: 1),
                        ], startPoint: .top, endPoint: .bottom)
                    }
                    .padding(.top, -Self.underBar)
            }
        }
    }

    #if os(macOS)
    private var titleSize: CGFloat { 80 }
    private static var wallHeight: CGFloat { 220 }
    private static var wordsWidth: CGFloat { 640 }
    #else
    private static var wallHeight: CGFloat { 250 }
    private static var wordsWidth: CGFloat { .infinity }
    #endif
    /// How far below the wall's last full row its covers keep showing, fading into the colour
    /// behind the name.
    private static var wallFade: CGFloat { 110 }
    /// How far the wall carries on up under the bar, so it has no top edge.
    private static var underBar: CGFloat { 160 }
}

/// Covers as a wall of sleeves, tilted, edge to edge, repeating so a genre with a few covers
/// still fills it. With none, the genre's symbol, large and faint; while they load, faint
/// squares in their places.
struct CoverWall: View {
    let covers: [CoverArt]
    let symbol: String
    var isLoading = false

    var body: some View {
        GeometryReader { proxy in
            let side = Self.side
            let step = side + Self.gap
            let columns = Int((proxy.size.width * 1.4) / step) + 2
            let rows = Int((proxy.size.height * 1.4) / step) + 2
            if covers.isEmpty, !isLoading {
                Image(systemName: symbol)
                    .font(.system(size: proxy.size.height * 0.8, weight: .bold))
                    .foregroundStyle(.white.opacity(0.14))
                    .rotationEffect(.degrees(-12))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .offset(x: 40)
            } else {
                VStack(spacing: Self.gap) {
                    ForEach(0..<rows, id: \.self) { row in
                        HStack(spacing: Self.gap) {
                            ForEach(0..<columns, id: \.self) { column in
                                tile(at: row * columns + column + row * 3)
                            }
                        }
                        // Each row a little along from the one above, as sleeves on a shelf.
                        .offset(x: CGFloat(row % 2) * step / 2)
                    }
                }
                .rotationEffect(.degrees(-9))
                .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
            }
        }
        .clipped()
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func tile(at index: Int) -> some View {
        if covers.isEmpty {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(.white.opacity(0.14))
                .frame(width: Self.side, height: Self.side)
        } else {
            CoverImage(cover: covers[index % covers.count], size: Self.side)
                .shadow(color: .black.opacity(0.2), radius: 6, y: 3)
        }
    }

    #if os(macOS)
    private static var side: CGFloat { 112 }
    #else
    private static var side: CGFloat { 96 }
    #endif
    private static var gap: CGFloat { 10 }
}

/// Your listening in a genre, in three figures under its hero: how much, how many artists, and
/// since when.
struct GenreFigures: View {
    let plays: Int
    let artists: Int
    let since: Date?
    let style: RoomStyle

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            figure(plays.formatted(), String(localized: "Plays"))
            figure(artists.formatted(), String(localized: "Artists"))
            if let since {
                figure(since.formatted(.dateTime.year()), String(localized: "Since"))
            }
        }
        .frame(maxWidth: Self.width, alignment: .leading)
    }

    private func figure(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(style.title.font(size: Self.valueSize))
                .monospacedDigit()
                .foregroundStyle(style.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(value))
    }

    #if os(macOS)
    private static var valueSize: CGFloat { 30 }
    private static var width: CGFloat { 520 }
    #else
    private static var valueSize: CGFloat { 28 }
    private static var width: CGFloat { .infinity }
    #endif
}

/// The genres closest to one, as links in their own colours, for the foot of its page.
struct GenreNeighbors: View {
    let genre: MusicGenre

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(genre.neighbors) { neighbor in
                    NavigationLink(value: PlayRoute.genre(neighbor)) {
                        Label(neighbor.title, systemImage: neighbor.symbol)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .frame(minHeight: Self.height)
                            .background(neighbor.color.gradient, in: .capsule)
                            .padding(.vertical, Self.reach)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.pressable)
                }
            }
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
    }

    #if os(macOS)
    private static var height: CGFloat { 28 }
    private static var reach: CGFloat { 0 }
    #else
    private static var height: CGFloat { 36 }
    private static var reach: CGFloat { 4 }
    #endif
}
