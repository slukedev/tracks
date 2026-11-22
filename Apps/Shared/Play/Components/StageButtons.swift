import SwiftUI

/// Buttons for a field of a cover's colour: the main action an opaque white capsule with the
/// field's colour for its label, the rest capsules of glass with white labels, as Music's are
/// over a cover. Elsewhere use ``PlayButton`` and ``GlassCircleButton``.
struct StageButtonStyle: ButtonStyle {
    enum Role { case primary, secondary }

    let role: Role
    /// The field's colour, for the primary's label. Nil draws it in near black.
    var tint: Color?
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(font)
            .labelStyle(.titleAndIcon)
            .foregroundStyle(role == .primary ? AnyShapeStyle(tint ?? Color(white: 0.1)) : AnyShapeStyle(.white))
            .padding(.horizontal, Self.horizontalPadding)
            .frame(minHeight: Self.height)
            .background {
                if role == .primary {
                    Capsule().fill(.white)
                }
            }
            .glassEffect(role == .primary ? .identity : .regular.interactive(), in: .capsule)
            .environment(\.colorScheme, .dark)
            .contentShape(.capsule)
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(Design.Motion.press, value: configuration.isPressed)
    }

    private var font: Font {
        #if os(macOS)
        .body.weight(.semibold)
        #else
        .headline
        #endif
    }

    #if os(macOS)
    static let height: CGFloat = 34
    static let horizontalPadding: CGFloat = 18
    #else
    static let height: CGFloat = 50
    static let horizontalPadding: CGFloat = 20
    #endif
}

extension ButtonStyle where Self == StageButtonStyle {
    /// The main action on a cover's field.
    static func stagePrimary(tint: Color?) -> StageButtonStyle { StageButtonStyle(role: .primary, tint: tint) }
    /// A second action on a cover's field.
    static var stageSecondary: StageButtonStyle { StageButtonStyle(role: .secondary) }
}

#if DEBUG
#Preview("On a cover's field") {
    VStack(spacing: Design.Spacing.medium) {
        HStack(spacing: Design.Control.spacing) {
            Button("Play", systemImage: "play.fill") {}
                .buttonStyle(.stagePrimary(tint: CoverStage.fallback))
            Button("Shuffle", systemImage: "shuffle") {}
                .buttonStyle(.stageSecondary)
        }
        Button("Play Everything From This Artist", systemImage: "play.fill") {}
            .buttonStyle(.stagePrimary(tint: .indigo))
        Button("Unavailable", systemImage: "nosign") {}
            .buttonStyle(.stageSecondary)
            .disabled(true)
    }
    .padding(Design.Margin.page)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background { CoverStage(tint: .indigo) }
}
#endif
