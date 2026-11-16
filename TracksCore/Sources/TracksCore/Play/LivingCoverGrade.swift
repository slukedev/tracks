import Foundation

/// How Living Cover colours a cover, worked out once from its pixels and applied by the
/// shader to every one it draws: in Oklab, so lightness, colour and hue move separately and
/// a darkened colour keeps the hue the eye sees.
///
/// The cover's own range of lightness is moved into a band chosen for it: a white cover sits
/// light and a black one dark, both with room to show their flow, and white type always
/// reads. A cover with almost no range of its own, one flat colour, is given a texture of
/// its own colour so the flow still shows. Greys stay grey. Warm colours that would turn to
/// mud as they darken are kept from it: a pale beige becomes a warm grey rather than brown,
/// and a yellow leans to gold rather than olive.
///
/// `shade` is the shader's arithmetic, so the rules can be checked here.
public nonisolated struct LivingCoverGrade: Equatable, Sendable {
    /// The cover's darkest and lightest, ignoring the extremes, as Oklab lightness.
    public var sourceLow: Double
    public var sourceHigh: Double
    /// Where they land.
    public var low: Double
    public var high: Double
    /// How much richer its colours are drawn.
    public var chroma: Double
    /// How much texture of its own a flat cover is given, 0 to 1.
    public var texture: Double

    public init(sourceLow: Double, sourceHigh: Double, low: Double, high: Double, chroma: Double, texture: Double) {
        self.sourceLow = sourceLow
        self.sourceHigh = sourceHigh
        self.low = low
        self.high = high
        self.chroma = chroma
        self.texture = texture
    }

    public static let neutral = LivingCoverGrade(sourceLow: 0, sourceHigh: 1, low: 0.2, high: 0.5, chroma: 1, texture: 0)

    /// The lightest white type stays readable on: about 4.5 to 1 against it.
    public static let readableCeiling = 0.57
    /// Under the song's name and the controls: about 5.5 to 1.
    public static let textCeiling = 0.52
    /// The darkest it goes, so the flow still shows on a black cover.
    public static let floor = 0.1

    /// From a cover's pixels, sRGB, four bytes each, alpha last.
    public init(rgba: [UInt8]) {
        let count = rgba.count / 4
        guard count > 0 else {
            self = .neutral
            return
        }
        var lightness: [Double] = []
        var chromas: [Double] = []
        lightness.reserveCapacity(count)
        chromas.reserveCapacity(count)
        for index in 0..<count {
            let lab = Oklab(red: Double(rgba[index * 4]) / 255, green: Double(rgba[index * 4 + 1]) / 255, blue: Double(rgba[index * 4 + 2]) / 255)
            lightness.append(lab.lightness)
            chromas.append(lab.chroma)
        }
        let mean = lightness.reduce(0, +) / Double(count)
        lightness.sort()
        chromas.sort()
        let sourceLow = lightness[count / 20]
        let sourceHigh = lightness[count - 1 - count / 20]
        let spread = sourceHigh - sourceLow
        let vivid = chromas[count - 1 - count / 10]

        let centre = 0.2 + 0.36 * mean
        let range = min(max(spread * 0.65, 0.12), 0.3)
        let low = min(max(Self.floor + 0.02, centre - range / 2), Self.readableCeiling + 0.08 - range)
        self.init(
            sourceLow: sourceLow,
            sourceHigh: sourceHigh,
            low: low,
            high: low + range,
            chroma: vivid < 0.035 ? 0.5 : 1.12,
            texture: 1 - Self.smoothstep(0.04, 0.18, spread)
        )
    }

    /// A colour of the cover as it's drawn, under `ceiling`, its lightness nudged by
    /// `structure`, -0.5 to 0.5, where the flow folds: only felt on a flat cover.
    public func shade(_ colour: Oklab, ceiling: Double, structure: Double = 0) -> Oklab {
        let t = min(max((colour.lightness - sourceLow) / max(sourceHigh - sourceLow, 0.05), -0.25), 1.25)
        var lightness = low + (high - low) * t
        lightness += texture * (high - low) * 1.2 * structure

        var chroma = colour.chroma * self.chroma
        var hue = colour.hue
        let degrees = hue * 180 / .pi
        let darkening = Self.smoothstep(0.8, 0.35, lightness)
        let yellow = Self.bump(degrees, centre: 105, width: 25) * darkening
        hue -= yellow * 38 * .pi / 180
        let muddy = Self.bump(degrees, centre: 70, width: 45) * (1 - Self.smoothstep(0.04, 0.1, colour.chroma)) * darkening
        chroma *= 1 - 0.6 * muddy
        chroma = min(chroma, 0.26)

        let knee = ceiling - 0.1
        if lightness > knee {
            lightness = knee + 0.1 * (1 - exp(-(lightness - knee) / 0.1))
        }
        lightness = max(Self.floor, lightness)
        return Oklab(lightness: lightness, chroma: chroma, hue: hue).inGamut
    }

    /// The colour drawn for a cover's sRGB colour.
    public func shade(red: Double, green: Double, blue: Double, ceiling: Double) -> (red: Double, green: Double, blue: Double) {
        shade(Oklab(red: red, green: green, blue: blue), ceiling: ceiling).srgb
    }

    static func smoothstep(_ edge0: Double, _ edge1: Double, _ x: Double) -> Double {
        let t = min(max((x - edge0) / (edge1 - edge0), 0), 1)
        return t * t * (3 - 2 * t)
    }

    static func bump(_ degrees: Double, centre: Double, width: Double) -> Double {
        var distance = abs(degrees - centre).truncatingRemainder(dividingBy: 360)
        if distance > 180 { distance = 360 - distance }
        return 1 - smoothstep(0, width, distance)
    }
}

