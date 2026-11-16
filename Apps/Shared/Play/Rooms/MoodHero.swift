import SwiftUI
import TracksCore

struct MoodHero<Actions: View>: View {
    let mood: Mood
    let covers: [CoverArt]
    /// Nil while the history is read, so one line doesn't give way to another as the page opens.
    let line: String?
    let facts: String?
    @ViewBuilder var actions: Actions

    #if os(iOS)
    @ScaledMetric(relativeTo: .largeTitle) private var titleSize: CGFloat = 44
    #endif

    var body: some View {
        ArtworkHero(covers: covers, columns: Self.columns, fallback: mood.base, symbol: mood.symbol) {
            VStack(alignment: .leading, spacing: 0) {
                Label(String(localized: "Mood"), systemImage: mood.symbol)
                    .font(.footnote.weight(.semibold))
                    .textCase(.uppercase)
                    .opacity(0.85)
                Text(mood.title)
                    .font(.system(size: titleSize, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.top, 2)
                    .accessibilityAddTraits(.isHeader)
                Text(line ?? " ")
                    .font(.title3.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                    .opacity(line == nil ? 0 : 0.95)
                    .animation(.easeOut(duration: 0.2), value: line)
                    .accessibilityHidden(line == nil)
                Text(facts ?? " ")
                    .font(.subheadline)
                    .opacity(facts == nil ? 0 : 0.75)
                    .padding(.top, 2)
                    .accessibilityHidden(facts == nil)
                actions
                    .padding(.top, 18)
            }
            .frame(maxWidth: Self.wordsWidth, alignment: .leading)
        }
    }

    #if os(macOS)
    private var titleSize: CGFloat { 64 }
    private static var columns: Int { 6 }
    private static var wordsWidth: CGFloat { 560 }
    #else
    private static var columns: Int { 3 }
    private static var wordsWidth: CGFloat { .infinity }
    #endif
}

struct MoodLeanChips: View {
    let mood: Mood
    @Binding var lean: MoodLean?

    var body: some View {
        ScrollView(.horizontal) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    ForEach(mood.leans) { option in
                        chip(option)
                    }
                }
            }
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .font(.subheadline.weight(.semibold))
        .buttonBorderShape(.capsule)
        .sensoryFeedback(.selection, trigger: lean)
        .animation(.snappy(duration: 0.2), value: lean)
    }

    @ViewBuilder
    private func chip(_ option: MoodLean) -> some View {
        let label = Label(option.title, systemImage: option.symbol)
        if lean == option {
            Button { lean = nil } label: { label }
                .buttonStyle(.glassProminent)
                .tint(mood.base)
                .accessibilityAddTraits(.isSelected)
        } else {
            Button { lean = option } label: { label }
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

    var symbol: String {
        switch self {
        case .calmer: "tortoise"
        case .livelier: "hare"
        case .instrumental: "pianokeys"
        }
    }
}
