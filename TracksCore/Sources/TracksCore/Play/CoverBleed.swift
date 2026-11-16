import Foundation
import CoreGraphics

/// How a cover carries on past its own edges when it fills the player, as Music's full-bleed
/// artwork does: each edge's colours, column by column, drawn on out to the screen's edge and
/// settling into one colour, with the type on it light or dark to suit.
///
/// Read from the cover's pixels once, small. Each edge is a band a few pixels deep, the rows
/// nearest the edge counting most, averaged into a row of stops. A plain edge (a sky, a wall,
/// a studio backdrop) keeps its stops as they are, so the cover simply goes on; a busy one
/// (lettering, a crowd, a photo cut off mid-face) is smoothed toward its own average, so it
/// carries on as calm light rather than as streaks.
public nonisolated struct CoverBleed: Sendable, Equatable {
    /// An sRGB colour, each part 0 to 1.
    public struct Colour: Sendable, Equatable {
        public var red: Double
        public var green: Double
        public var blue: Double

        public init(red: Double, green: Double, blue: Double) {
            self.red = red
            self.green = green
            self.blue = blue
        }

        /// Relative luminance, as WCAG measures it.
        public var luminance: Double {
            0.2126 * Self.linear(red) + 0.7152 * Self.linear(green) + 0.0722 * Self.linear(blue)
        }

        /// The contrast of type in `other` on this, from 1 to 21.
        public func contrast(with other: Colour) -> Double {
            let (a, b) = (luminance, other.luminance)
            return (max(a, b) + 0.05) / (min(a, b) + 0.05)
        }

        static func linear(_ value: Double) -> Double {
            value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }

        static func gamma(_ value: Double) -> Double {
            let value = min(1, max(0, value))
            return value <= 0.0031308 ? value * 12.92 : 1.055 * pow(value, 1 / 2.4) - 0.055
        }

        var linear: (Double, Double, Double) { (Self.linear(red), Self.linear(green), Self.linear(blue)) }

        init(linear: (Double, Double, Double)) {
            self.init(red: Self.gamma(linear.0), green: Self.gamma(linear.1), blue: Self.gamma(linear.2))
        }

        public static let white = Colour(red: 1, green: 1, blue: 1)
        public static let black = Colour(red: 0, green: 0, blue: 0)
    }

    public enum Edge: CaseIterable, Sendable {
        case top, bottom, leading, trailing
    }

    /// The type on a part of the background: white, or near black.
    public enum Ink: Sendable {
        case light, dark
    }

    /// Each edge's colours, calmed: left to right along the top and bottom, top to bottom
    /// down the sides.
    public let top: [Colour]
    public let bottom: [Colour]
    public let leading: [Colour]
    public let trailing: [Colour]
    /// How textured each edge is, 0 to 1, in the order of ``Edge/allCases``: 0 for a plain
    /// backdrop or a flat colour, rising through a photo's sea or sky to 1 for lettering.
    /// A plain edge simply carries on as its colour; a textured one carries on as a soft
    /// copy of itself first, so it doesn't stop dead into a flat field.
    public let textures: [Double]

    /// Light type on a background whose luminance is at most this. Above it, dark type has
    /// the better of it: at 0.3 black reads at 7:1, white at only 2.6:1.
    static let darkInkAbove = 0.3

    public init(top: [Colour], bottom: [Colour], leading: [Colour], trailing: [Colour], textures: [Double] = [0, 0, 0, 0]) {
        self.top = top
        self.bottom = bottom
        self.leading = leading
        self.trailing = trailing
        self.textures = textures.count == 4 ? textures : [0, 0, 0, 0]
    }

    public func texture(_ edge: Edge) -> Double {
        textures[Edge.allCases.firstIndex(of: edge) ?? 0]
    }

    /// Reads a cover from its RGBA pixels, `side` square, rows from the top. Nil when there
    /// are none to read.
    public init?(rgba: [UInt8], side: Int, stops: Int = 12) {
        guard side >= 4, stops >= 2, rgba.count >= side * side * 4 else { return nil }
        let reader = EdgeReader(rgba: rgba, side: side, stops: stops)
        let read = Edge.allCases.map { reader.read($0) }
        let seen = read.compactMap(\.self)
        guard !seen.isEmpty else { return nil }
        // An edge that's see-through all along carries on in the colour of those that aren't.
        let stand = Array(repeating: Self.average(seen.flatMap(\.self)), count: stops)
        self.init(top: read[0] ?? stand, bottom: read[1] ?? stand, leading: read[2] ?? stand, trailing: read[3] ?? stand, textures: Edge.allCases.map { reader.texture($0) })
    }

    public func stops(_ edge: Edge) -> [Colour] {
        switch edge {
        case .top: top
        case .bottom: bottom
        case .leading: leading
        case .trailing: trailing
        }
    }

    /// The edge's colour as a whole: the colour most of it is, when most of it is one colour
    /// (a sky either side of a shoulder is the shoulder's, if the shoulder is most of it), and
    /// otherwise all of it averaged in light. An average of two unlike colours is a third
    /// colour that's in neither, often a muddy one.
    public func average(_ edge: Edge) -> Colour {
        Self.dominant(stops(edge))
    }

    /// The type that reads best past this edge.
    public func ink(_ edge: Edge) -> Ink {
        average(edge).luminance > Self.darkInkAbove ? .dark : .light
    }

    /// Most of `colours`, split in two by likeness: the larger part's average when it's at
    /// least three in five of them, else the average of all.
    static func dominant(_ colours: [Colour]) -> Colour {
        guard colours.count > 2 else { return average(colours) }
        let points = colours.map(\.linear)
        func distance(_ a: (Double, Double, Double), _ b: (Double, Double, Double)) -> Double {
            pow(a.0 - b.0, 2) + pow(a.1 - b.1, 2) + pow(a.2 - b.2, 2)
        }
        func mean(_ group: [(Double, Double, Double)]) -> (Double, Double, Double) {
            let count = Double(max(1, group.count))
            return (group.map(\.0).reduce(0, +) / count, group.map(\.1).reduce(0, +) / count, group.map(\.2).reduce(0, +) / count)
        }
        // Started from the two stops furthest apart, so the split follows the edge's real divide.
        var (first, second) = (points[0], points[1])
        var widest = -1.0
        for i in points.indices {
            for j in points.indices where j > i {
                let d = distance(points[i], points[j])
                if d > widest { (widest, first, second) = (d, points[i], points[j]) }
            }
        }
        var inFirst: [Bool] = []
        for _ in 0..<8 {
            inFirst = points.map { distance($0, first) <= distance($0, second) }
            first = mean(zip(points, inFirst).filter { $0.1 }.map(\.0))
            second = mean(zip(points, inFirst).filter { !$0.1 }.map(\.0))
        }
        let firstCount = inFirst.filter { $0 }.count
        let larger = max(firstCount, points.count - firstCount)
        // Two halves that are nearly alike are one colour: no part needs to win.
        guard widest > 0.002, Double(larger) / Double(points.count) >= 0.6 else { return average(colours) }
        return Colour(linear: firstCount >= points.count - firstCount ? first : second)
    }

    static func average(_ colours: [Colour]) -> Colour {
        guard !colours.isEmpty else { return .black }
        var sum = (0.0, 0.0, 0.0)
        for colour in colours {
            let (r, g, b) = colour.linear
            sum = (sum.0 + r, sum.1 + g, sum.2 + b)
        }
        let count = Double(colours.count)
        return Colour(linear: (sum.0 / count, sum.1 / count, sum.2 / count))
    }
}

