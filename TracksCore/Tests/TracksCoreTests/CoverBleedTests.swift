import Testing
import Foundation
import CoreGraphics
@testable import TracksCore

/// A cover `side` square, each pixel coloured by `paint(x, y)` (0 to 255 each), opaque
/// unless `paint` gives an alpha.
private func cover(side: Int = 48, _ paint: (Int, Int) -> (UInt8, UInt8, UInt8, UInt8)) -> [UInt8] {
    var rgba: [UInt8] = []
    rgba.reserveCapacity(side * side * 4)
    for y in 0..<side {
        for x in 0..<side {
            let (r, g, b, a) = paint(x, y)
            // Premultiplied, as a bitmap context stores it.
            let scale = Double(a) / 255
            rgba += [UInt8(Double(r) * scale), UInt8(Double(g) * scale), UInt8(Double(b) * scale), a]
        }
    }
    return rgba
}

private func solid(_ r: UInt8, _ g: UInt8, _ b: UInt8) -> [UInt8] {
    cover { _, _ in (r, g, b, 255) }
}

private func distance(_ a: CoverBleed.Colour, _ b: CoverBleed.Colour) -> Double {
    sqrt(pow(a.red - b.red, 2) + pow(a.green - b.green, 2) + pow(a.blue - b.blue, 2))
}

@Suite("Full Cover's edges")
struct CoverBleedTests {
    @Test("Each edge carries its own colour, so the cover simply goes on")
    func edgesKeepTheirColours() throws {
        // Sky above, grass below, a wall on each side.
        let pixels = cover { x, y in
            if x < 3 { return (200, 40, 40, 255) }
            if x > 44 { return (40, 40, 200, 255) }
            return y < 24 ? (90, 170, 230, 255) : (30, 140, 60, 255)
        }
        let bleed = try #require(CoverBleed(rgba: pixels, side: 48))
        let grass = CoverBleed.Colour(red: 30 / 255, green: 140 / 255, blue: 60 / 255)
        let sky = CoverBleed.Colour(red: 90 / 255, green: 170 / 255, blue: 230 / 255)
        // The middle of the bottom edge is grass, the middle of the top sky.
        #expect(distance(bleed.bottom[6], grass) < 0.03, "\(bleed.bottom.map { ($0.red, $0.green, $0.blue) })")
        #expect(distance(bleed.top[6], sky) < 0.03)
        // A side is read from the side's band, not the middle.
        #expect(bleed.leading.allSatisfy { $0.red > $0.blue })
        #expect(bleed.trailing.allSatisfy { $0.blue > $0.red })
    }

    @Test("The type is dark on a light edge and white on a dark or mid one", arguments: [
        (rgb: (255, 255, 255), ink: CoverBleed.Ink.dark),
        (rgb: (230, 200, 60), ink: .dark),
        (rgb: (150, 175, 190), ink: .dark),
        (rgb: (0, 0, 0), ink: .light),
        (rgb: (60, 160, 70), ink: .light),
        (rgb: (200, 30, 40), ink: .light),
    ] as [(rgb: (Int, Int, Int), ink: CoverBleed.Ink)])
    func inkFollowsTheEdge(_ example: (rgb: (Int, Int, Int), ink: CoverBleed.Ink)) throws {
        let (r, g, b) = example.rgb
        let bleed = try #require(CoverBleed(rgba: solid(UInt8(r), UInt8(g), UInt8(b)), side: 48))
        #expect(bleed.ink(.bottom) == example.ink)
    }

    @Test("Settles into the colour most of the edge is, not a muddy mix")
    func floorIsTheDominantColour() throws {
        // Three quarters of the bottom is a blue sky, a quarter a warm shoulder.
        let pixels = cover { x, _ in x < 36 ? (40, 90, 200, 255) : (220, 160, 120, 255) }
        let bleed = try #require(CoverBleed(rgba: pixels, side: 48))
        let floor = bleed.average(.bottom)
        #expect(floor.blue > floor.red)
        #expect(floor.blue > 0.5)
    }