/// A colour in Oklab: lightness 0 to 1, and how far and which way it is from grey.
public nonisolated struct Oklab: Equatable, Sendable {
    public var lightness: Double
    public var a: Double
    public var b: Double

    public init(lightness: Double, a: Double, b: Double) {
        self.lightness = lightness
        self.a = a
        self.b = b
    }

    public init(lightness: Double, chroma: Double, hue: Double) {
        self.init(lightness: lightness, a: chroma * cos(hue), b: chroma * sin(hue))
    }

    /// From sRGB, each 0 to 1.
    public init(red: Double, green: Double, blue: Double) {
        let (r, g, b) = (Self.linear(red), Self.linear(green), Self.linear(blue))
        let l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        let m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        let s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        self.init(
            lightness: 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            a: 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            b: 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        )
    }

    public var chroma: Double { (a * a + b * b).squareRoot() }
    public var hue: Double { atan2(b, a) }

    public var linearRGB: (red: Double, green: Double, blue: Double) {
        let l = pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3)
        let m = pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3)
        let s = pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3)
        return (
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        )
    }

    public var srgb: (red: Double, green: Double, blue: Double) {
        let (r, g, b) = linearRGB
        return (Self.encoded(r), Self.encoded(g), Self.encoded(b))
    }

    /// The same lightness and hue, its chroma brought in until sRGB can show it.
    public var inGamut: Oklab {
        func fits(_ colour: Oklab) -> Bool {
            let (r, g, b) = colour.linearRGB
            return [r, g, b].allSatisfy { $0 >= -0.0005 && $0 <= 1.0005 }
        }
        if fits(self) { return self }
        var (inside, outside) = (0.0, 1.0)
        for _ in 0..<12 {
            let middle = (inside + outside) / 2
            if fits(Oklab(lightness: lightness, a: a * middle, b: b * middle)) { inside = middle } else { outside = middle }
        }
        return Oklab(lightness: lightness, a: a * inside, b: b * inside)
    }

    /// Relative luminance, as contrast ratios use it.
    public var luminance: Double {
        let (r, g, b) = linearRGB
        return 0.2126 * min(max(r, 0), 1) + 0.7152 * min(max(g, 0), 1) + 0.0722 * min(max(b, 0), 1)
    }

    static func linear(_ channel: Double) -> Double {
        channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
    }

    static func encoded(_ channel: Double) -> Double {
        let c = min(max(channel, 0), 1)
        return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1 / 2.4) - 0.055
    }
}
