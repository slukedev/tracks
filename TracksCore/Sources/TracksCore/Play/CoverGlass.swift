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
    /// and at 97% at most, so a subject that runs to the foot stays sharp.
    public static func dissolve(subjectBottom: Double?) -> Double {
        guard let subjectBottom, subjectBottom.isFinite else { return earliest }
        return min(latest, max(earliest, subjectBottom + 0.03))
    }

    static let earliest = 0.88
    static let latest = 0.97

    /// How far below the cover the glass has deepened for the type, as a part of its side.
    public static let settle = 0.18
    /// The softening the glass reaches, as a part of the side, and how far down it takes to.
    static let softest = 0.14
    static let softening = 0.22

    /// The glass below a cover `side` points wide at the top of `size`, its sharp part ending
    /// at `start` (see ``dissolve(subjectBottom:)``), `pointsPerPixel` to a pixel, transparent
    /// above. `picture` is the cover, any size.
    public static func extend(picture: CGImage, size: CGSize, side: CGFloat, start: Double, ink: CoverBleed.Ink, increasedContrast: Bool, pointsPerPixel: CGFloat = 2, context: CIContext) -> CGImage? {
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
        let gradient = CIFilter.linearGradient()
        gradient.point0 = CGPoint(x: 0, y: dissolveY)
        gradient.point1 = CGPoint(x: 0, y: dissolveY - CGFloat(softening) * sidePixels)
        gradient.color0 = CIColor(red: 0, green: 0, blue: 0)
        gradient.color1 = CIColor(red: 1, green: 1, blue: 1)
        let blur = CIFilter.maskedVariableBlur()
        blur.inputImage = canvas
        blur.mask = gradient.outputImage?.cropped(to: extent)
        blur.radius = Float(CGFloat(softest) * sidePixels)
        let glassy = CIFilter.colorControls()
        glassy.inputImage = blur.outputImage?.cropped(to: extent)
        glassy.saturation = 1.12
        glassy.brightness = 0.02
        guard let output = glassy.outputImage,
              let rendered = context.createCGImage(output, from: extent, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        else { return nil }

        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = rgba.withUnsafeMutableBytes { bytes -> Bool in
            guard let bitmap = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            bitmap.draw(rendered, in: extent)
            return true
        }
        guard drawn else { return nil }

        let startPoints = Double(start) * Double(side)
        let edge = Double(side)
        let settled = edge + settle * Double(side)
        for row in 0..<height {
            let y = (Double(row) + 0.5) * Double(pointsPerPixel)
            let base = row * width * 4
            guard y >= startPoints - 4 else {
                for index in base..<(base + width * 4) { rgba[index] = 0 }
                continue
            }
            let toType = smooth(min(1, max(0, (y - edge) / (settled - edge))))
            let opacity = 1 - fieldShare * smooth(min(1, max(0, (y - edge) / (Double(size.height) - edge))))
            for column in 0..<width {
                let index = base + column * 4
                var colour = CoverBleed.Colour(red: Double(rgba[index]) / 255, green: Double(rgba[index + 1]) / 255, blue: Double(rgba[index + 2]) / 255)
                if toType > 0 {
                    let legible = CoverBleed.legible(colour, for: ink, increasedContrast: increasedContrast)
                    colour = CoverBleed.Colour(
                        red: colour.red + (legible.red - colour.red) * toType,
                        green: colour.green + (legible.green - colour.green) * toType,
                        blue: colour.blue + (legible.blue - colour.blue) * toType
                    )
                }
                rgba[index] = byte(colour.red * opacity)
                rgba[index + 1] = byte(colour.green * opacity)
                rgba[index + 2] = byte(colour.blue * opacity)
                rgba[index + 3] = byte(opacity)
            }
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue), provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// How much of the colours under it the glass gives way to by the screen's foot.
    static let fieldShare = 0.5

    private static func smooth(_ t: Double) -> Double { t * t * (3 - 2 * t) }

    private static func byte(_ value: Double) -> UInt8 {
        UInt8(min(255, max(0, (value * 255).rounded())))
    }
}
