import Testing
import Foundation
@testable import TracksCore

@Suite("Living Cover grade")
struct LivingCoverGradeTests {
    private static func cover(side: Int = 32, _ colour: (Int, Int) -> (Double, Double, Double)) -> [UInt8] {
        var rgba: [UInt8] = []
        for y in 0..<side {
            for x in 0..<side {
                let (r, g, b) = colour(x, y)
                rgba += [UInt8(r * 255), UInt8(g * 255), UInt8(b * 255), 255]
            }
        }
        return rgba
    }

    private static let covers: [String: [UInt8]] = [
        "white": cover { _, _ in (1, 1, 1) },
        "black": cover { _, _ in (0, 0, 0) },
        "red": cover { _, _ in (0.85, 0.15, 0.17) },
        "yellow": cover { _, _ in (1, 0.9, 0) },
        "photo": cover { x, y in (Double(x) / 31, Double(y) / 31, 1 - Double(x) / 31) },
        "monochrome": cover { x, _ in (Double(x) / 31, Double(x) / 31, Double(x) / 31) },
    ]

    private static func contrastWithWhite(_ colour: Oklab) -> Double {
        1.05 / (colour.luminance + 0.05)
    }

    @Test("White type reads on every colour of every cover, most of all under the words")
    func whiteTypeReads() {
        let steps = stride(from: 0.0, through: 1.0, by: 0.125)
        for (name, rgba) in Self.covers {
            let grade = LivingCoverGrade(rgba: rgba)
            for r in steps { for g in steps { for b in steps {
                for structure in [-0.5, 0, 0.5] {
                    let source = Oklab(red: r, green: g, blue: b)
                    let words = grade.shade(source, ceiling: LivingCoverGrade.textCeiling, structure: structure)
                    let elsewhere = grade.shade(source, ceiling: LivingCoverGrade.readableCeiling, structure: structure)
                    #expect(Self.contrastWithWhite(words) >= 5, "\(name) \(r) \(g) \(b)")
                    #expect(Self.contrastWithWhite(elsewhere) >= 4.2, "\(name) \(r) \(g) \(b)")
                }
            }}}
        }
    }

    @Test("A white cover sits lighter than a black one, and both have room to flow")
    func lightAndDarkCovers() {
        let white = LivingCoverGrade(rgba: Self.covers["white"]!)
        let black = LivingCoverGrade(rgba: Self.covers["black"]!)
        #expect(white.low > black.high - 0.05)
        #expect(black.low >= LivingCoverGrade.floor)
        for grade in [white, black] {
            #expect(grade.high - grade.low > 0.119)
        }
        let whiteShade = white.shade(Oklab(red: 1, green: 1, blue: 1), ceiling: LivingCoverGrade.readableCeiling)
        let blackShade = black.shade(Oklab(red: 0, green: 0, blue: 0), ceiling: LivingCoverGrade.readableCeiling)
        #expect(whiteShade.lightness > 0.4)
        #expect(blackShade.lightness > LivingCoverGrade.floor)
        #expect(blackShade.lightness < 0.3)
    }

    @Test("A flat cover is given a texture of its own colour; a varied one isn't")
    func texture() {
        #expect(LivingCoverGrade(rgba: Self.covers["red"]!).texture > 0.95)
        #expect(LivingCoverGrade(rgba: Self.covers["photo"]!).texture < 0.05)
        let red = LivingCoverGrade(rgba: Self.covers["red"]!)
        let source = Oklab(red: 0.85, green: 0.15, blue: 0.17)
        let folded = red.shade(source, ceiling: LivingCoverGrade.readableCeiling, structure: -0.5)
        let open = red.shade(source, ceiling: LivingCoverGrade.readableCeiling, structure: 0.5)
        #expect(open.lightness - folded.lightness > 0.08)
    }

