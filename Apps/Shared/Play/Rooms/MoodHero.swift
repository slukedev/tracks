import SwiftUI
import TracksCore

/// The top of a mood's page: no covers, since a mood is a feeling rather than a set of records,
/// but its sky, moving at the mood's own pace, its name large in its own type, when you play
/// it, and one big way to play it with the ways to steer it beneath.
struct MoodHero<Actions: View>: View {
    let mood: Mood
    /// When you play it, or what it's for when your history can't say yet. Nil while your
    /// history is read, so one line doesn't give way to another as the page opens.
    let line: String?
    /// How many of your songs suit it; nil while they're worked out or when there are none.
    let facts: String?
    @ViewBuilder var actions: Actions

    #if os(iOS)
    @ScaledMetric(relativeTo: .largeTitle) private var titleSize: CGFloat = 60
    #endif

    var body: some View {
        let style = mood.style
        VStack(alignment: .leading, spacing: 0) {
            Image(systemName: mood.symbol)
                .font(.title2.weight(.semibold))
                .symbolRenderingMode(.hierarchical)
                .opacity(0.9)
                .accessibilityHidden(true)
            Text(mood.title)
                .font(style.title.font(size: titleSize))
                .tracking(style.title.tracking)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .padding(.top, 6)
                .accessibilityAddTraits(.isHeader)
            Text(line ?? " ")
                .font(.title3.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
                .opacity(line == nil ? 0 : 1)
                .animation(.easeOut(duration: 0.2), value: line)
                .accessibilityHidden(line == nil)
            Text(facts ?? " ")
                .font(.subheadline.weight(.medium))
                .opacity(facts == nil ? 0 : 0.82)
                .padding(.top, 6)
                .accessibilityHidden(facts == nil)
            actions
                .padding(.top, Self.actionsGap)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: Self.wordsWidth, alignment: .leading)
        .padding(.horizontal, PlayMetrics.margin)
        .padding(.top, Self.top)
        .padding(.bottom, RoomField.fade)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { MoodSky(mood: mood) }
    }

    #if os(macOS)
    private var titleSize: CGFloat { 84 }
    private static var top: CGFloat { 64 }
    private static var wordsWidth: CGFloat { 620 }
    private static var actionsGap: CGFloat { 22 }
    #else
    private static var top: CGFloat { 72 }
    private static var wordsWidth: CGFloat { .infinity }
    private static var actionsGap: CGFloat { 26 }
    #endif
}

/// A mood's sky: its field, its seams drifting at the mood's own pace, slow and wide for the
/// calm ones and quick for the lively ones. Still with Reduce Motion, and while it's scrolled
/// away or the app isn't in front, so it costs nothing nobody sees.
struct MoodSky: View {
    let mood: Mood
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = true
    /// Which way the sky is swaying. A repeating animation between the two, rather than a
    /// timeline, so the page's body isn't made again on every frame.
    @State private var isOut = false

    var body: some View {
        let breath = mood.breath
        RoomField(style: mood.style, points: Self.points(phase: isOut ? .pi : 0, reach: breath.reach))
            .onScrollVisibilityChange(threshold: 0.05) { isVisible = $0 }
            .onChange(of: moves, initial: true) { _, moves in
                if moves {
                    withAnimation(.easeInOut(duration: breath.period / 2).repeatForever(autoreverses: true)) { isOut = true }
                } else {
                    withAnimation(.easeOut(duration: 0.4)) { isOut = false }
                }
            }
    }

    private var moves: Bool { !reduceMotion && isVisible && scenePhase == .active }

