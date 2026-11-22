import SwiftUI
import TracksCore

/// A page's primary action: an opaque capsule or circle, never glass, the one high-contrast
/// thing on the page. On a page coloured from a cover it's the page's type colour with the
/// page's colour for its label; on a plain page it's black on light and white on dark; on a
/// field of colour that isn't a page (a mood's hero) pass `tint` for white with that label.
struct PlayButton: View {
    enum Shape { case capsule, circle }

    let title: Text
    var systemImage = "play.fill"
    var shape: Shape = .capsule
    /// The label's colour on a field that isn't a page coloured from a cover. White fills it.
    var tint: Color?
    /// Stretches the capsule across the width it's given, as at accessibility sizes.
    var fillsWidth = false
    let action: () -> Void

    init(
        _ title: LocalizedStringKey = "Play",
        systemImage: String = "play.fill",
        shape: Shape = .capsule,
        tint: Color? = nil,
        fillsWidth: Bool = false,
        action: @escaping () -> Void
    ) {
        self.init(title: Text(title), systemImage: systemImage, shape: shape, tint: tint, fillsWidth: fillsWidth, action: action)
    }

    /// A title that's already localized, such as a mix's "Play Chill Mix".
    init(
        verbatim title: String,
        systemImage: String = "play.fill",
        shape: Shape = .capsule,
        tint: Color? = nil,
        fillsWidth: Bool = false,
        action: @escaping () -> Void
    ) {
        self.init(title: Text(verbatim: title), systemImage: systemImage, shape: shape, tint: tint, fillsWidth: fillsWidth, action: action)
    }

    private init(title: Text, systemImage: String, shape: Shape, tint: Color?, fillsWidth: Bool, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.shape = shape
        self.tint = tint
        self.fillsWidth = fillsWidth
        self.action = action
    }

    @State private var presses = 0
    @ScaledMetric(relativeTo: .headline) private var height = Design.Control.playHeight
    @ScaledMetric(relativeTo: .headline) private var circle = Design.Control.playCircle

    var body: some View {
        Button {
            presses += 1
            action()
        } label: {
            Label {
                title
            } icon: {
                Image(systemName: systemImage)
            }
        }
        .buttonStyle(PlayButtonStyle(shape: shape, tint: tint, fillsWidth: fillsWidth, height: height, diameter: min(circle, Design.Control.playCircle * 1.4)))
        .sensoryFeedback(Design.Haptic.start, trigger: presses)
        .help(title)
    }
}

private struct PlayButtonStyle: ButtonStyle {
    let shape: PlayButton.Shape
    let tint: Color?
    let fillsWidth: Bool
    let height: CGFloat
    let diameter: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        PlayButtonBody(configuration: configuration, shape: shape, tint: tint, fillsWidth: fillsWidth, height: height, diameter: diameter)
    }
}

