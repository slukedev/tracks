import Foundation

/// The colours of a page that takes its colour from a cover, as Music's album and artist pages
/// do: a shade of the cover's own colour for the page, and type, hairlines and an accent that
/// read on it.
///
/// The shade is the cover's colour made pleasing, not a sampled pixel: its saturation is held
/// below neon, a grey cover gives a neutral page, and its lightness is moved until the type on
/// it passes WCAG. Primary type reaches 7:1, secondary 4.5:1 and tertiary 3:1.
public struct PageTone: Sendable, Equatable {
    public struct Colour: Sendable, Equatable {
        public var red: Double
        public var green: Double
        public var blue: Double

        public init(red: Double, green: Double, blue: Double) {
            self.red = red
            self.green = green
            self.blue = blue
        }

        public static let white = Colour(red: 1, green: 1, blue: 1)
        public static let black = Colour(red: 0, green: 0, blue: 0)
    }

    public enum Appearance: Sendable {
        case light, dark
    }

    public let background: Colour
    /// True when the page is dark and its type is white.
    public let isDark: Bool
    public let primary: Colour
    public let secondary: Colour
    public let tertiary: Colour
    /// Row separators and other rules on the page.
    public let hairline: Colour
    /// The cover's hue, light or deep enough to read as text on the page: for the song that's
    /// playing and for links. The primary colour on a neutral page.
    public let accent: Colour

    static let primaryContrast = 7.0
    static let secondaryContrast = 4.5
    static let tertiaryContrast = 3.0
    /// Below this much difference between its channels a colour reads as grey.
    static let neutralChroma = 0.08
    static let maximumSaturation = 0.62
    static let darkLuminance = 0.01...0.1
    /// Deeper in Dark Mode, so a page sits with the dark pages around it rather than glowing.
    static let darkModeLuminance = 0.01...0.06
    static let lightLuminance = 0.42...0.88
    /// In light appearance, a cover at least this bright gives a light page.
    static let lightPageThreshold = 0.4

    /// The page for a cover whose average colour is `colour`. `coverage` is how much of the
    /// cover is opaque: a mostly transparent cover has no colour worth a page, and gives nil.
    public init?(cover colour: Colour, coverage: Double = 1, appearance: Appearance) {
        guard coverage >= 0.5 else { return nil }
        let cover = colour.clamped
        var hsl = HSL(cover)
        let isNeutral = cover.chroma < Self.neutralChroma
        hsl.saturation = isNeutral ? 0 : min(Self.maximumSaturation, hsl.saturation * 1.25)

        let coverLuminance = cover.luminance
        let isDark = appearance == .dark || coverLuminance < Self.lightPageThreshold
        let range = appearance == .dark ? Self.darkModeLuminance : isDark ? Self.darkLuminance : Self.lightLuminance
        if isDark, !isNeutral {
            hsl.hue = Self.amberLean(hsl.hue)
        }
        let background = hsl.adjusted(toLuminance: min(range.upperBound, max(range.lowerBound, coverLuminance)))

        let ink: Colour = isDark ? .white : .black
        self.background = background
        self.isDark = isDark
        self.primary = ink
        self.secondary = Self.faded(ink, over: background, from: 0.6, reaching: Self.secondaryContrast)
        self.tertiary = Self.faded(ink, over: background, from: 0.35, reaching: Self.tertiaryContrast)
        self.hairline = ink.blended(over: background, alpha: 0.14)
        self.accent = isNeutral ? ink : Self.accent(hue: hsl.hue, on: background, isDark: isDark)
    }

    /// WCAG relative luminance of an sRGB colour.
    public static func luminance(_ colour: Colour) -> Double {
        colour.luminance
    }

    /// WCAG contrast ratio between two colours, from 1 to 21.
    public static func contrast(_ a: Colour, _ b: Colour) -> Double {
        let (light, dark) = (max(a.luminance, b.luminance), min(a.luminance, b.luminance))
        return (light + 0.05) / (dark + 0.05)
    }

