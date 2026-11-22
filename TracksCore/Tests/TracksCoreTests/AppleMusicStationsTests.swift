import Testing
@testable import TracksCore

/// The stations Settings offers a switch for.
@Suite("Apple Music stations")
struct AppleMusicStationsTests {
    @Test("only the year-round live stations, in Apple's order, before anything is heard")
    func liveStationsOnly() {
        let choices = AppleMusicStations.choices(heard: [], excluded: [])
        #expect(choices == [
            "Apple Music 1",
            "Apple Music Hits",
            "Apple Music Country",
            "Apple Música Uno",
            "Apple Music Club",
            "Apple Music Chill",
        ])
    }

    @Test("stations that aren't Apple Music's get no switch, heard or excluded")
    func otherStationsLeftOut() {
        let choices = AppleMusicStations.choices(
            heard: ["Kendrick Lamar Station", "Apple Music Chill", "Focus"],
            excluded: ["Pop Station"]
        )
        #expect(choices == AppleMusicStations.live)
    }

    @Test("a seasonal station joins after Apple Music 1 once heard")
    func seasonalOnceHeard() {
        let choices = AppleMusicStations.choices(heard: ["Holiday Hits"], excluded: [])
        #expect(choices.prefix(2) == ["Apple Music 1", "Holiday Hits"])
        #expect(choices.count == AppleMusicStations.live.count + 1)
    }

    @Test("a seasonal station that's off keeps its switch")
    func excludedSeasonalKeepsSwitch() {
        let choices = AppleMusicStations.choices(heard: [], excluded: ["Holiday Hits"])
        #expect(choices.contains("Holiday Hits"))
    }
}
