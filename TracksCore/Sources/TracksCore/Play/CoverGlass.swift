import Foundation
import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins

/// How Full Cover's cover carries on below itself on iPhone: as liquid glass rather than a fade
/// to colour. Below where it starts to dissolve, the cover goes on as itself and then as the
/// colours along its foot, each column running on down, softening more the further down it is
/// until only colour is left, a little brighter and richer, as glass is, and easing into the
/// colours under it; further down it deepens (or lifts) just as far as the song's name and the
/// controls need, keeping its colours.
///
/// Where it starts to dissolve follows what the cover is of: never above its subject (a face,
/// a figure, the title), so the subject is always sharp.
public nonisolated enum CoverGlass {
    /// Where the sharp cover starts to give way to its glass, as a part of its side from the top:
    /// at 88% for a cover whose subject ends higher, lower for one whose subject reaches down,
    /// and at 96% at most, so a subject that runs to the foot stays sharp.
    public static func dissolve(subjectBottom: Double?) -> Double {
        guard let subjectBottom, subjectBottom.isFinite else { return earliest }
        return min(latest, max(earliest, subjectBottom + 0.03))
    }

    static let earliest = 0.88
    static let latest = 0.96

    /// How far below the cover the glass has deepened for the type, as a part of its side.
    public static let settle = 0.18
    /// The softening the glass reaches, as a part of the side, and how far down it takes to.
    static let softest = 0.14
    static let softening = 0.22

    /// The glass below a cover `side` points wide at the top of `size`, its sharp part ending
    /// at `start` (see ``dissolve(subjectBottom:)``), `pointsPerPixel` to a pixel, transparent
    /// above. `picture` is the cover, any size; `under` the colours it eases into, any size.
    public static func extend(picture: CGImage, under: CGImage? = nil, size: CGSize, side: CGFloat, start: Double, ink: CoverBleed.Ink, increasedContrast: Bool, pointsPerPixel: CGFloat = 2, context: CIContext) -> CGImage? {
        guard size.width > 0, size.height > 0, side > 0 else { return nil }
        let scale = 1 / pointsPerPixel
        let width = Int((size.width * scale).rounded(.up)), height = Int((size.height * scale).rounded(.up))
        let extent = CGRect(x: 0, y: 0, width: width, height: height)
        let sidePixels = side * scale
        let top = CGFloat(height)

        let source = CIImage(cgImage: picture)
        let fit = CGAffineTransform(scaleX: sidePixels / source.extent.width, y: sidePixels / source.extent.height)
        let canvas = source.transformed(by: fit).transformed(by: CGAffineTransform(translationX: 0, y: top - sidePixels)).clampedToExtent()

        let dissolveY = top - CGFloat(start) * sidePixels
        func softened(_ image: CIImage, radius: CGFloat) -> CIImage? {
            let gradient = CIFilter.linearGradient()
            gradient.point0 = CGPoint(x: 0, y: dissolveY)
            gradient.point1 = CGPoint(x: 0, y: dissolveY - CGFloat(softening) * sidePixels)
            gradient.color0 = CIColor(red: 0, green: 0, blue: 0)
            gradient.color1 = CIColor(red: 1, green: 1, blue: 1)
            let blur = CIFilter.maskedVariableBlur()
            blur.inputImage = image
            blur.mask = gradient.outputImage
            blur.radius = Float(radius)
            return blur.outputImage
        }
        // Wide as well as deep further down: squeezed across before softening and stretched
        // back, so each column's colour runs into its neighbours' rather than down as a band.
        let squeeze = 1 / widening
        let near = softened(canvas, radius: CGFloat(softest) * sidePixels)?.cropped(to: extent)
        let wide = softened(canvas.transformed(by: CGAffineTransform(scaleX: squeeze, y: 1)), radius: CGFloat(softest) * sidePixels * 0.6)?
            .transformed(by: CGAffineTransform(scaleX: widening, y: 1)).cropped(to: extent)
        func glassy(_ image: CIImage?) -> [UInt8]? {
            let tint = CIFilter.colorControls()
            tint.inputImage = image
            tint.saturation = 1.12
            tint.brightness = 0.02
            guard let output = tint.outputImage, let rendered = context.createCGImage(output, from: extent, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)) else { return nil }
            return bitmap(rendered, width: width, height: height)
        }
        guard let nearPixels = glassy(near), let widePixels = glassy(wide) else { return nil }
        let underPixels = under.flatMap { bitmap($0, width: width, height: height) }

        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let startPoints = Double(start) * Double(side)
        let edge = Double(side)
        let settled = edge + settle * Double(side)
        let foot = Double(size.height)
        for row in 0..<height {
            let y = (Double(row) + 0.5) * Double(pointsPerPixel)
            guard y >= startPoints - 4 else { continue }
            let below = max(0, y - edge)
            let widening = smootherstep(min(1, below / (spread * Double(side))))
            let toType = smootherstep(min(1, max(0, (y - edge) / (settled - edge))))
            let toUnder = underPixels == nil ? 0 : fieldShare * smootherstep(min(1, below / max(1, foot - edge)))
            for column in 0..<width {
                let index = (row * width + column) * 4
                func channel(_ k: Int) -> Double {
                    (Double(nearPixels[index + k]) + (Double(widePixels[index + k]) - Double(nearPixels[index + k])) * widening) / 255
                }
                var colour = CoverBleed.Colour(red: channel(0), green: channel(1), blue: channel(2))
                if toType > 0 {
                    let legible = CoverBleed.legible(colour, for: ink, increasedContrast: increasedContrast)
                    colour = CoverBleed.Colour(
                        red: colour.red + (legible.red - colour.red) * toType,
                        green: colour.green + (legible.green - colour.green) * toType,
                        blue: colour.blue + (legible.blue - colour.blue) * toType
                    )
                }
                if toUnder > 0, let underPixels {
                    let glass = Oklab(red: colour.red, green: colour.green, blue: colour.blue)
                    let field = Oklab(red: Double(underPixels[index]) / 255, green: Double(underPixels[index + 1]) / 255, blue: Double(underPixels[index + 2]) / 255)
                    let mixed = Oklab(
                        lightness: glass.lightness + (field.lightness - glass.lightness) * toUnder,
                        a: glass.a + (field.a - glass.a) * toUnder,
                        b: glass.b + (field.b - glass.b) * toUnder
                    ).srgb
                    colour = CoverBleed.Colour(red: mixed.red, green: mixed.green, blue: mixed.blue)
                }
                let noise = (dither(column, row) - 0.5) / 255
                rgba[index] = byte(colour.red + noise)
                rgba[index + 1] = byte(colour.green + noise)
                rgba[index + 2] = byte(colour.blue + noise)
                rgba[index + 3] = 255
            }
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue), provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// How much wider than deep the softening is far below the cover, and how far below it
    /// takes to get there, as a part of the side.
    static let widening: CGFloat = 5
    static let spread = 0.25

    private static func bitmap(_ image: CGImage, width: Int, height: Int) -> [UInt8]? {
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = rgba.withUnsafeMutableBytes { bytes -> Bool in
            guard let bitmap = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            bitmap.interpolationQuality = .high
            bitmap.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? rgba : nil
    }

    private static func dither(_ x: Int, _ y: Int) -> Double {
        let seed = sin(Double(x) * 12.9898 + Double(y) * 78.233) * 43758.5453
        return seed - seed.rounded(.down)
    }

    /// How much of the colours under it the glass gives way to by the screen's foot.
    static let fieldShare = 0.85

    private static func smootherstep(_ t: Double) -> Double { t * t * t * (t * (6 * t - 15) + 10) }

    private static func byte(_ value: Double) -> UInt8 {
        UInt8(min(255, max(0, (value * 255).rounded())))
    }
}
