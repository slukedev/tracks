import Testing
import Foundation
@testable import TracksCore

@Suite("Music's position")
struct MusicPositionTests {
    @Test("Reads seconds with either decimal mark, and nothing that isn't a time")
    func seconds() {
        #expect(PlayerPresence.seconds("212.5") == 212.5)
        #expect(PlayerPresence.seconds("212,5") == 212.5)
        #expect(PlayerPresence.seconds(" 30 ") == 30)
        for bad in [nil, "", "missing value", "-3", "nan", "inf"] as [String?] {
            #expect(PlayerPresence.seconds(bad) == nil, "\(bad ?? "nil")")
        }
    }
}