    @Test("A busy edge is calmed, a plain one kept")
    func busyEdgesAreCalmed() throws {
        // Black and white stripes, two pixels wide: lettering, or a crowd.
        let stripes = try #require(CoverBleed(rgba: cover { x, _ in x / 2 % 2 == 0 ? (0, 0, 0, 255) : (255, 255, 255, 255) }, side: 48))
        let lightness = stripes.bottom.map(\.luminance)
        #expect((lightness.max() ?? 0) - (lightness.min() ?? 0) < 0.15)
        // A gentle gradient keeps its run from end to end.
        let gradient = try #require(CoverBleed(rgba: cover { x, _ in (UInt8(x * 5), UInt8(x * 5), UInt8(x * 5), 255) }, side: 48))
        #expect(gradient.bottom.last!.luminance - gradient.bottom.first!.luminance > 0.3)
    }

    @Test("See-through parts of a cover take their neighbours' colour, not black")
    func transparencyIgnored() throws {
        let pixels = cover { x, _ in x < 24 ? (0, 0, 0, 0) : (240, 200, 40, 255) }
        let bleed = try #require(CoverBleed(rgba: pixels, side: 48))
        #expect(bleed.bottom.allSatisfy { $0.red > 0.6 && $0.green > 0.5 })
        // Wholly see-through: nothing to read.
        #expect(CoverBleed(rgba: cover { _, _ in (0, 0, 0, 0) }, side: 48) == nil)
    }
}

@Suite("Full Cover's layout")
struct CoverBleedLayoutTests {
    let phone = CGSize(width: 402, height: 874)

    @Test("Under the song, the whole cover sits at the screen's top, its width, whatever the room; only its foot is cut short when the room ends above it")
    func belowSitsAtTheTop() {
        let top = CGRect(x: 0, y: 0, width: 402, height: 402)
        for room in [CGRect(x: 0, y: 82, width: 402, height: 402), CGRect(x: 0, y: 90, width: 402, height: 355), CGRect(x: 0, y: 90, width: 402, height: 200)] {
            #expect(CoverBleedLayout.frame(in: phone, focus: room, direction: .below) == top)
        }
        #expect(CoverBleedLayout.frame(in: phone, focus: nil, direction: .below) == top)
        #expect(CoverBleedLayout.shownFrame(of: top, focus: CGRect(x: 0, y: 82, width: 402, height: 402)) == top)
        let short = CGRect(x: 0, y: 60, width: 402, height: 300)
        let shown = CoverBleedLayout.shownFrame(of: top, focus: short)
        #expect(shown.minY == 0 && shown.width == 402)
        #expect(shown.maxY > short.maxY && shown.maxY < short.maxY + 12)
    }

    @Test("Beside the song, the cover reaches the window's edge and runs past the player's")
    func besideReachesTheEdge() {
        let window = CGSize(width: 1280, height: 820)
        let slot = CGRect(x: 140, y: 170, width: 480, height: 480)
        let rect = CoverBleedLayout.frame(in: window, focus: slot, direction: .beside)
        #expect(rect.minX <= 0.5)
        #expect(rect.maxX > slot.maxX)
        #expect(rect.minY >= 0 && rect.maxY <= window.height)
        #expect(abs(rect.width - rect.height) < 0.001)
        // A figure cut off at the cover's foot stands on the window's bottom edge.
        let standing = CoverBleedLayout.frame(in: window, focus: slot, direction: .beside, standsOnFloor: true)
        #expect(standing.maxY == window.height)
        // A photo running to its edges fills the window's height.
        let tall = CoverBleedLayout.frame(in: window, focus: slot, direction: .beside, fillsHeight: true)
        #expect(tall.minY == 0 && tall.height == window.height && tall.maxX > slot.maxX)
    }
}