/// Reads one edge of a cover into stops.
private nonisolated struct EdgeReader {
    let rgba: [UInt8]
    let side: Int
    let stops: Int

    /// How deep a band each edge is read from: a few pixels, so one row of noise or a hairline
    /// border doesn't decide it, and not so deep that what's inside the cover does.
    private var depth: Int { max(2, Int((Double(side) * 0.07).rounded())) }

    /// How much the edge's band varies in lightness around each stretch's own typical
    /// lightness, as the eye has it (L*): the median of how far each pixel is from it, scaled
    /// so 0.1 (a choppy sea, a crowd) is fully textured.
    func texture(_ edge: CoverBleed.Edge) -> Double {
        let skip = side >= 32 ? 1 : 0
        var spreads: [Double] = []
        for stop in 0..<stops {
            let start = stop * side / stops
            let end = max(start + 1, (stop + 1) * side / stops)
            var values: [Double] = []
            for along in start..<end {
                for inward in skip..<(depth + skip) {
                    let (x, y) = position(edge, along: along, inward: inward)
                    let index = (y * side + x) * 4
                    let alpha = Double(rgba[index + 3]) / 255
                    guard alpha > 0.5 else { continue }
                    let luminance = CoverBleed.Colour(red: min(1, Double(rgba[index]) / 255 / alpha), green: min(1, Double(rgba[index + 1]) / 255 / alpha), blue: min(1, Double(rgba[index + 2]) / 255 / alpha)).luminance
                    values.append((luminance > 0.008856 ? 116 * cbrt(luminance) - 16 : 903.3 * luminance) / 100)
                }
            }
            guard !values.isEmpty else { continue }
            values.sort()
            let typical = values[values.count / 2]
            let deviations = values.map { abs($0 - typical) }.sorted()
            spreads.append(deviations[deviations.count / 2])
        }
        guard !spreads.isEmpty else { return 0 }
        return min(1, (spreads.reduce(0, +) / Double(spreads.count)) / 0.1)
    }

    func read(_ edge: CoverBleed.Edge) -> [CoverBleed.Colour]? {
        var raw: [(Double, Double, Double)] = []
        raw.reserveCapacity(stops)
        // The outermost row is often a pixel of border the cover's resizing left, not the
        // cover: it's skipped once there are pixels enough to spare one.
        let skip = side >= 32 ? 1 : 0
        for stop in 0..<stops {
            let start = stop * side / stops
            let end = max(start + 1, (stop + 1) * side / stops)
            var samples: [(r: Double, g: Double, b: Double, weight: Double)] = []
            for along in start..<end {
                for inward in skip..<(depth + skip) {
                    let (x, y) = position(edge, along: along, inward: inward)
                    let index = (y * side + x) * 4
                    let alpha = Double(rgba[index + 3]) / 255
                    guard alpha > 0.02 else { continue }
                    // Premultiplied: undone, so a soft-edged cover isn't read as darker.
                    let r = CoverBleed.Colour.linear(min(1, Double(rgba[index]) / 255 / alpha))
                    let g = CoverBleed.Colour.linear(min(1, Double(rgba[index + 1]) / 255 / alpha))
                    let b = CoverBleed.Colour.linear(min(1, Double(rgba[index + 2]) / 255 / alpha))
                    // The row on the edge counts most, the band's innermost least.
                    samples.append((r, g, b, alpha / Double(1 + inward - skip)))
                }
            }
            // The middle of what's there rather than its average: a title or a face reaching
            // into the band is outvoted by the colour around it instead of greying it.
            raw.append(samples.isEmpty ? (.nan, .nan, .nan) : (
                Self.median(samples.map { ($0.r, $0.weight) }),
                Self.median(samples.map { ($0.g, $0.weight) }),
                Self.median(samples.map { ($0.b, $0.weight) })
            ))
        }
        // A see-through cover's empty stretches take their neighbours' colour.
        let seen = raw.filter { !$0.0.isNaN }
        guard !seen.isEmpty else { return nil }
        let mean = (
            seen.map(\.0).reduce(0, +) / Double(seen.count),
            seen.map(\.1).reduce(0, +) / Double(seen.count),
            seen.map(\.2).reduce(0, +) / Double(seen.count)
        )
        let filled = raw.map { $0.0.isNaN ? mean : $0 }
        return calmed(filled).map(CoverBleed.Colour.init(linear:))
    }

    /// The value with half the weight either side of it.
    private static func median(_ values: [(Double, Double)]) -> Double {
        let sorted = values.sorted { $0.0 < $1.0 }
        let half = sorted.reduce(0) { $0 + $1.1 } / 2
        var seen = 0.0
        for (value, weight) in sorted {
            seen += weight
            if seen >= half { return value }
        }
        return sorted.last?.0 ?? 0
    }

    private func position(_ edge: CoverBleed.Edge, along: Int, inward: Int) -> (x: Int, y: Int) {
        switch edge {
        case .top: (along, inward)
        case .bottom: (along, side - 1 - inward)
        case .leading: (inward, along)
        case .trailing: (side - 1 - inward, along)
        }
    }

    /// Smooths a busy edge across its stops and draws it toward the colour most of it is;
    /// leaves a plain one as it is. Busy is how sharply it turns back and forth from stop to
    /// stop, most of the way along, in the colours as they look: lettering and crowds do, while
    /// one clean change (a sky meeting a wall) doesn't, and keeps its line.
    private func calmed(_ stops: [(Double, Double, Double)]) -> [(Double, Double, Double)] {
        let seen = stops.map { CoverBleed.Colour(linear: $0) }
        var bends: [Double] = []
        for index in seen.indices.dropFirst().dropLast() {
            let (a, b, c) = (seen[index - 1], seen[index], seen[index + 1])
            bends.append(sqrt(pow(a.red - 2 * b.red + c.red, 2) + pow(a.green - 2 * b.green + c.green, 2) + pow(a.blue - 2 * b.blue + c.blue, 2)))
        }
        bends.sort()
        let typical = bends.isEmpty ? 0 : bends[min(bends.count - 1, bends.count * 7 / 10)]
        // 0.05 is a gentle curve; 0.3 is lettering or a crowd.
        let busy = min(1, max(0, (typical - 0.05) / 0.25))
        let sigma = 0.7 + 1.8 * busy
        let reach = Int(ceil(sigma * 2.5))
        let count = stops.count
        var smooth: [(Double, Double, Double)] = []
        for index in 0..<count {
            var sum = (0.0, 0.0, 0.0)
            var weight = 0.0
            for offset in -reach...reach {
                // The edge's ends hold their own colour rather than wrapping or fading.
                let at = min(count - 1, max(0, index + offset))
                let w = exp(-Double(offset * offset) / (2 * sigma * sigma))
                sum = (sum.0 + stops[at].0 * w, sum.1 + stops[at].1 * w, sum.2 + stops[at].2 * w)
                weight += w
            }
            smooth.append((sum.0 / weight, sum.1 / weight, sum.2 / weight))
        }
        let pull = 0.45 * busy
        // Toward the colour most of the edge is, rather than a muddy average of all of it.
        let mean = CoverBleed.dominant(smooth.map(CoverBleed.Colour.init(linear:))).linear
        return smooth.map { stop in
            (stop.0 + (mean.0 - stop.0) * pull, stop.1 + (mean.1 - stop.1) * pull, stop.2 + (mean.2 - stop.2) * pull)
        }
    }
}