    /// `ink` over the page at the least opacity, from `start` up, that reaches `ratio`, so
    /// each level is as quiet as it can be and still read.
    private static func faded(_ ink: Colour, over background: Colour, from start: Double, reaching ratio: Double) -> Colour {
        var alpha = start
        var colour = ink.blended(over: background, alpha: alpha)
        while contrast(colour, background) < ratio, alpha < 1 {
            alpha = min(1, alpha + 0.02)
            colour = ink.blended(over: background, alpha: alpha)
        }
        return colour
    }

    private static func accent(hue: Double, on background: Colour, isDark: Bool) -> Colour {
        var hsl = HSL(hue: hue, saturation: 0.75, lightness: isDark ? 0.62 : 0.4)
        var colour = hsl.colour
        while contrast(colour, background) < secondaryContrast, hsl.lightness > 0, hsl.lightness < 1 {
            hsl.lightness += isDark ? 0.02 : -0.02
            colour = hsl.colour
        }
        return colour
    }

    /// Yellow deepened keeping its hue turns olive. Leaned toward amber, it stays gold.
    private static func amberLean(_ hue: Double) -> Double {
        let yellow = 60.0 / 360, amber = 38.0 / 360, reach = 25.0 / 360
        let distance = abs(hue - yellow)
        guard distance < reach else { return hue }
        return hue + (amber - hue) * (1 - distance / reach) * 0.8
    }
}

extension PageTone.Colour {
    var clamped: Self {
        Self(red: red.unit, green: green.unit, blue: blue.unit)
    }

    var chroma: Double {
        max(red, green, blue) - min(red, green, blue)
    }

    var luminance: Double {
        func linear(_ channel: Double) -> Double {
            channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red.unit) + 0.7152 * linear(green.unit) + 0.0722 * linear(blue.unit)
    }

    func blended(over background: Self, alpha: Double) -> Self {
        Self(
            red: red * alpha + background.red * (1 - alpha),
            green: green * alpha + background.green * (1 - alpha),
            blue: blue * alpha + background.blue * (1 - alpha)
        )
    }
}

private extension Double {
    var unit: Double { Swift.min(1, Swift.max(0, self)) }
}

/// Hue, saturation and lightness, each 0 to 1.
private struct HSL {
    var hue: Double
    var saturation: Double
    var lightness: Double

    init(hue: Double, saturation: Double, lightness: Double) {
        self.hue = hue
        self.saturation = saturation
        self.lightness = lightness
    }

    init(_ colour: PageTone.Colour) {
        let high = max(colour.red, colour.green, colour.blue)
        let low = min(colour.red, colour.green, colour.blue)
        let chroma = high - low
        lightness = (high + low) / 2
        saturation = chroma == 0 ? 0 : chroma / (1 - abs(2 * lightness - 1))
        let sector: Double = switch high {
        case _ where chroma == 0: 0
        case colour.red: ((colour.green - colour.blue) / chroma).truncatingRemainder(dividingBy: 6)
        case colour.green: (colour.blue - colour.red) / chroma + 2
        default: (colour.red - colour.green) / chroma + 4
        }
        hue = ((sector / 6).truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1)
    }

    var colour: PageTone.Colour {
        let lightness = min(1, max(0, lightness))
        let chroma = (1 - abs(2 * lightness - 1)) * min(1, max(0, saturation))
        let sector = hue * 6
        let x = chroma * (1 - abs(sector.truncatingRemainder(dividingBy: 2) - 1))
        let (r, g, b): (Double, Double, Double) = switch Int(sector) % 6 {
        case 0: (chroma, x, 0)
        case 1: (x, chroma, 0)
        case 2: (0, chroma, x)
        case 3: (0, x, chroma)
        case 4: (x, 0, chroma)
        default: (chroma, 0, x)
        }
        let low = lightness - chroma / 2
        return PageTone.Colour(red: r + low, green: g + low, blue: b + low)
    }

    /// The same hue and saturation at the lightness that gives `target` luminance. Luminance
    /// rises with lightness, so a bisection finds it.
    func adjusted(toLuminance target: Double) -> PageTone.Colour {
        var (low, high) = (0.0, 1.0)
        var probe = self
        for _ in 0..<32 {
            probe.lightness = (low + high) / 2
            if probe.colour.luminance < target {
                low = probe.lightness
            } else {
                high = probe.lightness
            }
        }
        probe.lightness = low
        return probe.colour
    }
}
