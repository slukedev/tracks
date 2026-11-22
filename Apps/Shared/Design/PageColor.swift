import SwiftUI
import TracksCore

/// A page that wears its cover's colour: the background a shade of the cover, the type,
/// separators, tint and system controls on it set to read there. Under Increase Contrast, or
/// with no cover, the page stays the system's own.
extension View {
    /// Colours the page from `cover`, as Music's album and artist pages are. Apply it to the
    /// page's scroll view or list, outside anything that draws its own background.
    func pageColor(from cover: CoverArt?) -> some View {
        modifier(CoverPageColor(cover: cover))
    }

    /// Colours the page with a tone already worked out, or leaves it as it is for nil.
    func pageColor(_ tone: PageTone?) -> some View {
        modifier(PageColorModifier(tone: tone))
    }
}

extension EnvironmentValues {
    /// The colour of the page a view is on, when the page takes one from a cover. Play and the
    /// glass buttons beside it read it to match.
    @Entry var pageTone: PageTone?
}

extension PageTone {
    /// The tone for a cover's sample in the current appearance, or nil where the page should
    /// stay the system's own: under Increase Contrast, or for a cover with no colour.
    static func page(for sample: CoverTint.Sample?, colorScheme: ColorScheme, contrast: ColorSchemeContrast) -> PageTone? {
        guard let sample, contrast == .standard else { return nil }
        return PageTone(cover: sample.colour, coverage: sample.coverage, appearance: colorScheme == .dark ? .dark : .light)
    }

    var backgroundColor: Color { background.color }
    var primaryColor: Color { primary.color }
    var secondaryColor: Color { secondary.color }
    var tertiaryColor: Color { tertiary.color }
    var hairlineColor: Color { hairline.color }
    var accentColor: Color { accent.color }
    var colorScheme: ColorScheme { isDark ? .dark : .light }
}

extension PageTone.Colour {
    var color: Color {
        Color(.sRGB, red: red, green: green, blue: blue)
    }
}

private struct CoverPageColor: ViewModifier {
    let cover: CoverArt?
    @State private var sample: CoverTint.Sample?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var contrast

    func body(content: Content) -> some View {
        let known = sample ?? cover.flatMap(CoverTint.knownSample(for:))
        content
            .pageColor(PageTone.page(for: known, colorScheme: colorScheme, contrast: contrast))
            .task(id: cover) {
                guard let cover else {
                    sample = nil
                    return
                }
                let found = await CoverTint.sample(for: cover)
                guard !Task.isCancelled else { return }
                sample = found
            }
    }
}

/// Never branches on the tone, so a page whose colour arrives late keeps its scroll position
/// and its rows: only colours change.
private struct PageColorModifier: ViewModifier {
    let tone: PageTone?
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .foregroundStyle(
                tone.map { AnyShapeStyle($0.primaryColor) } ?? AnyShapeStyle(.primary),
                tone.map { AnyShapeStyle($0.secondaryColor) } ?? AnyShapeStyle(.secondary),
                tone.map { AnyShapeStyle($0.tertiaryColor) } ?? AnyShapeStyle(.tertiary)
            )
            .tint(tone?.accentColor)
            .environment(\.pageTone, tone)
            .environment(\.colorScheme, tone?.colorScheme ?? colorScheme)
            .scrollContentBackground(tone == nil ? .automatic : .hidden)
            #if os(iOS)
            .toolbarColorScheme(tone?.colorScheme, for: .navigationBar)
            #endif
            .background {
                (tone?.backgroundColor ?? .clear)
                    .animation(Design.Motion.tint, value: tone)
                    .ignoresSafeArea()
            }
    }
}

#if DEBUG
private struct PageColorPreview: View {
    let sample: CoverTint.Sample?
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        List {
            Section {
                VStack(spacing: 6) {
                    Text("Late Night Drives").font(.collectionTitle)
                    Text("Juniper Lane").font(.title3).foregroundStyle(.tint)
                    Text("Alternative · 2024 · 12 songs, 48 min").font(.footnote).foregroundStyle(.secondary)
                    PlayButton {}
                        .padding(.top, 8)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            ForEach(1..<6) { number in
                HStack(spacing: 12) {
                    Text(number, format: .number).foregroundStyle(.secondary).monospacedDigit()
                    Text("Song number \(number)")
                    Spacer()
                    Text("12 plays").font(.subheadline).foregroundStyle(.tertiary)
                }
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .pageColor(PageTone.page(for: sample, colorScheme: colorScheme, contrast: .standard))
    }
}

extension CoverTint.Sample {
    /// An opaque cover of one colour, for previews.
    static func rgb(_ red: Double, _ green: Double, _ blue: Double) -> Self {
        Self(colour: PageTone.Colour(red: red, green: green, blue: blue), coverage: 1)
    }
}

#Preview("Blue cover") { PageColorPreview(sample: .rgb(0.2, 0.4, 0.7)) }
#Preview("Bright cover") { PageColorPreview(sample: .rgb(1, 0.9, 0.2)) }
#Preview("Dark cover") { PageColorPreview(sample: .rgb(0.05, 0.04, 0.06)) }
#Preview("Bright cover, dark") { PageColorPreview(sample: .rgb(1, 0.9, 0.2)).preferredColorScheme(.dark) }
#Preview("No cover") { PageColorPreview(sample: nil) }
#endif