/// Where the cover sits in a full-bleed background.
public nonisolated enum CoverBleedLayout {
    /// Which way the player reads from its cover.
    public enum Direction: Sendable {
        /// The song under the cover, as on iPhone.
        case below
        /// The song beside it, as on the Mac.
        case beside
    }

    /// The cover's square, in a container of `size`, for a player that marks where its cover
    /// is with `focus`.
    ///
    /// Under the song, as on iPhone, the whole cover sits at the very top, the container's
    /// width, under the status bar and the top bar, never moved, cropped or inset for what's
    /// on it; the colours carry on below it. Only its foot is cut short, fading out, when the
    /// player's room for it ends above it (see ``shownFrame(of:focus:)``). Beside it, as on
    /// the Mac, the cover reaches back to the screen's edge and runs a little past the player's
    /// own, toward the song, as large as fits: cut off at the far edge only if the player's
    /// cover is close to it.
    ///
    /// Beside, a cover shorter than the window `standsOnFloor` when what it's of runs off its
    /// foot: it sits on the window's bottom edge, rather than in the middle with colour under
    /// a figure cut off at the waist. One that `fillsHeight` is the window's height, top to
    /// bottom, losing a sliver at the window's edge if it must.
    public static func frame(in size: CGSize, focus: CGRect?, direction: Direction, standsOnFloor: Bool = false, fillsHeight: Bool = false) -> CGRect {
        guard size.width > 0, size.height > 0 else { return .zero }
        switch direction {
        case .below:
            return CGRect(x: 0, y: 0, width: size.width, height: size.width)
        case .beside:
            let focus = focus ?? CGRect(x: 0, y: 0, width: size.height * 0.9, height: size.height)
            let end = focus.maxX + size.width * gap
            let side = fillsHeight ? size.height : min(size.height, max(focus.width, end))
            let y = standsOnFloor ? size.height - side : min(max(0, focus.midY - side / 2), size.height - side)
            return CGRect(x: end - side, y: y, width: side, height: side)
        }
    }

    /// The part of the cover that shows: all of it, unless its foot runs past the room under
    /// it, where it ends a little past the room, its fade there taking it out of sight before
    /// the song's name.
    public static func shownFrame(of cover: CGRect, focus: CGRect?) -> CGRect {
        guard let focus, cover.maxY > focus.maxY else { return cover }
        let end = min(cover.maxY, focus.maxY + cover.width * overrun)
        return CGRect(x: cover.minX, y: cover.minY, width: cover.width, height: max(0, end - cover.minY))
    }

    /// How far a cover cut short at its foot shows past the room under it.
    static let overrun = 0.02

    /// How far past the player's cover the full one runs beside it, as a part of the
    /// screen's width.
    static let gap = 0.03

    /// The edge the song's words and the controls sit past.
    public static func edge(for direction: Direction) -> CoverBleed.Edge {
        direction == .below ? .bottom : .trailing
    }
}