    /// The field's seams moved round small ellipses, each a little out of step, so the blend
    /// shifts without ever looking like it loops.
    static func points(phase: Double, reach: Float) -> [SIMD2<Float>] {
        func wave(_ offset: Double, _ speed: Double = 1) -> Float { Float(sin(phase * speed + offset)) * reach }
        var points = RoomField.points
        points[1].x += wave(0.4)
        points[3].y += wave(1.7, 0.8)
        points[4] += SIMD2(wave(2.1, 1.1), wave(0.9, 0.7))
        points[5].y += wave(3.2, 0.9)
        points[7].x += wave(4.4, 0.6)
        return points
    }
}

extension Mood {
    /// How its sky moves: seconds for one sway, and how far its seams travel.
    var breath: (period: Double, reach: Float) {
        switch self {
        case .sleep: (18, 0.07)
        case .focus: (16, 0.05)
        case .chill: (14, 0.09)
        case .heartbreak: (15, 0.08)
        case .love: (9, 0.1)
        case .drive: (7, 0.12)
        case .feelGood: (5, 0.12)
        case .party: (3.2, 0.14)
        case .energy: (2.6, 0.14)
        case .workout: (2.4, 0.13)
        }
    }
}

/// Playing a mood, on its sky: one big Play, named for the mood, Shuffle and anything else
/// beside it as round buttons.
struct MoodPlayRow<Extra: View>: View {
    let mood: Mood
    let playTitle: String
    let playSymbol: String
    let canPlay: Bool
    /// Nil hides Shuffle: there's nothing of yours to shuffle.
    let shuffle: (() -> Void)?
    let play: () -> Void
    @ViewBuilder var extra: Extra
    @ScaledMetric(relativeTo: .headline) private var side = StageButtonStyle.height
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // At the largest sizes Play takes the whole row, so the mood's name fits, and the round
        // buttons sit under it.
        if typeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 10) {
                playButton
                HStack(spacing: 10) {
                    shuffleButton
                    extra
                }
            }
        } else {
            HStack(spacing: 10) {
                playButton
                shuffleButton
                extra
            }
        }
    }

    private var playButton: some View {
        Button(action: play) {
            Label(playTitle, systemImage: playSymbol)
                .contentTransition(.symbolEffect(.replace))
                .lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                .frame(maxWidth: Self.fills ? .infinity : nil)
        }
        .buttonStyle(.stagePrimary(tint: mood.base.mix(with: .black, by: 0.25)))
        .disabled(!canPlay)
    }

    @ViewBuilder
    private var shuffleButton: some View {
        if let shuffle {
            Button(action: shuffle) {
                Image(systemName: "shuffle")
                    .font(Self.roundFont)
                    .frame(width: side, height: side)
                    .background(.white.opacity(0.2), in: .circle)
                    .contentShape(.circle)
            }
            .buttonStyle(.pressable)
            .foregroundStyle(.white)
            .accessibilityLabel("Shuffle Your Songs")
            #if os(macOS)
            .help("Shuffle Your Songs")
            #endif
        }
    }

    #if os(macOS)
    private static var fills: Bool { false }
    private static var roundFont: Font { .body.weight(.semibold) }
    #else
    private static var fills: Bool { true }
    private static var roundFont: Font { .headline }
    #endif
}

/// Which way to steer the mood, as small chips on its sky: one at a time, and tapping the one
/// chosen lets it go.
struct MoodLeanChips: View {
    let mood: Mood
    @Binding var lean: MoodLean?

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(mood.leans) { option in
                    let isChosen = lean == option
                    Button {
                        lean = isChosen ? nil : option
                    } label: {
                        Label(option.title, systemImage: option.symbol)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(isChosen ? AnyShapeStyle(mood.base.mix(with: .black, by: 0.3)) : AnyShapeStyle(.white))
                            .padding(.horizontal, 12)
                            .frame(minHeight: Self.height)
                            .background(isChosen ? AnyShapeStyle(.white) : AnyShapeStyle(.white.opacity(0.18)), in: .capsule)
                            .padding(.vertical, Self.reach)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isChosen ? .isSelected : [])
                }
            }
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: lean)
        .animation(.easeOut(duration: 0.15), value: lean)
    }

    #if os(macOS)
    private static var height: CGFloat { 26 }
    private static var reach: CGFloat { 0 }
    #else
    private static var height: CGFloat { 34 }
    private static var reach: CGFloat { 5 }
    #endif
}

extension Mood {
    /// The ways to steer it worth offering, the likeliest first: a calm mood can go calmer or
    /// lose its words; a lively one can go further or ease off.
    var leans: [MoodLean] {
        switch self {
        case .focus, .sleep: [.instrumental, .calmer, .livelier]
        case .chill, .heartbreak, .love: [.calmer, .livelier, .instrumental]
        default: [.livelier, .calmer, .instrumental]
        }
    }
}

extension MoodLean {
    var title: LocalizedStringKey {
        switch self {
        case .calmer: "Calmer"
        case .livelier: "More Upbeat"
        case .instrumental: "Less Vocal"
        }
    }

    var symbol: String {
        switch self {
        case .calmer: "tortoise"
        case .livelier: "hare"
        case .instrumental: "pianokeys"
        }
    }
}
