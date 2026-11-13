import SwiftUI

/// Material 3 Expressive's shapes, as Tracks uses them: artists are scalloped (`cookie9`), what's
/// playing turns like a record (`cookie12`), and the rest give tiles and badges some character.
/// Drawn by `Skip/MaterialExpressive.kt`.
enum ExpressiveShape: String, CaseIterable {
    case circle, square, cookie4, cookie6, cookie9, cookie12, sunny, softBurst
    case clover4, clover8, flower, pentagon, gem, pill
}

extension View {
    /// Clips to an expressive shape, turned `rotation` degrees.
    @ViewBuilder
    func expressiveClip(_ shape: ExpressiveShape, rotation: Double = 0) -> some View {
        #if os(Android)
        self.composeModifier { ExpressiveClipModifier(shape: shape.rawValue, rotation: rotation) }
        #else
        if shape == .square {
            self.clipShape(RoundedRectangle(cornerRadius: 16))
        } else {
            self.clipShape(Circle())
        }
        #endif
    }

    /// Fills behind the view with a colour role, in an expressive shape.
    func expressiveBackground(_ role: MaterialRole, _ shape: ExpressiveShape, rotation: Double = 0) -> some View {
        background {
            Color.clear
                .materialBackground(role)
                .expressiveClip(shape, rotation: rotation)
        }
    }
}

/// A page coloured from a cover, as Android 16's media controls and Google's music apps are:
/// the cover's hue runs through the surfaces and accents, and eases into the next song's.
struct CoverColored<Content: View>: View {
    let url: URL?
    @ViewBuilder let content: () -> Content
    @Environment(\.colorScheme) var colorScheme

    var body: some View {
        #if os(Android)
        content()
            .composeModifier { CoverColorsModifier(url: url?.absoluteString ?? "", dark: colorScheme == .dark) }
        #else
        content()
        #endif
    }
}

/// Now Playing's cover: a scalloped disc turning slowly while the music plays, a rounded square
/// when it's paused. Swiping it plays the next or previous song.
struct MorphingCover: View {
    let url: URL?
    let isPlaying: Bool
    let size: CGFloat
    let next: () -> Void
    let previous: () -> Void

    var body: some View {
        #if os(Android)
        ComposeView {
            MorphingCoverComposer(url: url?.absoluteString ?? "", playing: isPlaying, onNext: next, onPrevious: previous)
        }
        .frame(width: size, height: size)
        #else
        AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Color.gray }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: 28))
        #endif
    }
}

/// The mini player's cover, a scalloped record that turns while the music plays.
struct SpinningCover: View {
    let url: URL?
    let isPlaying: Bool
    var size: CGFloat = 48

    var body: some View {
        #if os(Android)
        ComposeView {
            SpinningCoverComposer(url: url?.absoluteString ?? "", playing: isPlaying)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        #else
        AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Color.gray }
            .frame(width: size, height: size)
            .clipShape(Circle())
        #endif
    }
}

/// Android 16's wavy seek bar. `interactive` false is the mini player's thin line.
struct WavySeekBar: View {
    let position: Double
    let duration: Double
    let isPlaying: Bool
    var interactive = true
    var valueLabel = ""
    var seek: (Double) -> Void = { _ in }
    /// Where a finger has dragged the handle to, and nil when it lets go.
    var scrub: (Double?) -> Void = { _ in }

    var body: some View {
        #if os(Android)
        ComposeView {
            WavySeekBarComposer(
                position: position, duration: duration, playing: isPlaying, interactive: interactive,
                valueLabel: valueLabel, onSeek: seek, onScrub: { seconds in scrub(seconds < 0 ? nil : seconds) }
            )
        }
        .frame(maxWidth: .infinity)
        .frame(height: interactive ? 40 : 10)
        #else
        ProgressView(value: duration > 0 ? min(position / duration, 1) : 0)
        #endif
    }
}

/// Material 3 Expressive's loading indicator: a shape that turns and morphs into the next.
struct MorphingLoadingIndicator: View {
    var body: some View {
        #if os(Android)
        ComposeView { MorphingLoadingComposer() }
            .frame(width: 48, height: 48)
        #else
        ProgressView()
        #endif
    }
}

/// A Material 3 Expressive button: a pill that squares off while it's pressed.
struct ExpressiveButton: View {
    enum Emphasis: String { case filled, tonal, tertiary, surface }

    let title: String
    var symbol: Symbol?
    var emphasis: Emphasis = .filled
    /// 40 is Material's small button, 56 its medium.
    var height: CGFloat = 56
    var isEnabled = true
    let action: () -> Void

    var body: some View {
        #if os(Android)
        ComposeView {
            ExpressiveButtonComposer(
                label: title, icon: symbol?.materialName ?? "", emphasis: emphasis.rawValue,
                height: Int(height), enabled: isEnabled, onClick: action
            )
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        #else
        Button(action: action) { Label(title, systemImage: symbol?.sfSymbol ?? "circle").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent)
            .disabled(!isEnabled)
        #endif
    }
}

/// Play and pause for Now Playing: round while paused, a wider softened square while playing.
struct PlayPauseButton: View {
    let isPlaying: Bool
    let action: () -> Void

