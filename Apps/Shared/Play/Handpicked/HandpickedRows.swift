import SwiftUI
import TracksCore

/// Which list a song to pick is shown in, so the same song can be in two at once and the
/// Mac's keyboard still knows which row it's on.
enum PickSection: Hashable, CaseIterable {
    case suggestions
    case recent
    case top
    case history
    case library
    case catalog
    case yours
    case servers
    case playlist
    case picked

    var title: LocalizedStringKey {
        switch self {
        case .suggestions: "Suggestions"
        case .recent: "Recently Played"
        case .top: "Top Songs"
        case .history: "History"
        case .library: "Library"
        case .catalog: "Apple Music"
        case .yours: "Your Music"
        case .servers: "Your Servers"
        case .playlist: "Songs"
        case .picked: "Picked"
        }
    }
}

struct PickRowID: Hashable {
    let section: PickSection
    let identity: String
}

/// A song to pick: its cover, which plays its preview, its name and artist, and ⊕, or ✓
/// once picked. On the iPhone the rest of the row picks; on the Mac a click chooses the row,
/// as in Music, and a double-click, Return or ⊕ picks it.
struct PickRow: View {
    let pick: Handpick
    @Environment(HandpickedSelection.self) private var selection
    /// Counts picks made here, so the tap is felt.
    @State private var toggles = 0
    #if os(macOS)
    @State private var isHovering = false
    #endif

