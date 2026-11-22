import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins

/// Draws several covers melted into one field over a category's colour, once, into a picture:
/// the covers dissolve into each other and into their own blurred light with no edge anywhere,
/// the category's colour runs through them, the part that carries white type is deepened just
/// enough for it, and the whole picture is dithered so its long fades never band.
nonisolated enum CoverBlendRenderer {
    /// A colour in sRGB, with the maths the blend needs.
    struct RGB: Hashable, Sendable {
        var red: Double
        var green: Double
        var blue: Double

        init(red: Double, green: Double, blue: Double) {
            self.red = red
            self.green = green
            self.blue = blue
        }

        init?(_ color: CGColor) {
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let converted = color.converted(to: space, intent: .defaultIntent, options: nil),
                  let parts = converted.components, parts.count >= 3
            else { return nil }
            self.init(red: Double(parts[0]), green: Double(parts[1]), blue: Double(parts[2]))
        }

        static let white = RGB(red: 1, green: 1, blue: 1)
        static let black = RGB(red: 0, green: 0, blue: 0)

        func mixed(with other: RGB, by amount: Double) -> RGB {
            RGB(
                red: red + (other.red - red) * amount,
                green: green + (other.green - green) * amount,
                blue: blue + (other.blue - blue) * amount
            )
        }

        /// WCAG relative luminance.
        var luminance: Double {
            func linear(_ value: Double) -> Double {
                let value = min(max(value, 0), 1)
                return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }

        /// The colour taken towards black until its luminance is at most `ceiling`.
        func deepened(to ceiling: Double) -> RGB {
            var amount = 0.0
            var deep = self
            while deep.luminance > ceiling, amount < 1 {
                amount += 0.02
                deep = mixed(with: .black, by: amount)
            }
            return deep
        }

        /// The light end of the colour's field, top leading.
        var light: RGB { mixed(with: .white, by: 0.14) }
        /// The deep end of the colour's field, bottom trailing.
        var shade: RGB { mixed(with: .black, by: 0.2) }

        func ciColor(alpha: Double = 1) -> CIColor {
            CIColor(red: red * alpha, green: green * alpha, blue: blue * alpha, alpha: alpha)
        }
    }

    struct Request: Sendable {
        /// The covers in order of weight, the strongest first.
        var pictures: [CGImage]
        var color: RGB
        /// The picture's size in pixels.
        var size: CGSize
        /// Pixels from the bottom, above `fade`, that carry white type.
        var legible: CGFloat
        /// Pixels at the bottom that dissolve into `page`.
        var fade: CGFloat = 0
        var page: RGB?
    }

    /// White type needs 4.5:1, which is luminance 0.183 behind it. The blend aims for 5:1, so
    /// antialiased edges and fine detail inside the type's box still clear it.
    static let legibleLuminance = 0.16

    @concurrent
    static func draw(_ request: Request) async -> CGImage? {
        render(request)
    }

    static func render(_ request: Request) -> CGImage? {
        let extent = CGRect(origin: .zero, size: request.size).integral
        guard extent.width >= 1, extent.height >= 1 else { return nil }
        let color = request.color
        let pictures = request.pictures.map { CIImage(cgImage: $0) }

        var canvas = field(color, in: extent)
        if let lead = pictures.first {
            canvas = glow(of: lead, tint: color, in: extent).composited(over: canvas)
            for placement in placements(count: pictures.count, in: extent).reversed() {
                canvas = place(pictures[placement.index], placement, tint: color).composited(over: canvas)
            }
        }
        canvas = wash(color, in: extent).composited(over: canvas)
        canvas = sheen(in: extent).composited(over: canvas)

        let band = CGRect(x: 0, y: request.fade, width: extent.width, height: min(request.legible, extent.height - request.fade))
        if band.height > 0 {
            let deep = color.deepened(to: 0.07)
            let alpha = scrimAlpha(over: canvas, band: band, deep: deep)
            if alpha > 0 {
                canvas = scrim(deep, alpha: alpha, band: band, feather: max(band.height, extent.height * 0.35))
                    .composited(over: canvas)
            }
        }
        if let page = request.page, request.fade > 0 {
            canvas = dissolve(into: page, height: request.fade, in: extent).composited(over: canvas)
        }
        canvas = dithered(canvas, in: extent)
        return context.createCGImage(canvas, from: extent, format: .RGBA8, colorSpace: outputSpace)
    }

    // MARK: Layers

    /// The category's colour, lit from the top leading corner.
    private static func field(_ color: RGB, in extent: CGRect) -> CIImage {
        let gradient = CIFilter.smoothLinearGradient()
        gradient.point0 = CGPoint(x: extent.minX, y: extent.maxY)
        gradient.point1 = CGPoint(x: extent.maxX, y: extent.minY)
        gradient.color0 = color.light.ciColor()
        gradient.color1 = color.shade.ciColor()
        return (gradient.outputImage ?? CIImage(color: color.ciColor())).cropped(to: extent)
    }

    /// The strongest cover's light spread over the whole field, so nothing between the covers
    /// is ever bare. Blurred small, from the middle of the cover only, so its edges never smear
    /// in as streaks.
    private static func glow(of picture: CIImage, tint: RGB, in extent: CGRect) -> CIImage {
        let side: CGFloat = 96
        let small = picture.transformed(by: CGAffineTransform(scaleX: side / picture.extent.width, y: side / picture.extent.height))
            .transformed(by: CGAffineTransform(translationX: -picture.extent.minX * side / picture.extent.width, y: -picture.extent.minY * side / picture.extent.height))
        let sigma = side * 0.09
        let inner = CGRect(x: 0, y: 0, width: side, height: side).insetBy(dx: sigma * 3, dy: sigma * 3)
        let blurred = small.clampedToExtent()
            .applyingGaussianBlur(sigma: sigma)
            .cropped(to: inner)
        let filled = aspectFill(blurred, into: extent)
            .clampedToExtent()
            .applyingGaussianBlur(sigma: min(extent.width, extent.height) * 0.04)
            .cropped(to: extent)
        return woven(filled.applyingFilter("CIColorControls", parameters: [
            kCIInputSaturationKey: 1.35,
            kCIInputBrightnessKey: -0.02,
        ]), with: tint, by: 0.8)
    }

    /// Where each cover sits and how it dissolves.
    private struct Placement {
        let index: Int
        let rect: CGRect
        /// Where the cover is whole and where it has gone, in the rect's unit space with the
        /// origin top leading.
        let whole: CGPoint
        let gone: CGPoint
        var opacity: Double = 1
        /// How far the cover leans into the category's colours: the lead keeps its own.
        var weave: Double = 0.7
        /// Where the round falloff starts and where it reaches nothing, as fractions of the
        /// side from the middle. Half the side or less keeps a cover inside the field edgeless.
        var round: (inner: CGFloat, outer: CGFloat) = (0.2, 0.5)
    }

    /// The strongest cover whole at the trailing edge, top to bottom, dissolving leftwards;
    /// the next ones smaller and softer towards the leading edge, melting into its light. On a
    /// tall picture the strongest runs the width at the top and dissolves downwards.
    private static func placements(count: Int, in extent: CGRect) -> [Placement] {
        let (width, height) = (extent.width, extent.height)
        var placements: [Placement] = []
        if width >= height {
            let side = height
            placements.append(Placement(
                index: 0,
                rect: CGRect(x: width - side, y: 0, width: side, height: side),
                whole: CGPoint(x: 0.6, y: 0.5),
                gone: CGPoint(x: 0, y: 0.5),
                weave: 0.2,
                round: (0.45, 1)
            ))
            let others: [(x: CGFloat, y: CGFloat, side: CGFloat)] = [(0.36, 0.02, 0.86), (0.02, 0.18, 0.8), (0.22, 0.5, 0.7)]
            for (offset, spot) in others.prefix(max(0, count - 1)).enumerated() {
                let side = height * spot.side
                placements.append(Placement(
                    index: offset + 1,
                    rect: CGRect(x: width * spot.x - side * 0.3, y: height * (1 - spot.y) - side, width: side, height: side),
                    whole: CGPoint(x: 0.5, y: 0.5),
                    gone: CGPoint(x: 0.02, y: 0.5),
                    opacity: 0.85 - 0.15 * Double(offset)
                ))
            }
        } else {
            let side = width
            placements.append(Placement(
                index: 0,
                rect: CGRect(x: 0, y: height - side, width: side, height: side),
                whole: CGPoint(x: 0.5, y: 0.45),
                gone: CGPoint(x: 0.5, y: 1.02),
                weave: 0.2,
                round: (0.45, 1)
            ))
            let others: [(x: CGFloat, y: CGFloat, side: CGFloat)] = [(0.0, 0.42, 0.62), (0.52, 0.48, 0.58), (0.2, 0.62, 0.5)]
            for (offset, spot) in others.prefix(max(0, count - 1)).enumerated() {
                let side = width * spot.side
                placements.append(Placement(
                    index: offset + 1,
                    rect: CGRect(x: width * spot.x, y: height * (1 - spot.y) - side, width: side, height: side),
                    whole: CGPoint(x: 0.5, y: 0.5),
                    gone: CGPoint(x: 0.5, y: 0.02),
                    opacity: 0.7 - 0.12 * Double(offset)
                ))
            }
        }
        return placements
    }

    /// One cover in its place, its edges dissolved: fully along the gradient's direction and
    /// softly on every other side, so no edge of it is ever seen.
    private static func place(_ picture: CIImage, _ placement: Placement, tint: RGB) -> CIImage {
        let rect = placement.rect
        let filled = woven(aspectFill(picture, into: rect), with: tint, by: placement.weave)

        func point(_ unit: CGPoint) -> CGPoint {
            CGPoint(x: rect.minX + unit.x * rect.width, y: rect.maxY - unit.y * rect.height)
        }
        let along = CIFilter.smoothLinearGradient()
        along.point0 = point(placement.whole)
        along.point1 = point(placement.gone)
        along.color0 = CIColor(red: placement.opacity, green: placement.opacity, blue: placement.opacity)
        along.color1 = CIColor(red: 0, green: 0, blue: 0)

        let round = CIFilter.radialGradient()
        round.center = CGPoint(x: rect.midX, y: rect.midY)
        round.radius0 = Float(rect.width * placement.round.inner)
        round.radius1 = Float(rect.width * placement.round.outer)
        round.color0 = CIColor(red: 1, green: 1, blue: 1)
        round.color1 = CIColor(red: 0, green: 0, blue: 0)
        let soft = (round.outputImage ?? CIImage.empty()).applyingFilter("CIToneCurve", parameters: [
            "inputPoint0": CIVector(x: 0, y: 0),
            "inputPoint1": CIVector(x: 0.25, y: 0.08),
            "inputPoint2": CIVector(x: 0.5, y: 0.5),
            "inputPoint3": CIVector(x: 0.75, y: 0.92),
            "inputPoint4": CIVector(x: 1, y: 1),
        ])
        let mask = (along.outputImage ?? CIImage.empty())
            .applyingFilter("CIMultiplyCompositing", parameters: [kCIInputBackgroundImageKey: soft])
            .cropped(to: rect)
        return filled.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: CIImage.empty(),
            kCIInputMaskImageKey: mask,
        ])
    }

    /// The category's own colour rising from the bottom leading corner, where its name sits.
    private static func wash(_ color: RGB, in extent: CGRect) -> CIImage {
        let gradient = CIFilter.smoothLinearGradient()
        gradient.point0 = CGPoint(x: extent.minX, y: extent.minY)
        gradient.point1 = CGPoint(x: extent.minX + extent.width * 0.7, y: extent.minY + extent.height * 0.9)
        gradient.color0 = color.ciColor(alpha: 0.5)
        gradient.color1 = color.ciColor(alpha: 0)
        return (gradient.outputImage ?? CIImage.empty()).cropped(to: extent)
    }

    /// A whisper of light from the top leading corner, as light falls on glass.
    private static func sheen(in extent: CGRect) -> CIImage {
        let gradient = CIFilter.smoothLinearGradient()
        gradient.point0 = CGPoint(x: extent.minX, y: extent.maxY)
        gradient.point1 = CGPoint(x: extent.minX + extent.width * 0.42, y: extent.maxY - extent.height * 0.62)
        gradient.color0 = RGB.white.ciColor(alpha: 0.13)
        gradient.color1 = RGB.white.ciColor(alpha: 0)
        return (gradient.outputImage ?? CIImage.empty()).cropped(to: extent)
    }

    /// How much of the deep colour the band needs for white type: the least that brings its
    /// brightest patch down to ``legibleLuminance``, measured on the picture itself.
    private static func scrimAlpha(over image: CIImage, band: CGRect, deep: RGB) -> Double {
        let scale: CGFloat = 0.25
        let small = image.cropped(to: band)
            .transformed(by: CGAffineTransform(translationX: -band.minX, y: -band.minY))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let width = max(1, Int(band.width * scale))
        let height = max(1, Int(band.height * scale))
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        context.render(small, toBitmap: &bytes, rowBytes: width * 4, bounds: CGRect(x: 0, y: 0, width: width, height: height), format: .RGBA8, colorSpace: outputSpace)

        var bright: [RGB] = []
        for pixel in stride(from: 0, to: bytes.count, by: 4) {
            let color = RGB(red: Double(bytes[pixel]) / 255, green: Double(bytes[pixel + 1]) / 255, blue: Double(bytes[pixel + 2]) / 255)
            if color.luminance > legibleLuminance { bright.append(color) }
        }
        guard !bright.isEmpty else { return 0 }
        func isLegible(at alpha: Double) -> Bool {
            !bright.contains { $0.mixed(with: deep, by: alpha).luminance > legibleLuminance }
        }
        var (low, high) = (0.0, 1.0)
        for _ in 0..<8 {
            let middle = (low + high) / 2
            if isLegible(at: middle) { high = middle } else { low = middle }
        }
        return high
    }

    /// The deep colour over the band, at `alpha`, easing out above it.
    private static func scrim(_ deep: RGB, alpha: Double, band: CGRect, feather: CGFloat) -> CIImage {
        let gradient = CIFilter.smoothLinearGradient()
        gradient.point0 = CGPoint(x: 0, y: band.maxY)
        gradient.point1 = CGPoint(x: 0, y: band.maxY + feather)
        gradient.color0 = deep.ciColor(alpha: alpha)
        gradient.color1 = deep.ciColor(alpha: 0)
        // Down to the foot, under any fade, so the band never ends in an edge.
        return (gradient.outputImage ?? CIImage.empty())
            .cropped(to: CGRect(x: band.minX, y: 0, width: band.width, height: band.maxY + feather))
    }

    /// The bottom of a hero giving way to the page, so the page carries on from it.
    private static func dissolve(into page: RGB, height: CGFloat, in extent: CGRect) -> CIImage {
        let gradient = CIFilter.smoothLinearGradient()
        gradient.point0 = CGPoint(x: 0, y: height)
        gradient.point1 = CGPoint(x: 0, y: 0)
        gradient.color0 = page.ciColor(alpha: 0)
        gradient.color1 = page.ciColor()
        return (gradient.outputImage ?? CIImage.empty())
            .cropped(to: CGRect(x: extent.minX, y: extent.minY, width: extent.width, height: height))
    }

    /// Plus or minus one step of eight-bit noise, so long dark fades come out smooth.
    private static func dithered(_ image: CIImage, in extent: CGRect) -> CIImage {
        let step: CGFloat = 1 / 255
        let noise = CIFilter.randomGenerator().outputImage?
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: step * 2, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: step * 2, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: step * 2, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                "inputBiasVector": CIVector(x: -step, y: -step, z: -step, w: 0),
            ])
            .cropped(to: extent)
        guard let noise else { return image.cropped(to: extent) }
        return noise.applyingFilter("CIAdditionCompositing", parameters: [kCIInputBackgroundImageKey: image])
            .cropped(to: extent)
    }

    // MARK: Helpers

    private static func aspectFill(_ picture: CIImage, into rect: CGRect) -> CIImage {
        let source = picture.extent
        guard source.width > 0, source.height > 0 else { return CIImage.empty() }
        let scale = max(rect.width / source.width, rect.height / source.height)
        let width = source.width * scale
        let height = source.height * scale
        return picture
            .transformed(by: CGAffineTransform(translationX: -source.minX, y: -source.minY))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: rect.midX - width / 2, y: rect.midY - height / 2))
            .cropped(to: rect)
    }

    /// The category's colour run through a picture: its light and shade kept, redrawn in the
    /// colour's own deep and light ends, by `amount`. Redrawing rather than tinting keeps it
    /// clean: a purple cover tinted orange goes brown, redrawn it goes orange.
    private static func woven(_ picture: CIImage, with tint: RGB, by amount: Double) -> CIImage {
        let duotone = picture.applyingFilter("CIFalseColor", parameters: [
            "inputColor0": tint.mixed(with: .black, by: 0.35).ciColor(),
            "inputColor1": tint.mixed(with: .white, by: 0.32).ciColor(),
        ])
        return duotone.applyingFilter("CIDissolveTransition", parameters: [
            kCIInputTargetImageKey: picture,
            kCIInputTimeKey: 1 - amount,
        ])
    }

    /// Blends are worked out in sRGB's own curve, as design tools blend, so a fade from a
    /// colour to the page looks even rather than dark in the middle.
    private static let outputSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

    private static let context = CIContext(options: [
        .workingColorSpace: CGColorSpace(name: CGColorSpace.extendedSRGB) ?? CGColorSpaceCreateDeviceRGB(),
        .workingFormat: CIFormat.RGBAh,
        .cacheIntermediates: false,
        .name: "Cover Blend",
    ])
}
