import Foundation

/// How Full Cover's sharp cover gives way to the same cover blurred behind it, and how far
/// that blurred cover is dimmed under the song's words.
public nonisolated enum FullCoverDissolve {
    /// Where the sharp cover starts to dissolve, as a part of its side from its top (or from its
    /// leading edge, beside the song): at 82% of the way, or lower, up to 88%, to keep what it's
    /// of, `subjectEnd`, sharp, so the cover reads whole and its last eighth or so dissolves.
    public static func start(subjectEnd: Double?) -> Double {
        guard let subjectEnd, subjectEnd.isFinite else { return earliest }
        return min(latest, max(earliest, subjectEnd + 0.02))
    }

    static let earliest = 0.82
    static let latest = 0.88

    /// The blurred cover is a little richer and brighter than the sharp one, as glass is.
    public static let saturation = 1.15
    public static let brightness = 0.03

    /// How strongly the blurred cover is dimmed (black at that opacity, under white type) or
    /// lightened (white, under dark) where the words are, for the lightest (or darkest) of
    /// what's there, `under`, as luminance, to read at 4.5 to 1, or 7 to 1 with Increase
    /// Contrast. Nothing where it reads already.
    public static func dimming(under: (lightest: Double, darkest: Double)?, ink: CoverBleed.Ink, increasedContrast: Bool) -> Double {
        guard let under else { return 0 }
        let target = increasedContrast ? 7.0 : 4.5
        let worst = ink == .light ? under.lightest : under.darkest
        guard !reads(worst, ink: ink, target: target) else { return 0 }
        var (low, high) = (0.0, 1.0)
        for _ in 0..<16 {
            let amount = (low + high) / 2
            if reads(veiled(worst, by: amount, toward: ink), ink: ink, target: target) { high = amount } else { low = amount }
        }
        return high
    }

    static func reads(_ luminance: Double, ink: CoverBleed.Ink, target: Double) -> Bool {
        switch ink {
        case .light: 1.05 / (luminance + 0.05) >= target
        case .dark: (luminance + 0.05) / (darkInk + 0.05) >= target
        }
    }

    /// The luminance of the dark type: the system's near black.
    static let darkInk = 0.0

    /// A luminance under a layer of black (under light type) or white (under dark) at `amount`
    /// opacity, as sRGB layers blend.
    static func veiled(_ luminance: Double, by amount: Double, toward ink: CoverBleed.Ink) -> Double {
        let encoded = CoverBleed.Colour.gamma(luminance)
        let blended = ink == .light ? encoded * (1 - amount) : encoded + (1 - encoded) * amount
        return CoverBleed.Colour.linear(blended)
    }

    /// The lightest and darkest luminance of a picture's pixels (RGBA, `side` square) inside
    /// `region` (parts of the side, from the top left), a hundredth either way left out.
    public static func extremes(rgba: [UInt8], side: Int, region: CGRect) -> (lightest: Double, darkest: Double)? {
        guard side > 0, rgba.count >= side * side * 4 else { return nil }
        let columns = max(0, Int(region.minX * Double(side)))..<min(side, Int((region.maxX * Double(side)).rounded(.up)))
        let rows = max(0, Int(region.minY * Double(side)))..<min(side, Int((region.maxY * Double(side)).rounded(.up)))
        var values: [Double] = []
        for y in rows {
            for x in columns {
                let index = (y * side + x) * 4
                values.append(CoverBleed.Colour(red: Double(rgba[index]) / 255, green: Double(rgba[index + 1]) / 255, blue: Double(rgba[index + 2]) / 255).luminance)
            }
        }
        guard !values.isEmpty else { return nil }
        values.sort()
        return (values[values.count - 1 - values.count / 100], values[values.count / 100])
    }

    /// A luminance as the blurred cover shows it, `brightness` lighter.
    public static func brightened(_ luminance: Double) -> Double {
        CoverBleed.Colour.linear(min(1, CoverBleed.Colour.gamma(luminance) + brightness))
    }

    public static func smootherstep(_ t: Double) -> Double {
        let t = min(1, max(0, t))
        return t * t * t * (t * (6 * t - 15) + 10)
    }
}
