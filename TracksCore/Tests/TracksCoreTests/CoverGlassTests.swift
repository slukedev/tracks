import Testing
import Foundation
import CoreGraphics
import CoreImage
@testable import TracksCore

@Suite("Full Cover's glass")
struct CoverGlassTests {
    @Test("It starts to dissolve below the subject, never above 88% of the cover nor below 96%")
    func dissolveFollowsTheSubject() {
        #expect(CoverGlass.dissolve(subjectBottom: nil) == 0.88)
        #expect(CoverGlass.dissolve(subjectBottom: 0.4) == 0.88)
        #expect(abs(CoverGlass.dissolve(subjectBottom: 0.9) - 0.93) < 1e-9)
        #expect(CoverGlass.dissolve(subjectBottom: 1) == 0.96)
        #expect(CoverGlass.dissolve(subjectBottom: .nan) == 0.88)
    }

    private static func picture(_ paint: (Int, Int) -> (UInt8, UInt8, UInt8)) -> CGImage {
        let side = 64
        var rgba: [UInt8] = []
        for y in 0..<side { for x in 0..<side { let (r, g, b) = paint(x, y); rgba += [r, g, b, 255] } }
        return CGImage(width: side, height: side, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: side * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: CGDataProvider(data: Data(rgba) as CFData)!, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
    }

    @Test("Under the song's name its colours read for the type, on a light cover and a dark one; above where it starts it's clear, and it never quite gives way to the colours under it", arguments: [CoverBleed.Ink.light, .dark])
    func readsUnderTheWords(ink: CoverBleed.Ink) throws {
        let cover = Self.picture { x, y in ink == .light ? (UInt8(180 + x), 240, UInt8(200 + y / 2)) : (UInt8(x * 2), 20, UInt8(y)) }
        let size = CGSize(width: 402, height: 874)
        let glass = try #require(CoverGlass.extend(picture: cover, size: size, side: 402, start: 0.9, ink: ink, increasedContrast: false, pointsPerPixel: 2, context: CIContext()))
        var rgba = [UInt8](repeating: 0, count: glass.width * glass.height * 4)
        rgba.withUnsafeMutableBytes { bytes in
            CGContext(data: bytes.baseAddress, width: glass.width, height: glass.height, bitsPerComponent: 8, bytesPerRow: glass.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                .draw(glass, in: CGRect(x: 0, y: 0, width: glass.width, height: glass.height))
        }
        func pixel(_ x: Int, _ y: Double) -> (colour: CoverBleed.Colour, alpha: UInt8) {
            let row = min(glass.height - 1, Int(y / 2)), index = (row * glass.width + x) * 4
            let alpha = max(1, Double(rgba[index + 3])) / 255
            return (CoverBleed.Colour(red: Double(rgba[index]) / 255 / alpha, green: Double(rgba[index + 1]) / 255 / alpha, blue: Double(rgba[index + 2]) / 255 / alpha), rgba[index + 3])
        }
        #expect(pixel(50, 100).alpha == 0)
        #expect(pixel(50, 380).alpha == 255)
        #expect(pixel(50, 860).alpha > 100)
        let type: CoverBleed.Colour = ink == .light ? .white : CoverBleed.Colour(red: 0.07, green: 0.07, blue: 0.07)
        for y in stride(from: 402 * (1 + CoverGlass.settle), to: 874, by: 20) {
            for x in stride(from: 0, to: glass.width, by: 15) {
                #expect(pixel(x, y).colour.contrast(with: type) >= 4.4)
            }
        }
    }
}