private struct PlayButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let shape: PlayButton.Shape
    let tint: Color?
    let fillsWidth: Bool
    let height: CGFloat
    let diameter: CGFloat

    @Environment(\.pageTone) private var tone
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        let pressed = configuration.isPressed
        Group {
            switch shape {
            case .capsule:
                configuration.label
                    .foregroundStyle(labelStyle)
                    .labelStyle(.titleAndIcon)
                    .font(Self.font)
                    .lineLimit(1)
                    .padding(.horizontal, Self.horizontalPadding)
                    .frame(minWidth: Design.Control.playMinimumWidth, maxWidth: fillsWidth ? .infinity : nil, minHeight: height)
                    .background(fill, in: .capsule)
                    .contentShape(.capsule)
            case .circle:
                configuration.label
                    .foregroundStyle(labelStyle)
                    .labelStyle(.iconOnly)
                    .font(.system(size: diameter * 0.4, weight: .bold))
                    // The play triangle's weight sits left of its box; nudged, it looks centred.
                    .offset(x: diameter * 0.03)
                    .frame(width: diameter, height: diameter)
                    .background(fill, in: .circle)
                    .contentShape(.circle)
            }
        }
        .brightness(isHovering && isEnabled && !pressed ? 0.06 : 0)
        .scaleEffect(pressed && !reduceMotion ? 0.96 : 1)
        .opacity(pressed ? 0.88 : 1)
        .animation(Design.Motion.press, value: pressed)
        .onHover { hovering in
            withAnimation(Design.Motion.hover) { isHovering = hovering }
        }
    }

    private var fill: Color {
        if let tone { return tone.primaryColor }
        if tint != nil { return .white }
        return .primary
    }

    /// Opaque even while it waits: only the symbol and word dim, so it never reads as glass.
    /// Set on the label alone, since the fill's `.primary` follows the foreground style.
    private var labelStyle: Color {
        label.opacity(isEnabled ? 1 : 0.4)
    }

    private var label: Color {
        if let tone { return tone.backgroundColor }
        if let tint { return tint }
        return Color.pageBackground
    }

    #if os(macOS)
    private static let font = Font.body.weight(.semibold)
    private static let horizontalPadding: CGFloat = 16
    #else
    private static let font = Font.headline
    private static let horizontalPadding: CGFloat = 24
    #endif
}

/// A secondary action beside Play, or a control floating over art: a symbol in a circle of
/// glass, 44 points on iPhone and 32 on the Mac. Glass over a page coloured from a cover picks
/// the colour up by itself, so it's untinted unless there's a reason.
struct GlassCircleButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    /// Draws the symbol's filled variant, for a toggle that's on.
    var isOn = false
    var tint: Color?
    let action: () -> Void

    @State private var presses = 0

    var body: some View {
        Button {
            presses += 1
            action()
        } label: {
            Label(title, systemImage: systemImage)
                .labelStyle(.iconOnly)
                .symbolVariant(isOn ? .fill : .none)
                .contentTransition(.symbolEffect(.replace))
                .glassCircle(tint: tint)
        }
        .buttonStyle(GlassCirclePressStyle())
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .help(Text(title))
        .sensoryFeedback(Design.Haptic.secondary, trigger: presses)
    }
}

/// A menu behind a glass circle, as More is beside Play.
struct GlassCircleMenu<Items: View>: View {
    let title: LocalizedStringKey
    var systemImage = "ellipsis"
    var tint: Color?
    @ViewBuilder var items: Items

    var body: some View {
        Menu {
            items
        } label: {
            Label(title, systemImage: systemImage)
                .labelStyle(.iconOnly)
                .glassCircle(tint: tint)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(GlassCirclePressStyle())
        .fixedSize()
        .help(Text(title))
    }
}

extension View {
    /// A symbol in a circle of interactive glass, sized for a secondary action. For a label
    /// that isn't a plain button's, such as a download control's.
    func glassCircle(diameter: CGFloat = Design.Control.glassCircle, tint: Color? = nil) -> some View {
        modifier(GlassCircle(diameter: diameter, tint: tint))
    }
}

private struct GlassCircle: ViewModifier {
    let diameter: CGFloat
    let tint: Color?
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1
    @Environment(\.isEnabled) private var isEnabled

    func body(content: Content) -> some View {
        let side = diameter * min(scale, 1.35)
        content
            // Sized by the circle, which stops growing at 1.35 times, so the symbol never
            // spills out of it at the largest text sizes.
            .font(.system(size: side * 0.39, weight: .semibold))
            .imageScale(.medium)
            .foregroundStyle(.primary)
            .opacity(isEnabled ? 1 : 0.4)
            .frame(width: side, height: side)
            .contentShape(.circle)
            .glassEffect(.regular.tint(tint).interactive(), in: .circle)
    }
}

/// The press for a glass circle. Interactive glass answers a touch on iPhone by itself; the
/// Mac gets a small dip, since glass doesn't answer a click there.
private struct GlassCirclePressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            #if os(macOS)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.8 : 1)
            .animation(Design.Motion.press, value: configuration.isPressed)
            #endif
    }
}

