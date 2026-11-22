import SwiftUI
import TracksCore

struct MoodHero<Actions: View>: View {
    let mood: Mood
    let covers: [CoverArt]
    let facts: String?
    @ViewBuilder var actions: Actions

    var body: some View {
        ArtworkHero(covers: covers, color: mood.base) {
            VStack(alignment: .leading, spacing: 0) {
                Label(String(localized: "Mood"), systemImage: mood.symbol)
                    .eyebrowStyle()
                Text(mood.title)
                    .font(.pageTitle)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .padding(.top, Design.Spacing.xxSmall)
                    .accessibilityAddTraits(.isHeader)
                Text(facts ?? " ")
                    .font(.subheadline)
                    .opacity(facts == nil ? 0 : 0.75)
                    .padding(.top, Design.Spacing.xxSmall)
                    .accessibilityHidden(facts == nil)
                actions
                    .padding(.top, Design.Spacing.xLarge)
            }
            .frame(maxWidth: Self.wordsWidth, alignment: .leading)
        }
    }

    #if os(macOS)
    private static var wordsWidth: CGFloat { 560 }
    #else
    private static var wordsWidth: CGFloat { .infinity }
    #endif
}

/// Which way to steer the mood's flow: in glass over the hero, the one chosen filled with the
/// mood's colour. Words only; the three fit side by side, and scroll at the largest sizes.
struct MoodLeanChips: View {
    let mood: Mood
    @Binding var lean: MoodLean?

    var body: some View {
        ViewThatFits(in: .horizontal) {
            chips
            ScrollView(.horizontal) { chips }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
        }
        .font(.subheadline.weight(.semibold))
        .buttonBorderShape(.capsule)
        .sensoryFeedback(Design.Haptic.choice, trigger: lean)
        .animation(Design.Motion.press, value: lean)
    }

    private var chips: some View {
        GlassEffectContainer(spacing: Design.Spacing.small) {
            HStack(spacing: Design.Spacing.small) {
                ForEach(mood.leans) { option in
                    chip(option)
                }
            }
        }
    }

    @ViewBuilder
    private func chip(_ option: MoodLean) -> some View {
        if lean == option {
            Button(option.title) { lean = nil }
                .buttonStyle(.glassProminent)
                .tint(mood.base)
                .accessibilityAddTraits(.isSelected)
        } else {
            Button(option.title) { lean = option }
                .buttonStyle(.glass)
                .tint(nil)
        }
    }
}

extension Mood {
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
}
