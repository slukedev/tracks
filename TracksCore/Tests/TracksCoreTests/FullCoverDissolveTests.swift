import Testing
import Foundation
@testable import TracksCore

@Suite("Full Cover's dissolve")
struct FullCoverDissolveTests {
    @Test("It dissolves below the subject, from 82% at the highest; a subject running to the edge is crossed gently from 72%")
    func startFollowsTheSubject() {
        #expect(FullCoverDissolve.start(subjectEnd: nil) == 0.82)
        #expect(FullCoverDissolve.start(subjectEnd: 0.3) == 0.82)
        #expect(abs(FullCoverDissolve.start(subjectEnd: 0.85) - 0.88) < 1e-9)
        #expect(FullCoverDissolve.start(subjectEnd: 0.9) == 0.9)
        #expect(FullCoverDissolve.start(subjectEnd: 1) == 0.72)
        #expect(FullCoverDissolve.start(subjectEnd: .infinity) == 0.82)
    }

    @Test("Under the words it dims (or lightens) just enough for them to read, and not at all where they read already", arguments: [false, true])
    func dimmingIsJustEnough(increasedContrast: Bool) {
        let target = increasedContrast ? 7.0 : 4.5
        #expect(FullCoverDissolve.dimming(under: (0.04, 0.01), ink: .light, increasedContrast: increasedContrast) == 0)
        #expect(FullCoverDissolve.dimming(under: (0.95, 0.8), ink: .dark, increasedContrast: increasedContrast) == 0)
        #expect(FullCoverDissolve.dimming(under: nil, ink: .light, increasedContrast: increasedContrast) == 0)
        for (under, ink) in [((0.6, 0.3), CoverBleed.Ink.light), ((0.5, 0.12), .dark)] as [((Double, Double), CoverBleed.Ink)] {
            let amount = FullCoverDissolve.dimming(under: (under.0, under.1), ink: ink, increasedContrast: increasedContrast)
            #expect(amount > 0 && amount < 1)
            let worst = ink == .light ? under.0 : under.1
            #expect(FullCoverDissolve.reads(FullCoverDissolve.veiled(worst, by: amount, toward: ink), ink: ink, target: target))
            #expect(!FullCoverDissolve.reads(FullCoverDissolve.veiled(worst, by: amount - 0.02, toward: ink), ink: ink, target: target))
        }
    }

    @Test("The extremes are read from the region asked for, a stray pixel left out")
    func extremes() throws {
        let side = 20
        var rgba = [UInt8](repeating: 0, count: side * side * 4)
        for y in 0..<side { for x in 0..<side {
            let value: UInt8 = y >= 10 ? 200 : 20
            let index = (y * side + x) * 4
            (rgba[index], rgba[index + 1], rgba[index + 2], rgba[index + 3]) = (value, value, value, 255)
        }}
        let lower = try #require(FullCoverDissolve.extremes(rgba: rgba, side: side, region: CGRect(x: 0, y: 0.5, width: 1, height: 0.5)))
        #expect(abs(lower.lightest - lower.darkest) < 1e-9)
        #expect(lower.lightest > 0.5)
    }
}
