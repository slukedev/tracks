import SwiftUI
import MusicKit
import TracksCore

/// What a collection is, for the small word over its title on the Mac.
enum CollectionKind {
    case album, single, ep, playlist, smartPlaylist, mix

    var eyebrow: String {
        switch self {
        case .album: String(localized: "Album")
        case .single: String(localized: "Single")
        case .ep: String(localized: "EP")
        case .playlist: String(localized: "Playlist")
        case .smartPlaylist: String(localized: "Smart Playlist")
        case .mix: String(localized: "Mix")
        }
    }

    /// An Apple Music album's kind, and its title without the " - Single" or " - EP" Apple
    /// Music puts on the end, since the header says so.
    static func of(_ album: Album) -> (kind: CollectionKind, title: String) {
        for (suffix, kind) in [(" - EP", CollectionKind.ep), (" - Single", .single)] where album.title.hasSuffix(suffix) {
            return (kind, String(album.title.dropLast(suffix.count)))
        }
        return (album.isSingle == true ? .single : .album, album.title)
    }
}

/// The circle on Play's far side from Shuffle: the other thing to do with the whole
/// collection, such as Add to Library. Without one, the header puts More there.
struct CollectionSecondary {
    fileprivate let control: AnyView

    /// A glass circle with a symbol, filled when `isOn`.
    init(_ title: LocalizedStringKey, systemImage: String, isOn: Bool = false, action: @escaping () -> Void) {
        self.control = AnyView(CollectionSecondaryButton(title: title, systemImage: systemImage, isOn: isOn, action: action))
    }

    /// A control that draws itself, such as a download that shows its progress, set in a
    /// glass circle.
    init(@ViewBuilder control: () -> some View) {
        self.control = AnyView(CollectionSecondaryCircle(content: control()))
    }
}

private struct CollectionSecondaryButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    let isOn: Bool
    let action: () -> Void
    @Environment(\.pageTone) private var tone

    var body: some View {
        GlassCircleButton(title: title, systemImage: systemImage, isOn: isOn, tint: tone?.glassTint, action: action)
    }
}

private struct CollectionSecondaryCircle<Content: View>: View {
    let content: Content
    @Environment(\.pageTone) private var tone

    var body: some View {
        content
            .labelStyle(.iconOnly)
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.plain)
            .glassCircle(tint: tone?.glassTint)
            .fixedSize()
    }
}

extension PageTone {
    /// Glass beside Play takes a little of the page's colour, so it reads as part of the page
    /// rather than a grey disc on it, as Music's circles do.
    var glassTint: Color { backgroundColor.opacity(0.5) }
}

/// The top of an album's, playlist's or mix's page.
///
/// On iPhone the cover runs edge to edge under the bars and dissolves into the page, which
/// takes the cover's colour (see ``SwiftUI/View/collectionPage(title:cover:)``); the title and
/// Play sit on that colour. On the Mac the cover sits beside the words, as Music's does.
struct CollectionHeader<Cover: View, More: View>: View {
    let kind: CollectionKind
    let title: String
    /// The artist or curator.
    var subtitle: String?
    /// Opens the artist, when there's one to open.
    var onSubtitle: (() -> Void)?
    /// "Alternative · 2024 · 12 songs, 48 min", or a smart playlist's rules.
    var facts: Text?
    /// A playlist's description: a few lines at most.
    var note: String?
    /// How a Your Music album is encoded.
    var format: AudioFormat?
    /// The sources that have it, with every source on and more than one has it.
    var sources: [SourceID] = []
    /// The cover the page takes its colour from.
    let tintCover: CoverArt?
    var canPlay = true
    var download: DownloadSubject?
    /// Play's other companion, when downloads are off or there's nothing to download.
    var secondary: CollectionSecondary?
    /// Still loading: the cover is a placeholder, the words wait, and Play waits too.
    var isLoading = false
    let play: () -> Void
    let shuffle: () -> Void
    @ViewBuilder var more: More
    /// The cover at the side it's given.
    @ViewBuilder var cover: (CGFloat) -> Cover

    #if os(iOS)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.pageTone) private var tone
    @Environment(\.collectionTitleTop) private var titleTop
    @Environment(\.collectionTitleHandoff) private var titleHandoff
    @Environment(\.collectionPageCover) private var pageCover
    @Environment(\.collectionBarBottom) private var barBottom
    @AppStorage(DownloadChoice.isOnKey) private var downloadIsOn = false
    #endif

