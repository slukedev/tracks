import Foundation

public nonisolated struct CoverFrame: Equatable, Sendable {
    public var top: Double
    public var leading: Double
    public var bottom: Double
    public var trailing: Double

    public init(top: Double, leading: Double, bottom: Double, trailing: Double) {
        self.top = top
        self.leading = leading
        self.bottom = bottom
        self.trailing = trailing
    }

    public init?(rgba: [UInt8], side: Int) {
        guard side >= 16, rgba.count == side * side * 4 else { return nil }
        func colour(_ x: Int, _ y: Int) -> Oklab {
            let index = (y * side + x) * 4
            return Oklab(red: Double(rgba[index]) / 255, green: Double(rgba[index + 1]) / 255, blue: Double(rgba[index + 2]) / 255)
        }
        let ring = (0..<side).flatMap { [colour($0, 0), colour($0, side - 1), colour(0, $0), colour(side - 1, $0)] }
        let border = Oklab(
            lightness: ring.map(\.lightness).sorted()[ring.count / 2],
            a: ring.map(\.a).sorted()[ring.count / 2],
            b: ring.map(\.b).sorted()[ring.count / 2]
        )
        func isBorder(_ pixel: Oklab) -> Bool {
            let (l, a, b) = (pixel.lightness - border.lightness, pixel.a - border.a, pixel.b - border.b)
            return (l * l + a * a + b * b).squareRoot() < 0.06
        }
        let deepest = side * 3 / 10
        func depth(_ line: (Int, Int) -> Oklab) -> Int {
            var depth = 0
            while depth < deepest {
                let plain = (0..<side).filter { isBorder(line(depth, $0)) }.count
                guard Double(plain) >= Double(side) * 0.94 else { break }
                depth += 1
            }
            return depth
        }
        let edges = [
            depth { row, x in colour(x, row) },
            depth { column, y in colour(column, y) },
            depth { row, x in colour(x, side - 1 - row) },
            depth { column, y in colour(side - 1 - column, y) },
        ]
        let least = max(2, side / 50)
        guard edges.allSatisfy({ $0 >= least && $0 < deepest }) else { return nil }
        let inner = [
            (0..<side).map { colour($0, edges[0]) },
            (0..<side).map { colour(edges[1], $0) },
            (0..<side).map { colour($0, side - 1 - edges[2]) },
            (0..<side).map { colour(side - 1 - edges[3], $0) },
        ]
        guard inner.allSatisfy({ line in Double(line.filter { !isBorder($0) }.count) >= Double(side) * 0.5 }) else { return nil }
        let side = Double(side)
        self.init(top: Double(edges[0]) / side, leading: Double(edges[1]) / side, bottom: Double(edges[2]) / side, trailing: Double(edges[3]) / side)
    }
}