    @Test("Greys stay grey, and a black-and-white cover isn't tinted")
    func greys() {
        let monochrome = LivingCoverGrade(rgba: Self.covers["monochrome"]!)
        #expect(monochrome.chroma < 1)
        for level in stride(from: 0.0, through: 1.0, by: 0.1) {
            let shade = monochrome.shade(Oklab(red: level, green: level, blue: level), ceiling: LivingCoverGrade.readableCeiling)
            #expect(shade.chroma < 0.005)
        }
    }

    @Test("A darkened yellow leans to amber, not olive, and a pale beige to a warm grey, not brown")
    func noMud() {
        let yellow = LivingCoverGrade(rgba: Self.covers["yellow"]!)
        let source = Oklab(red: 1, green: 0.9, blue: 0)
        let shaded = yellow.shade(source, ceiling: LivingCoverGrade.textCeiling)
        #expect(shaded.hue < source.hue - 0.3)

        let photo = LivingCoverGrade(rgba: Self.covers["photo"]!)
        let beige = Oklab(red: 0.85, green: 0.75, blue: 0.62)
        let plain = beige.chroma * photo.chroma
        let shadedBeige = photo.shade(beige, ceiling: LivingCoverGrade.textCeiling)
        #expect(shadedBeige.chroma < plain * 0.8)
        #expect(abs(shadedBeige.hue - beige.hue) < 0.05)
    }

    @Test("Every colour it draws is one sRGB can show")
    func inGamut() {
        let red = LivingCoverGrade(rgba: Self.covers["red"]!)
        for colour in [(1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0), (1.0, 0.0, 1.0), (0.0, 1.0, 1.0)] {
            let (r, g, b) = red.shade(Oklab(red: colour.0, green: colour.1, blue: colour.2), ceiling: LivingCoverGrade.readableCeiling).linearRGB
            for channel in [r, g, b] {
                #expect(channel > -0.001 && channel < 1.001)
            }
        }
    }

    @Test("No pixels gives a middling grade")
    func empty() {
        #expect(LivingCoverGrade(rgba: []) == .neutral)
    }
}

@Suite("Cover frame")
struct CoverFrameTests {
    private static func cover(side: Int = 96, _ colour: (Int, Int) -> (UInt8, UInt8, UInt8)) -> [UInt8] {
        (0..<side).flatMap { y in (0..<side).flatMap { x -> [UInt8] in
            let (r, g, b) = colour(x, y)
            return [r, g, b, 255]
        }}
    }

    private static func picture(_ x: Int, _ y: Int) -> (UInt8, UInt8, UInt8) {
        (UInt8((x * 37 + y * 11) % 200), UInt8((x * 13 + y * 29) % 180), UInt8((x * 7 + y * 41) % 220))
    }

    @Test("A white frame round a picture is found, each edge at its depth")
    func whiteFrame() throws {
        let rgba = Self.cover { x, y in
            (x < 9 || x >= 87 || y < 6 || y >= 80) ? (250, 250, 248) : Self.picture(x, y)
        }
        let frame = try #require(CoverFrame(rgba: rgba, side: 96))
        #expect(abs(frame.leading - 9.0 / 96) < 0.001)
        #expect(abs(frame.trailing - 9.0 / 96) < 0.001)
        #expect(abs(frame.top - 6.0 / 96) < 0.001)
        #expect(abs(frame.bottom - 16.0 / 96) < 0.001)
    }

    @Test("A plain cover, a full-bleed picture and a figure on a plain ground have no frame")
    func noFrame() {
        #expect(CoverFrame(rgba: Self.cover { _, _ in (255, 255, 255) }, side: 96) == nil)
        #expect(CoverFrame(rgba: Self.cover(Self.picture), side: 96) == nil)
        let figure = Self.cover { x, y in
            let dx = x - 48, dy = y - 48
            return dx * dx + dy * dy < 30 * 30 ? Self.picture(x, y) : (250, 250, 250)
        }
        #expect(CoverFrame(rgba: figure, side: 96) == nil)
    }
}
