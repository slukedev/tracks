import SwiftUI
import TracksCore

struct GenreHeader: View {
    let genre: MusicGenre
    /// Nil while the history is read, so one line doesn't give way to another as the page opens.
    let line: String?
    let facts: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label(String(localized: "Genre"), systemImage: genre.symbol)
                .font(.footnote.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
            Text(genre.title)
                .font(Self.titleFont)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .padding(.top, 2)
                .accessibilityAddTraits(.isHeader)
            Text(line ?? " ")
                .font(.title3)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .opacity(line == nil ? 0 : 1)
                .animation(.easeOut(duration: 0.2), value: line)
                .accessibilityHidden(line == nil)
            if let facts {
                Text(facts)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, PlayMetrics.margin)
    }

    #if os(macOS)
    private static var titleFont: Font { .system(size: 40, weight: .bold) }
    #else
    private static var titleFont: Font { .largeTitle.bold() }
    #endif
}

struct GenreStationCard<Actions: View>: View {
    let genre: MusicGenre
    let covers: [CoverArt]
    let note: String
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(genre.title) Station")
                .font(.title2.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(note)
                .font(.subheadline)
                .opacity(0.85)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 2)
            actions
                .padding(.top, 14)
        }
        .foregroundStyle(.white)
        .padding(Self.inset)
        .padding(.top, 90)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { ArtworkScrim() }
        .frame(maxWidth: .infinity, minHeight: Self.height, alignment: .bottom)
        .background {
            ArtworkMosaic(covers: covers, columns: Self.columns, fallback: genre.color, symbol: genre.symbol)
        }
        .clipShape(.rect(cornerRadius: Self.radius, style: .continuous))
        .padding(.horizontal, PlayMetrics.margin)
    }

    #if os(macOS)
    private static var columns: Int { 8 }
    private static var height: CGFloat { 300 }
    private static var inset: CGFloat { 24 }
    private static var radius: CGFloat { 18 }
    #else
    private static var columns: Int { 4 }
    private static var height: CGFloat { 320 }
    private static var inset: CGFloat { 18 }
    private static var radius: CGFloat { 22 }
    #endif
}