    var body: some View {
        let isPicked = selection.contains(pick.identity)
        let isBlocked = !isPicked && selection.isFull
        #if os(macOS)
        HStack(spacing: 10) {
            PreviewCover(pick: pick, size: 36, showsControl: isHovering)
            titles
            Spacer(minLength: 8)
            Button(action: toggle) {
                pickSymbol(isPicked: isPicked)
                    .frame(width: 24, height: 24)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(isBlocked)
            .help(isPicked ? "Remove from Station" : "Add to Station")
            .accessibilityLabel(isPicked ? "Remove from Station" : "Add to Station")
        }
        .padding(.vertical, 2)
        .opacity(isBlocked ? 0.4 : 1)
        .onHover { hovering in
            withAnimation(PlayMotion.hover) { isHovering = hovering }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(isPicked ? .isSelected : [])
        #else
        HStack(spacing: 12) {
            PreviewCover(pick: pick, size: 48, showsControl: true)
            Button(action: toggle) {
                HStack(spacing: 12) {
                    titles
                    Spacer(minLength: 8)
                    pickSymbol(isPicked: isPicked)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(isBlocked)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(isPicked ? .isSelected : [])
            .accessibilityHint(isPicked ? "Takes it out of the station" : "Adds it to the station")
        }
        .opacity(isBlocked ? 0.4 : 1)
        .sensoryFeedback(.selection, trigger: toggles)
        .contextMenu { PickMenu(pick: pick) }
        #endif
    }

    private var titles: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(pick.title)
                .foregroundStyle(.primary)
                .lineLimit(1)
            Text(pick.artistName)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private func pickSymbol(isPicked: Bool) -> some View {
        Image(systemName: isPicked ? "checkmark.circle.fill" : "plus.circle")
            .font(.title3)
            .foregroundStyle(.tint)
            .contentTransition(.symbolEffect(.replace))
            .accessibilityHidden(true)
    }

    private func toggle() {
        withAnimation(PlayMotion.row) {
            if selection.toggle(pick) { toggles += 1 }
        }
    }
}

/// Hearing a song and picking it, from a right-click on the Mac or a long press on the iPhone.
struct PickMenu: View {
    let pick: Handpick
    @Environment(HandpickedSelection.self) private var selection
    @Environment(HandpickedPreview.self) private var preview
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music

    var body: some View {
        let isPicked = selection.contains(pick.identity)
        if preview.canPreview(pick, source: model.musicSource, music: music, isDemo: model.player.isDemo) {
            let isPlaying = preview.isCurrent(pick.identity) && preview.phase != .paused
            Button(isPlaying ? "Pause Preview" : "Play Preview", systemImage: isPlaying ? "pause" : "play") {
                preview.toggle(pick, source: model.musicSource, music: music, player: model.player)
            }
        }
        Button(isPicked ? "Remove from Station" : "Add to Station", systemImage: isPicked ? "minus.circle" : "plus.circle") {
            withAnimation(PlayMotion.row) { _ = selection.toggle(pick) }
        }
        .disabled(!isPicked && selection.isFull)
    }
}

/// A song's cover that plays its preview: a play button over it, and while it plays, pause
/// and a ring for the half minute running out. Only the cover where there's nothing to hear.
struct PreviewCover: View {
    let pick: Handpick
    let size: CGFloat
    /// Whether play shows before the preview starts: always on the iPhone, on hover on the Mac.
    var showsControl: Bool
    @Environment(HandpickedPreview.self) private var preview
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music

    var body: some View {
        let canPreview = preview.canPreview(pick, source: model.musicSource, music: music, isDemo: model.player.isDemo)
        let isCurrent = preview.isCurrent(pick.identity)
        let hasFailed = preview.failed == pick.identity
        if canPreview || hasFailed {
            Button {
                preview.toggle(pick, source: model.musicSource, music: music, player: model.player)
            } label: {
                CoverImage(cover: pick.cover, size: size)
                    .overlay {
                        if isCurrent || hasFailed || showsControl {
                            PreviewControl(phase: isCurrent ? preview.phase : nil, progress: isCurrent ? preview.progress : nil, hasFailed: hasFailed, size: size, isQuiet: !isCurrent && !hasFailed)
                                .transition(.opacity)
                        }
                    }
                    .contentShape(.rect(cornerRadius: CoverImage.radius(for: size)))
            }
            .buttonStyle(.plain)
            .disabled(hasFailed)
            .accessibilityLabel(Self.label(isCurrent: isCurrent, phase: preview.phase, hasFailed: hasFailed))
            .accessibilityValue(pick.title)
            #if os(macOS)
            .help(isCurrent && preview.phase != .paused ? "Pause Preview" : "Play Preview")
            #endif
        } else {
            CoverImage(cover: pick.cover, size: size)
                .accessibilityHidden(true)
        }
    }

    private static func label(isCurrent: Bool, phase: HandpickedPreview.Phase, hasFailed: Bool) -> LocalizedStringKey {
        if hasFailed { return "Preview Unavailable" }
        guard isCurrent else { return "Play Preview" }
        switch phase {
        case .loading: return "Loading Preview"
        case .playing: return "Pause Preview"
        case .paused: return "Play Preview"
        }
    }
}

/// What lies over a cover playing its preview.
private struct PreviewControl: View {
    let phase: HandpickedPreview.Phase?
    let progress: HandpickedPreview.Progress?
    let hasFailed: Bool
    let size: CGFloat
    /// Play before anything's started: a small badge, so the cover still shows.
    let isQuiet: Bool

    var body: some View {
        let glyph = size * 0.36
        ZStack {
            if isQuiet {
                Image(systemName: "play.fill")
                    .font(.system(size: size * 0.24, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: size * 0.5, height: size * 0.5)
                    .background(.black.opacity(0.4), in: .circle)
                    .shadow(color: .black.opacity(0.2), radius: 2)
            } else {
                RoundedRectangle(cornerRadius: CoverImage.radius(for: size), style: .continuous)
                    .fill(.black.opacity(0.45))
                if let progress {
                    PreviewRing(progress: progress)
                        .padding(size * 0.12)
                }
                Group {
                    if hasFailed {
                        Image(systemName: "exclamationmark")
                    } else if phase == .loading {
                        ProgressView()
                            .controlSize(.small)
                            .tint(.white)
                    } else {
                        Image(systemName: phase == .playing ? "pause.fill" : "play.fill")
                            .contentTransition(.symbolEffect(.replace))
                    }
                }
                .font(.system(size: glyph * 0.8, weight: .bold))
                .foregroundStyle(.white)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The half minute of a preview running out, as a ring that fills.
private struct PreviewRing: View {
    let progress: HandpickedPreview.Progress
    @State private var shown = 0.0

    var body: some View {
        ZStack {
            Circle()
                .stroke(.white.opacity(0.3), lineWidth: 2)
            Circle()
                .trim(from: 0, to: shown)
                .stroke(.white, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .onChange(of: progress, initial: true) { _, progress in
            let now = Date.now
            var still = Transaction()
            still.disablesAnimations = true
            withTransaction(still) { shown = progress.fraction(at: now) }
            guard progress.runningSince != nil else { return }
            let remaining = (1 - shown) * progress.length
            withAnimation(.linear(duration: remaining)) { shown = 1 }
        }
    }
}
