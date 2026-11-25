import Testing
@testable import TracksCore

@Suite struct FractionalKeyTests {
    @Test func firstKeyIsInTheMiddle() {
        #expect(FractionalKey.between(nil, nil) == "V")
    }

    @Test func keysFallBetweenTheirNeighbours() {
        let pairs: [(String?, String?)] = [(nil, "V"), ("V", nil), ("A", "B"), ("A", "BZ"), ("a0V", nil), (nil, "a0V"), ("V", "V1"), ("Az", "B")]
        for (low, high) in pairs {
            let key = FractionalKey.between(low, high)
            if let low { #expect(key > low, "\(key) after \(low)") }
            if let high { #expect(key < high, "\(key) before \(high)") }
        }
    }

    @Test func repeatedInsertsStayOrdered() {
        var keys = [FractionalKey.between(nil, nil)]
        for _ in 0..<200 {
            keys.insert(FractionalKey.between(nil, keys[0]), at: 0)
            keys.append(FractionalKey.between(keys[keys.count - 1], nil))
            let middle = keys.count / 2
            keys.insert(FractionalKey.between(keys[middle - 1], keys[middle]), at: middle)
        }
        #expect(keys == keys.sorted())
        #expect(Set(keys).count == keys.count)
    }
}
