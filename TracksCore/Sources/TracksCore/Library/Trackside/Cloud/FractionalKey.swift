import Foundation

/// Order keys that sort as text, with room between any two: a new key between neighbours
/// never moves either of them.
public enum FractionalKey {
    public static let digits = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

    /// A key after `low` and before `high`; nil for either end. `low` must sort before `high`.
    public static func between(_ low: String?, _ high: String?) -> String {
        midpoint(Array(low ?? ""), high.map(Array.init))
    }

    private static func index(_ character: Character) -> Int {
        digits.firstIndex(of: character) ?? 0
    }

    private static func midpoint(_ low: [Character], _ high: [Character]?) -> String {
        if let high {
            // A shared start is kept, and the rest worked out after it.
            var shared = 0
            while shared < high.count, (shared < low.count ? low[shared] : digits[0]) == high[shared] {
                shared += 1
            }
            if shared > 0 {
                let rest = midpoint(Array(low.dropFirst(min(shared, low.count))), Array(high.dropFirst(shared)))
                return String(high.prefix(shared)) + rest
            }
        }
        let lowDigit = low.first.map(index) ?? 0
        let highDigit = high?.first.map(index) ?? digits.count
        if highDigit - lowDigit > 1 {
            return String(digits[(lowDigit + highDigit) / 2])
        }
        if let high, high.count > 1 {
            return String(high[0])
        }
        return String(digits[lowDigit]) + midpoint(Array(low.dropFirst()), nil)
    }
}