    var body: some View {
        #if os(Android)
        ComposeView { PlayPauseComposer(playing: isPlaying, onClick: action) }
            .frame(width: 112, height: 88)
        #else
        Button(isPlaying ? "Pause" : "Play", action: action)
        #endif
    }
}

/// Covers as Material's hero carousel: one large, the next smaller, and a glimpse of the rest.
struct CoverCarousel: View {
    struct Item {
        let url: URL?
        let title: String
        let subtitle: String
    }

    let items: [Item]
    let open: (Int) -> Void

    var body: some View {
        #if os(Android)
        ComposeView { CoverCarouselComposer(json: json, onOpen: open) }
            .frame(maxWidth: .infinity)
            .frame(height: 228)
        #else
        EmptyView()
        #endif
    }

    private var json: String {
        let entries: [[String: String]] = items.map {
            ["url": $0.url?.absoluteString ?? "", "title": $0.title, "subtitle": $0.subtitle]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: entries) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }
}

/// Bouncing bars in place of a track's number while it's the one playing.
struct PlayingBars: View {
    let isPlaying: Bool

    var body: some View {
        #if os(Android)
        ComposeView { PlayingBarsComposer(playing: isPlaying) }
            .frame(width: 20, height: 20)
        #else
        Image(systemName: "waveform")
        #endif
    }
}

#if SKIP
import androidx.compose.runtime.Composable

struct ExpressiveClipModifier : ContentModifier {
    let shape: String
    let rotation: Double

    func modify(view: any View) -> any View {
        view.composeModifier { $0.expressiveClip(shape, rotation) }
    }
}

struct CoverColorsModifier : ContentModifier {
    let url: String
    let dark: Bool

    func modify(view: any View) -> any View {
        view
            .colorScheme(dark ? ColorScheme.dark : ColorScheme.light)
            .material3ColorScheme { base, isDark in rememberCoverScheme(url, base, isDark) }
    }
}

struct MorphingCoverComposer : ContentComposer {
    let url: String
    let playing: Bool
    let onNext: () -> Void
    let onPrevious: () -> Void

    init(url: String, playing: Bool, onNext: @escaping () -> Void, onPrevious: @escaping () -> Void) {
        self.url = url
        self.playing = playing
        self.onNext = onNext
        self.onPrevious = onPrevious
    }

    @Composable func Compose(context: ComposeContext) {
        ExpressiveMorphingCover(url, playing, onNext, onPrevious, context.modifier)
    }
}

struct SpinningCoverComposer : ContentComposer {
    let url: String
    let playing: Bool

    @Composable func Compose(context: ComposeContext) {
        ExpressiveSpinningCover(url, playing, context.modifier)
    }
}

struct WavySeekBarComposer : ContentComposer {
    let position: Double
    let duration: Double
    let playing: Bool
    let interactive: Bool
    let valueLabel: String
    let onSeek: (Double) -> Void
    let onScrub: (Double) -> Void

    init(position: Double, duration: Double, playing: Bool, interactive: Bool, valueLabel: String, onSeek: @escaping (Double) -> Void, onScrub: @escaping (Double) -> Void) {
        self.position = position
        self.duration = duration
        self.playing = playing
        self.interactive = interactive
        self.valueLabel = valueLabel
        self.onSeek = onSeek
        self.onScrub = onScrub
    }

    @Composable func Compose(context: ComposeContext) {
        ExpressiveWavySeekBar(position, duration, playing, interactive, valueLabel, onSeek, onScrub, context.modifier)
    }
}

struct MorphingLoadingComposer : ContentComposer {
    @Composable func Compose(context: ComposeContext) {
        ExpressiveLoadingIndicator(context.modifier)
    }
}

struct ExpressiveButtonComposer : ContentComposer {
    let label: String
    let icon: String
    let emphasis: String
    let height: Int
    let enabled: Bool
    let onClick: () -> Void

    init(label: String, icon: String, emphasis: String, height: Int, enabled: Bool, onClick: @escaping () -> Void) {
        self.label = label
        self.icon = icon
        self.emphasis = emphasis
        self.height = height
        self.enabled = enabled
        self.onClick = onClick
    }

    @Composable func Compose(context: ComposeContext) {
        ExpressivePillButton(label, icon, emphasis, height, enabled, onClick, context.modifier)
    }
}

struct PlayPauseComposer : ContentComposer {
    let playing: Bool
    let onClick: () -> Void

    init(playing: Bool, onClick: @escaping () -> Void) {
        self.playing = playing
        self.onClick = onClick
    }

    @Composable func Compose(context: ComposeContext) {
        ExpressivePlayPause(playing, onClick, context.modifier)
    }
}

struct CoverCarouselComposer : ContentComposer {
    let json: String
    let onOpen: (Int) -> Void

    init(json: String, onOpen: @escaping (Int) -> Void) {
        self.json = json
        self.onOpen = onOpen
    }

    @Composable func Compose(context: ComposeContext) {
        ExpressiveCarousel(json, onOpen, context.modifier)
    }
}

struct PlayingBarsComposer : ContentComposer {
    let playing: Bool

    @Composable func Compose(context: ComposeContext) {
        ExpressivePlayingBars(playing, context.modifier)
    }
}
#endif
