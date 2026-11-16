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
    /// The cover Play takes its colour from.
    let tintCover: CoverArt?
    var canPlay = true
    var download: DownloadSubject?
    /// Still loading: the cover is a placeholder, the words wait, and Play waits too.
    var isLoading = false
    let play: () -> Void
    let shuffle: () -> Void
    /// The page's More menu, beside Play on the Mac. iPhone keeps it in the toolbar.
    @ViewBuilder var more: More
    /// The cover at the side it's given.
    @ViewBuilder var cover: (CGFloat) -> Cover

    @State private var tint: Color?
    #if os(iOS)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #endif

    var body: some View {
        layout
            .coverTint(of: tintCover, into: $tint)
    }

    /// The field's colour: the one read from the cover, or straight away the colour Apple Music
    /// gives or a stand-in cover's, so the field doesn't start grey.
    private var fieldTint: Color? {
        tint ?? tintCover.flatMap(CollectionHeaderTint.immediate)
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
        .shadow(color: .black.opacity(0.35), radius: coverSide > 150 ? 24 : 12, y: coverSide > 150 ? 12 : 6)
    }

    #if os(iOS)
    private var coverSide: CGFloat { 220 }

    private var layout: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: 0) {
                    artwork
                    words(onArt: false)
                        .padding(.top, 18)
                }
            } else {
                artwork
                    .overlay(alignment: .bottom) {
                        words(onArt: true)
                            .padding(.top, 72)
                            .background(alignment: .bottom) { ArtworkScrim() }
                    }
            }
        }
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity)
        .listRowInsets(EdgeInsets())
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
    }

    // Drawn a little larger than the square so the cover's rounded corners fall outside it;
    // the blurred mirror above fills a pull past the top.
    private var artwork: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                GeometryReader { proxy in
                    let side = proxy.size.width
                    ZStack(alignment: .top) {
                        art(side)
                            .scaleEffect(x: 1, y: -1)
                            .blur(radius: 24)
                            .frame(width: side, height: side)
                            .clipped()
                            .offset(y: -side)
                        art(side)
                            .frame(width: side, height: side)
                            .clipped()
                    }
                }
            }
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private func art(_ side: CGFloat) -> some View {
        if isLoading {
            Color.placeholderFill
        } else {
            cover(side + CoverImage.maximumRadius * 3)
        }
    }

    private func words(onArt: Bool) -> some View {
        VStack(spacing: 0) {
            if isLoading {
                placeholderText(width: 180, height: 22)
                placeholderText(width: 120, height: 18)
                    .padding(.top, 6)
            } else {
                Text(title)
                    .font(.title2.bold())
                    .lineLimit(3)
                subtitleView(onArt: onArt)
                    .padding(.top, 2)
                if let facts {
                    facts
                        .font(.footnote)
                        .foregroundStyle(onArt ? AnyShapeStyle(.white.opacity(0.75)) : AnyShapeStyle(.secondary))
                        .padding(.top, 4)
                }
                if let format {
                    FormatBadge(format: format, onDark: onArt, showsDetail: true)
                        .padding(.top, 6)
                }
                if let note, !note.isEmpty {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(onArt ? AnyShapeStyle(.white.opacity(0.75)) : AnyShapeStyle(.secondary))
                        .lineLimit(3)
                        .padding(.top, 6)
                }
            }
            playRow(onArt: onArt)
                .padding(.top, 16)
        }
        .foregroundStyle(onArt ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, PlayMetrics.margin)
        .padding(.bottom, 18)
    }

    private func playRow(onArt: Bool) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        return GlassEffectContainer(spacing: 12) {
            layout {
                Button(action: play) {
                    Label("Play", systemImage: "play.fill")
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .tint((fieldTint ?? .accentColor).mix(with: .black, by: 0.3))
                Button(action: shuffle) {
                    Label("Shuffle", systemImage: "shuffle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glass)
            }
        }
        .font(.headline)
        .controlSize(.large)
        .buttonBorderShape(.capsule)
        .lineLimit(1)
        .disabled(!canPlay || isLoading)
    }

    @ViewBuilder
    private func subtitleView(onArt: Bool) -> some View {
        if let subtitle, !subtitle.isEmpty {
            let style = onArt ? AnyShapeStyle(.white.opacity(0.85)) : AnyShapeStyle(.tint)
            if let onSubtitle {
                Button(action: onSubtitle) {
                    Text(subtitle)
                        .font(.title3)
                        .foregroundStyle(style)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Opens the artist")
            } else {
                Text(subtitle)
                    .font(.title3)
                    .foregroundStyle(style)
            }
        }
    }
    #else
    private var coverSide: CGFloat { 232 }
    private var equalWidths: Bool { false }

    /// Music's header on the Mac: the cover, and beside it what the collection is, who made
    /// it and Play. It scrolls away with the songs, on a glow of the cover's own colour that
    /// fades into the window rather than a block of it.
    private var layout: some View {
        HStack(alignment: .bottom, spacing: 30) {
            coverView
            VStack(alignment: .leading, spacing: 0) {
                if isLoading {
                    placeholderText(width: 60, height: 10)
                    placeholderText(width: 260, height: 28)
                        .padding(.top, 8)
                    placeholderText(width: 140, height: 16)
                        .padding(.top, 8)
                } else {
                    Text(kind.eyebrow.uppercased())
                        .font(.caption.weight(.semibold))
                        .kerning(0.6)
                        .foregroundStyle(.secondary)
                    Text(title)
                        .font(.system(size: 34, weight: .bold))
                        .lineLimit(2)
                        .help(title)
                        .padding(.top, 4)
                    if let subtitle, !subtitle.isEmpty {
                        CollectionSubtitleLink(title: subtitle, open: onSubtitle)
                            .padding(.top, 3)
                    }
                    if let facts {
                        facts
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .padding(.top, 10)
                    }
                    if let format {
                        FormatBadge(format: format, onDark: false, showsDetail: true)
                            .padding(.top, 8)
                    }
                    if let note, !note.isEmpty {
                        CollectionNote(text: note, title: title)
                            .padding(.top, 10)
                    }
                }
                ViewThatFits(in: .horizontal) {
                    buttonRow(iconsOnly: false)
                    buttonRow(iconsOnly: true)
                }
                .padding(.top, 18)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, PlayMetrics.margin)
        .padding(.top, 24)
        .padding(.bottom, 28)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Play in the cover's colour, Shuffle and More beside it, with their words or, where
    /// they don't fit, their symbols alone.
    private func buttonRow(iconsOnly: Bool) -> some View {
        HStack(spacing: 10) {
            Button(action: play) {
                stageLabel("Play", systemImage: "play.fill", iconOnly: iconsOnly)
                    .frame(minWidth: iconsOnly ? nil : 74)
            }
            .buttonStyle(.glassProminent)
            .tint(fieldTint ?? .accentColor)
            .help("Play")
            Button(action: shuffle) {
                stageLabel("Shuffle", systemImage: "shuffle", iconOnly: iconsOnly)
                    .frame(minWidth: iconsOnly ? nil : 74)
            }
            .buttonStyle(.glass)
            .help("Shuffle")
            if let download {
                DownloadControl(subject: download)
                    .font(.body.weight(.semibold))
                    .menuStyle(.button)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .fixedSize()
            }
            Menu {
                more
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .frame(width: 28, height: 28)
                    .contentShape(.circle)
                    .accessibilityLabel("More")
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.plain)
            .fixedSize()
            .help("More")
        }
        .buttonBorderShape(.capsule)
        .controlSize(.large)
        .lineLimit(1)
        .disabled(!canPlay || isLoading)
    }
    #endif

    @ViewBuilder
    private func stageLabel(_ title: LocalizedStringKey, systemImage: String, iconOnly: Bool) -> some View {
        if iconOnly {
            Image(systemName: systemImage)
                .accessibilityLabel(Text(title))
        } else {
            Label(title, systemImage: systemImage)
        }
    }

    private func placeholderText(width: CGFloat, height: CGFloat) -> some View {
        Capsule()
            .fill(.white.opacity(0.16))
            .frame(width: width, height: height)
            .padding(.vertical, 2)
            .accessibilityHidden(true)
    }
}

/// The colour a field can take before the cover's own is read.
enum CollectionHeaderTint {
    static func immediate(_ cover: CoverArt) -> Color? {
        switch cover {
        case .artwork(let artwork):
            artwork.backgroundColor.flatMap(CoverTint.color(from:))
        case .url(let url, let seed):
            url == nil ? CoverTint.color(forSeed: seed) : nil
        }
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
                withAnimation(PlayMotion.hover) { isHovering = hovering }
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

/// A collection's description: two lines, and More for the rest in a popover.
private struct CollectionNote: View {
    let text: String
    let title: String
    @State private var isShowingAll = false
    @State private var isTruncated = false

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 6) {
            Text(text)
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .frame(maxWidth: 560, alignment: .leading)
                .background {
                    // Whether two lines hold it: the same text unbounded, measured unseen.
                    Text(text)
                        .font(.callout)
                        .frame(maxWidth: 560, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .hidden()
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { full in
                            isTruncated = full > 44
                        }
                }
            if isTruncated {
                Button("More") { isShowingAll = true }
                    .buttonStyle(.plain)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.tint)
                    .popover(isPresented: $isShowingAll, arrowEdge: .bottom) {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(title).font(.headline)
                                Text(text)
                            }
                            .padding(20)
                            .frame(width: 380, alignment: .leading)
                        }
                        .frame(maxHeight: 420)
                    }
            }
        }
    }
}

/// How big a Mac collection header is: full, or compact on a window too short to show the
/// songs under a full one. Chosen by the page from the height it's given, never measured
/// from the header itself.
enum CollectionHeaderTier {
    case regular, compact

    /// The header's height at each tier, from its fixed parts: the cover and the padding.
    var height: CGFloat {
        switch self {
        case .regular: 220 + 32 * 2
        case .compact: 120 + 20 * 2
        }
    }
}

extension EnvironmentValues {
    @Entry var collectionHeaderTier: CollectionHeaderTier = .regular
}
#endif

#if os(iOS)
extension View {
    /// A song row's insets on a collection's page, as Music spaces them: its text on the
    /// header's margin, a little air above and below a cover so rows of covers don't touch,
    /// and room at the end for the row's "…".
    func collectionRowInsets(hasCover: Bool) -> some View {
        let vertical: CGFloat = hasCover ? 6 : 3
        return listRowInsets(EdgeInsets(top: vertical, leading: PlayMetrics.margin, bottom: vertical, trailing: 6))
    }
}
#endif

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
