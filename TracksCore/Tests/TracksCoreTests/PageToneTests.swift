import Testing
@testable import TracksCore

private typealias Colour = PageTone.Colour

private let covers: [(name: String, colour: Colour)] = [
    ("black", .black),
    ("white", .white),
    ("mid grey", Colour(red: 0.5, green: 0.5, blue: 0.5)),
    ("saturated red", Colour(red: 1, green: 0, blue: 0)),
    ("saturated blue", Colour(red: 0.1, green: 0.3, blue: 0.95)),
    ("saturated yellow", Colour(red: 1, green: 0.9, blue: 0.1)),
    ("very dark green", Colour(red: 0.02, green: 0.06, blue: 0.03)),
    ("pale pink", Colour(red: 0.98, green: 0.85, blue: 0.88)),
]

private let appearances: [PageTone.Appearance] = [.light, .dark]

/// The hue of an sRGB colour, 0 to 360, for colours that have one.
private func hue(_ colour: Colour) -> Double {
    let high = max(colour.red, colour.green, colour.blue)
    let chroma = high - min(colour.red, colour.green, colour.blue)
    let sector = switch high {
    case colour.red: ((colour.green - colour.blue) / chroma).truncatingRemainder(dividingBy: 6)
    case colour.green: (colour.blue - colour.red) / chroma + 2
    default: (colour.red - colour.green) / chroma + 4
    }
    return (sector * 60 + 360).truncatingRemainder(dividingBy: 360)
}

@Suite("A page's colour from its cover")
struct PageToneTests {
    @Test("Contrast is WCAG's ratio")
    func contrastMatchesWCAG() {
        #expect(PageTone.contrast(.black, .white) == 21)
        #expect(PageTone.contrast(.white, .white) == 1)
        #expect(abs(PageTone.contrast(Colour(red: 0.5, green: 0.5, blue: 0.5), .white) - 3.98) < 0.01)
    }

    @Test("Every level of type reads on every page", arguments: covers.map(\.colour), appearances)
    func typeReads(cover: PageTone.Colour, appearance: PageTone.Appearance) throws {
        let tone = try #require(PageTone(cover: cover, appearance: appearance))
        #expect(PageTone.contrast(tone.primary, tone.background) >= 7)
        #expect(PageTone.contrast(tone.secondary, tone.background) >= 4.5)
        #expect(PageTone.contrast(tone.tertiary, tone.background) >= 3)
        #expect(PageTone.contrast(tone.accent, tone.background) >= 4.5)
    }

    @Test("Secondary and tertiary type stay quieter than primary", arguments: covers.map(\.colour), appearances)
    func levelsStayInOrder(cover: PageTone.Colour, appearance: PageTone.Appearance) throws {
        let tone = try #require(PageTone(cover: cover, appearance: appearance))
        let primary = PageTone.contrast(tone.primary, tone.background)
        let secondary = PageTone.contrast(tone.secondary, tone.background)
        let tertiary = PageTone.contrast(tone.tertiary, tone.background)
        #expect(primary > secondary)
        #expect(secondary > tertiary)
    }

    @Test("Dark Mode always gives a dark page with white type", arguments: covers.map(\.colour))
    func darkModeIsDark(cover: PageTone.Colour) throws {
        let tone = try #require(PageTone(cover: cover, appearance: .dark))
        #expect(tone.isDark)
        #expect(tone.primary == .white)
        #expect(PageTone.luminance(tone.background) <= 0.06)
    }

    @Test("A cover gives a deeper page in Dark Mode than the dark page it gives in light")
    func darkModeIsDeeper() throws {
        let red = Colour(red: 0.85, green: 0.2, blue: 0.25)
        let light = try #require(PageTone(cover: red, appearance: .light))
        let dark = try #require(PageTone(cover: red, appearance: .dark))
        #expect(light.isDark)
        #expect(PageTone.luminance(dark.background) < PageTone.luminance(light.background))
    }

    @Test("In light appearance a bright cover gives a light page with black type")
    func brightCoverGivesLightPage() throws {
        let yellow = try #require(PageTone(cover: Colour(red: 1, green: 0.9, blue: 0.1), appearance: .light))
        #expect(!yellow.isDark)
        #expect(yellow.primary == .black)

        let white = try #require(PageTone(cover: .white, appearance: .light))
        #expect(!white.isDark)
        // Not the plain white of an ordinary page: the cover still shows.
        #expect(PageTone.luminance(white.background) < 0.9)
    }

    @Test("A dark or middling cover gives a dark page in light appearance too")
    func darkCoverGivesDarkPage() throws {
        for cover in [Colour.black, Colour(red: 0.5, green: 0.5, blue: 0.5), Colour(red: 1, green: 0, blue: 0)] {
            let tone = try #require(PageTone(cover: cover, appearance: .light))
            #expect(tone.isDark)
        }
    }

    @Test("A grey cover gives a neutral page with neutral accent")
    func greyStaysNeutral() throws {
        for cover in [Colour.black, .white, Colour(red: 0.5, green: 0.5, blue: 0.5), Colour(red: 0.52, green: 0.5, blue: 0.48)] {
            let tone = try #require(PageTone(cover: cover, appearance: .light))
            #expect(tone.background.chroma < 0.02)
            #expect(tone.accent == tone.primary)
        }
    }

    @Test("A coloured cover keeps its hue, held below neon")
    func colourKeepsHue() throws {
        let blue = Colour(red: 0.1, green: 0.3, blue: 0.95)
        let tone = try #require(PageTone(cover: blue, appearance: .light))
        #expect(abs(hue(tone.background) - hue(blue)) < 3)
        #expect(tone.background.chroma < blue.chroma)
        #expect(tone.background.chroma > 0.05)
    }

    @Test("A very dark cover gives a page that's dark but not pitch black")
    func veryDarkCover() throws {
        let tone = try #require(PageTone(cover: Colour(red: 0.02, green: 0.06, blue: 0.03), appearance: .light))
        #expect(tone.isDark)
        #expect(PageTone.luminance(tone.background) >= 0.009)
    }

    @Test("A yellow cover deepened for Dark Mode leans to amber, not olive")
    func yellowLeansAmber() throws {
        let tone = try #require(PageTone(cover: Colour(red: 1, green: 0.9, blue: 0.1), appearance: .dark))
        #expect(hue(tone.background) < 50)
    }

    @Test("A mostly transparent cover gives no page colour")
    func transparentCover() {
        #expect(PageTone(cover: Colour(red: 0.9, green: 0.2, blue: 0.2), coverage: 0.1, appearance: .light) == nil)
        #expect(PageTone(cover: Colour(red: 0.9, green: 0.2, blue: 0.2), coverage: 0.6, appearance: .light) != nil)
    }

    @Test("Channels out of range are read as their nearest colour")
    func outOfRangeChannels() throws {
        let tone = try #require(PageTone(cover: Colour(red: 1.4, green: -0.2, blue: 0), appearance: .light))
        let red = try #require(PageTone(cover: Colour(red: 1, green: 0, blue: 0), appearance: .light))
        #expect(tone == red)
    }
}