/// Play over a cover on a tile: a small circle of glass in the cover's corner, in place of a
/// black veil. A spinner while the music starts.
struct CoverPlayButton: View {
    var title: LocalizedStringKey = "Play"
    var systemImage = "play.fill"
    var isStarting = false
    let action: () -> Void

    @State private var presses = 0

    var body: some View {
        Button {
            presses += 1
            action()
        } label: {
            ZStack {
                if isStarting {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityLabel("Starting")
                } else {
                    Image(systemName: systemImage)
                        .font(.system(size: Design.Control.coverButton * 0.4, weight: .bold))
                        .offset(x: systemImage == "play.fill" ? 1 : 0)
                }
            }
            .foregroundStyle(.primary)
            .frame(width: Design.Control.coverButton, height: Design.Control.coverButton)
            .glassEffect(.regular.interactive(), in: .circle)
            .background {
                // A finger's target, wider than the circle it draws, without moving it.
                Color.clear
                    .frame(width: Design.Control.minimumTarget, height: Design.Control.minimumTarget)
                    .contentShape(.circle)
            }
        }
        .buttonStyle(GlassCirclePressStyle())
        .accessibilityLabel(Text(title))
        .help(Text(title))
        .sensoryFeedback(Design.Haptic.start, trigger: presses)
    }
}

extension View {
    /// Play in a corner of a cover. On the Mac pass whether the pointer is over the tile, as
    /// Music shows it only then; on iPhone it's always there.
    func coverPlayButton(
        isVisible: Bool = true,
        isStarting: Bool = false,
        alignment: Alignment = .bottomTrailing,
        action: @escaping () -> Void
    ) -> some View {
        overlay(alignment: alignment) {
            if isVisible || isStarting {
                CoverPlayButton(isStarting: isStarting, action: action)
                    .padding(Design.Spacing.small)
                    .transition(.opacity)
            }
        }
    }
}

#if DEBUG
private struct ControlsPreview: View {
    let sample: CoverTint.Sample?
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: Design.Spacing.xxLarge) {
                GlassEffectContainer(spacing: Design.Control.spacing) {
                    HStack(spacing: Design.Control.spacing) {
                        GlassCircleButton(title: "Shuffle", systemImage: "shuffle") {}
                        PlayButton {}
                        GlassCircleMenu(title: "More") {
                            Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {}
                        }
                    }
                }
                PlayButton(shape: .circle) {}
                PlayButton("Play Everything You Played This Summer") {}
                PlayButton {}.disabled(true)
                HStack(spacing: Design.Spacing.large) {
                    ForEach([Color.yellow, .indigo, .black, .white], id: \.self) { colour in
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(colour.gradient)
                            .frame(width: 120, height: 120)
                            .coverPlayButton {}
                    }
                }
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.teal.gradient)
                    .frame(width: 120, height: 120)
                    .coverPlayButton(isStarting: true) {}
                PlayButton(tint: .indigo) {}
                    .padding()
                    .background(.indigo)
            }
            .padding(Design.Margin.page)
            .frame(maxWidth: .infinity)
        }
        .pageColor(PageTone.page(for: sample, colorScheme: colorScheme, contrast: .standard))
    }
}

#Preview("Plain page") { ControlsPreview(sample: nil) }
#Preview("Plain page, dark") { ControlsPreview(sample: nil).preferredColorScheme(.dark) }
#Preview("Bright cover") { ControlsPreview(sample: .rgb(1, 0.85, 0.3)) }
#Preview("Dark cover") { ControlsPreview(sample: .rgb(0.1, 0.12, 0.3)) }
#Preview("Accessibility size") { ControlsPreview(sample: .rgb(0.2, 0.4, 0.7)).dynamicTypeSize(.accessibility5) }
#endif