    var body: some View {
        layout
    }

    #if os(iOS)
    private var layout: some View {
        VStack(spacing: 0) {
            if dynamicTypeSize.isAccessibilitySize {
                framedArtwork
                words
                    .padding(.top, Design.Spacing.xLarge)
            } else {
                artwork
                words
                    .padding(.top, -Self.overlap)
            }
        }
        .frame(maxWidth: .infinity)
        .pageInk()
        .listRowInsets(EdgeInsets())
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .onChange(of: tintCover, initial: true) { _, cover in
            // The page's colour arrives with the header, not faded in after it.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { pageCover?.wrappedValue = cover }
        }
    }

    /// The cover, square and edge to edge, dissolving into the page colour below it. Pulled
    /// past the top, it stretches to fill the gap rather than leaving one.
    private var artwork: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                GeometryReader { proxy in
                    art(proxy.size.width)
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                }
            }
            .mask {
                LinearGradient(stops: [
                    .init(color: .black, location: 0),
                    .init(color: .black, location: 0.6),
                    .init(color: .black.opacity(0.5), location: 0.82),
                    .init(color: .clear, location: 1),
                ], startPoint: .top, endPoint: .bottom)
            }
            .visualEffect { content, proxy in
                let pull = max(0, proxy.frame(in: .scrollView).minY)
                return content.scaleEffect(1 + pull / max(proxy.size.height, 1), anchor: .bottom)
            }
            .accessibilityHidden(true)
    }

    /// At accessibility sizes the words need the screen, so the cover steps back to a framed
    /// square under the bar, as Music's album page sets it, and Play stays within reach.
    private var framedArtwork: some View {
        Group {
            if isLoading {
                RoundedRectangle(cornerRadius: CoverImage.radius(for: Self.framedSide), style: .continuous)
                    .fill(Color.placeholderFill)
                    .frame(width: Self.framedSide, height: Self.framedSide)
            } else {
                cover(Self.framedSide)
            }
        }
        .padding(.top, barBottom + Design.Spacing.small)
        .accessibilityHidden(true)
    }

    private static var framedSide: CGFloat { 200 }

    /// How far the words reach up into the cover's dissolve, where it's mostly page.
    private static var overlap: CGFloat { 28 }

    @ViewBuilder
    private func art(_ side: CGFloat) -> some View {
        if isLoading {
            Color.placeholderFill
        } else {
            // A little larger than the square, so the cover's rounded corners fall outside it.
            cover(side + CoverImage.maximumRadius * 3)
        }
    }

    private var words: some View {
        VStack(spacing: 0) {
            if isLoading {
                placeholderText(width: 180, height: 22)
                placeholderText(width: 120, height: 18)
                    .padding(.top, Design.Spacing.small)
            } else {
                Text(title)
                    .font(.collectionTitle)
                    .foregroundStyle(.primary)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? 5 : 3)
                    .opacity(1 - titleHandoff)
                    .scaleEffect(1 - titleHandoff * 0.08, anchor: .top)
                    .onGeometryChange(for: CGFloat.self) { $0.frame(in: .scrollView).minY } action: { titleTop?.wrappedValue = $0 }
                    .accessibilityAddTraits(.isHeader)
                subtitleView
                    .padding(.top, Design.Spacing.xxSmall)
                if facts != nil || sources.count > 1 {
                    HStack(spacing: Design.Spacing.small) {
                        facts
                        if sources.count > 1 {
                            SourceGlyphRow(sources: sources)
                        }
                    }
                    .font(.footnote)
                    .foregroundStyle(ink.secondary)
                    .padding(.top, Design.Spacing.xSmall)
                }
                if let format {
                    FormatBadge(format: format, showsDetail: true)
                        .padding(.top, Design.Spacing.small)
                }
            }
            playRow
                .padding(.top, Design.Spacing.large)
            if !isLoading, let note, !note.isEmpty {
                CollectionNote(text: note, title: title)
                    .padding(.top, Design.Spacing.large)
            }
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Design.Margin.page)
        .padding(.bottom, Design.Spacing.medium)
    }

    /// Shuffle and the collection's other action beside Play, as glass circles taking a little
    /// of the page's colour; Play the one opaque thing.
    @ViewBuilder
    private var playRow: some View {
        let isDisabled = !canPlay || isLoading
        GlassEffectContainer(spacing: Design.Control.spacing) {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: Design.Control.spacing) {
                    PlayButton(fillsWidth: true, action: play)
                    // Wide enough apart that the larger circles stay two, not one merged shape.
                    HStack(spacing: Design.Spacing.xxxLarge) {
                        shuffleButton
                        trailingCircle
                    }
                }
            } else {
                HStack(spacing: Design.Control.spacing) {
                    shuffleButton
                    PlayButton(action: play)
                    if hasTrailingCircle {
                        trailingCircle
                    } else {
                        // Keeps Play centred under the title.
                        shuffleButton.hidden()
                    }
                }
            }
        }
        .disabled(isDisabled)
    }

    /// The page's own colours for the words, named rather than left to `.secondary`, which
    /// in a list's cell draws the system's grey at half strength whatever the page sets.
    private var ink: PageInkColors { PageInkColors(tone: tone) }

    private var shuffleButton: some View {
        GlassCircleButton(title: "Shuffle", systemImage: "shuffle", tint: tone?.glassTint, action: shuffle)
    }

    /// The download when downloads are on, or the page's own action. Without either there's
    /// no circle: More is in the toolbar already.
    @ViewBuilder
    private var trailingCircle: some View {
        if let download, downloadIsOn {
            CollectionSecondary { DownloadControl(subject: download) }.control
        } else if let secondary {
            secondary.control
        }
    }

    private var hasTrailingCircle: Bool {
        (download != nil && downloadIsOn) || secondary != nil
    }

    @ViewBuilder
    private var subtitleView: some View {
        if let subtitle, !subtitle.isEmpty {
            if let onSubtitle {
                Button(action: onSubtitle) {
                    Text(subtitle)
                        .font(.title3)
                        .foregroundStyle(ink.accent)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Opens the artist")
            } else {
                Text(subtitle)
                    .font(.title3)
                    .foregroundStyle(ink.secondary)
            }
        }
    }
    #else
    private var coverSide: CGFloat { 232 }

    /// Music's header on the Mac: the cover, and beside it what the collection is, who made
    /// it and Play. It scrolls away with the songs.
    private var layout: some View {
        HStack(alignment: .bottom, spacing: 30) {
            coverView
            VStack(alignment: .leading, spacing: 0) {
                if isLoading {
                    placeholderText(width: 60, height: 10)
                    placeholderText(width: 260, height: 28)
                        .padding(.top, Design.Spacing.small)
                    placeholderText(width: 140, height: 16)
                        .padding(.top, Design.Spacing.small)
                } else {
                    Text(kind.eyebrow)
                        .eyebrowStyle()
                    Text(title)
                        .font(.collectionTitle)
                        .lineLimit(2)
                        .help(title)
                        .padding(.top, Design.Spacing.xSmall)
                    if let subtitle, !subtitle.isEmpty {
                        CollectionSubtitleLink(title: subtitle, open: onSubtitle)
                            .padding(.top, 3)
                    }
                    if facts != nil || sources.count > 1 {
                        HStack(spacing: Design.Spacing.small) {
                            facts
                                .lineLimit(1)
                            if sources.count > 1 {
                                SourceGlyphRow(sources: sources)
                            }
                        }
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.top, 10)
                    }
                    if let format {
                        FormatBadge(format: format, onDark: false, showsDetail: true)
                            .padding(.top, Design.Spacing.small)
                    }
                    if let note, !note.isEmpty {
                        CollectionNote(text: note, title: title)
                            .padding(.top, 10)
                    }
                }
                ViewThatFits(in: .horizontal) {
                    buttonRow(playShape: .capsule)
                    buttonRow(playShape: .circle)
                }
                .padding(.top, 18)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Design.Margin.page)
        .padding(.top, Design.Spacing.xxLarge)
        .padding(.bottom, 28)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var coverView: some View {
        Group {
            if isLoading {
                RoundedRectangle(cornerRadius: CoverImage.radius(for: coverSide), style: .continuous)
                    .fill(Color.placeholderFill)
                    .frame(width: coverSide, height: coverSide)
            } else {
                cover(coverSide)
            }
        }
        .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
    }

    /// Play, opaque, with Shuffle, the download or the collection's own action, and More beside
    /// it in glass. Where Play's word doesn't fit, it's a circle.
    private func buttonRow(playShape: PlayButton.Shape) -> some View {
        GlassEffectContainer(spacing: Design.Control.spacing) {
            HStack(spacing: Design.Spacing.small) {
                PlayButton(shape: playShape, action: play)
                GlassCircleButton(title: "Shuffle", systemImage: "shuffle", action: shuffle)
                if let download {
                    CollectionSecondary { DownloadControl(subject: download) }.control
                } else if let secondary {
                    secondary.control
                }
                GlassCircleMenu(title: "More") {
                    more
                }
            }
        }
        .disabled(!canPlay || isLoading)
    }
    #endif

    private func placeholderText(width: CGFloat, height: CGFloat) -> some View {
        Capsule()
            .fill(Color.placeholderFill)
            .frame(width: width, height: height)
            .padding(.vertical, Design.Spacing.xxSmall)
            .accessibilityHidden(true)
    }
}

#if os(macOS)
/// The artist under a title on the Mac: a link, underlined under the pointer, as Music's is.
private struct CollectionSubtitleLink: View {
    let title: String
    let open: (() -> Void)?
    @State private var isHovering = false

    var body: some View {
        if let open {
            Button(action: open) {
                Text(title)
                    .underline(isHovering)
            }
            .buttonStyle(.plain)
            .font(.title3.weight(.medium))
            .foregroundStyle(.tint)
            .onHover { hovering in
                withAnimation(Design.Motion.hover) { isHovering = hovering }
            }
            .pointerStyle(.link)
            .help("Go to \(title)")
        } else {
            Text(title)
                .font(.title3.weight(.medium))
                .foregroundStyle(.secondary)
        }
    }
}

#endif

/// A collection's description: a few lines, and More for the rest, in a sheet on iPhone and
/// a popover on the Mac.
private struct CollectionNote: View {
    let text: String
    let title: String
    @State private var isShowingAll = false
    @Environment(\.pageTone) private var tone
    #if os(iOS)
    private static let lines = 3
    private static let font = Font.subheadline
    private static let width = CGFloat.infinity
    #else
    private static let lines = 2
    private static let font = Font.callout
    private static let width: CGFloat = 560
    #endif
    @State private var fullHeight: CGFloat = 0
    @State private var shownHeight: CGFloat = 0

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: Design.Spacing.small) {
            Text(text)
                .lineLimit(Self.lines)
                .frame(maxWidth: Self.width, alignment: .leading)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { shownHeight = $0 }
                .background {
                    // The same text unbounded, measured unseen, says whether the lines hold it.
                    Text(text)
                        .frame(maxWidth: Self.width, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .hidden()
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
                }
            if fullHeight > shownHeight + 1 {
                Button("More") { isShowingAll = true }
                    .buttonStyle(.plain)
                    .fontWeight(.semibold)
                    .foregroundStyle(PageInkColors(tone: tone).accent)
                    .accessibilityHint("Shows the whole description")
            }
        }
        .font(Self.font)
        .foregroundStyle(PageInkColors(tone: tone).secondary)
        .multilineTextAlignment(.leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        #if os(iOS)
        .sheet(isPresented: $isShowingAll) {
            NavigationStack {
                ScrollView {
                    Text(text)
                        .font(.body)
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(Design.Margin.page)
                }
                .navigationTitle(title)
                .toolbarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done", systemImage: "checkmark") { isShowingAll = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
        #else
        .popover(isPresented: $isShowingAll, arrowEdge: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: Design.Spacing.small) {
                    Text(title).font(.headline)
                    Text(text)
                }
                .padding(Design.Spacing.xLarge)
                .frame(width: 380, alignment: .leading)
            }
            .frame(maxHeight: 420)
        }
        #endif
    }
}

#if os(iOS)
extension View {
    /// A song row's insets on a collection's page, as Music spaces them: its text on the
    /// header's margin, a little air above and below a cover so rows of covers don't touch,
    /// and room at the end for the row's "…". The row sits straight on the page's colour, with
    /// its separator in the page's hairline.
    func collectionRowInsets(hasCover: Bool) -> some View {
        modifier(CollectionRow(hasCover: hasCover))
    }
}

private struct CollectionRow: ViewModifier {
    let hasCover: Bool
    @Environment(\.pageTone) private var tone

    func body(content: Content) -> some View {
        let vertical: CGFloat = hasCover ? 6 : 3
        content
            .listRowInsets(EdgeInsets(top: vertical, leading: Design.Margin.page, bottom: vertical, trailing: 6))
            .listRowBackground(Color.clear)
            .listRowSeparatorTint(tone?.hairlineColor)
            .pageInk()
    }
}
#endif

extension View {
    /// The page's type colours and accent, set again where a list's row would otherwise draw
    /// in the system's own: a plain list's cells start their content from the default label
    /// colours, so the page's foreground style doesn't reach them.
    func pageInk() -> some View {
        modifier(PageInk())
    }
}

/// A page's type colours by name, for words that must read on it: the page tone's where there
/// is one, the system's own otherwise.
struct PageInkColors {
    let tone: PageTone?

    var primary: AnyShapeStyle { tone.map { AnyShapeStyle($0.primaryColor) } ?? AnyShapeStyle(.primary) }
    var secondary: AnyShapeStyle { tone.map { AnyShapeStyle($0.secondaryColor) } ?? AnyShapeStyle(.secondary) }
    var tertiary: AnyShapeStyle { tone.map { AnyShapeStyle($0.tertiaryColor) } ?? AnyShapeStyle(.tertiary) }
    var accent: AnyShapeStyle { tone.map { AnyShapeStyle($0.accentColor) } ?? AnyShapeStyle(.tint) }
}

private struct PageInk: ViewModifier {
    @Environment(\.pageTone) private var tone

    func body(content: Content) -> some View {
        content
            .foregroundStyle(
                tone.map { AnyShapeStyle($0.primaryColor) } ?? AnyShapeStyle(.primary),
                tone.map { AnyShapeStyle($0.secondaryColor) } ?? AnyShapeStyle(.secondary),
                tone.map { AnyShapeStyle($0.tertiaryColor) } ?? AnyShapeStyle(.tertiary)
            )
            .tint(tone?.accentColor)
    }
}

/// The facts under a collection's title, joined as Music joins them.
enum CollectionFacts {
    /// "Alternative · 2024 · 12 songs, 48 min". Empty parts are left out.
    static func line(_ parts: [String?]) -> Text? {
        let parts = parts.compactMap(\.self).filter { !$0.isEmpty }
        return parts.isEmpty ? nil : Text(parts.joined(separator: " · "))
    }

    /// "12 songs, 48 min", short enough for the header.
    static func length(count: Int, seconds: TimeInterval) -> String? {
        guard count > 0 else { return nil }
        let songs = String(AttributedString(localized: "^[\(count) song](inflect: true)").characters)
        guard seconds >= 60 else { return songs }
        let length = Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
        return "\(songs), \(length)"
    }

    /// "Out Friday, October 3", for an album not released yet.
    static func release(_ date: Date?, now: Date = .now) -> String? {
        guard let date, date > now else { return nil }
        return String(localized: "Out \(date.formatted(.dateTime.weekday(.wide).month(.wide).day()))")
    }
}

#if DEBUG
private struct CollectionHeaderPreview: View {
    var title = "Coastlines"
    var isLoading = false
    private let cover = CoverArt.url(nil, seed: "Coastlines")

    var body: some View {
        #if os(iOS)
        NavigationStack {
            List {
                Section { header }
                Section {
                    ForEach(1..<9) { number in
                        HStack(spacing: Design.Spacing.medium) {
                            Text(number, format: .number).foregroundStyle(.secondary).monospacedDigit()
                            Text("Track Title \(number)")
                        }
                        .collectionRowInsets(hasCover: false)
                    }
                }
            }
            .listSectionSpacing(0)
            .collectionPage(title: title, cover: cover)
        }
        #else
        ScrollView { header }
            .pageColor(from: cover)
        #endif
    }

    private var header: some View {
        CollectionHeader(
            kind: .album,
            title: title,
            subtitle: "Juniper Lane",
            onSubtitle: {},
            facts: CollectionFacts.line(["Alternative", "2024", CollectionFacts.length(count: 9, seconds: 1_920)]),
            note: "Recorded over one winter in a house by the sea.",
            tintCover: cover,
            isLoading: isLoading,
            play: {},
            shuffle: {}
        ) {
            Button("Add to Library", systemImage: "plus") {}
        } cover: { side in
            CoverImage(cover: cover, size: side)
        }
    }
}

#Preview("Album") { CollectionHeaderPreview() }
#Preview("Album, dark") { CollectionHeaderPreview().preferredColorScheme(.dark) }
#Preview("Long title") { CollectionHeaderPreview(title: "Everything I Meant to Say on the Drive Home, the Long Way") }
#Preview("Loading") { CollectionHeaderPreview(isLoading: true) }
#Preview("Accessibility size") { CollectionHeaderPreview().dynamicTypeSize(.accessibility5) }
#endif
