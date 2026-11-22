import Foundation

/// Apple Music's live radio stations, the ones every subscriber can tune in to.
public enum AppleMusicStations {
    /// In the order the stations list shows them. Spelled as the platform reports them
    /// ("Apple Música Uno", with the accent), since an exclusion only matches the exact name.
    public static let live = [
        "Apple Music 1",
        "Apple Music Hits",
        "Apple Music Country",
        "Apple Música Uno",
        "Apple Music Club",
        "Apple Music Chill",
    ]

    /// Live stations Apple Music airs only part of the year.
    public static let seasonal = [
        "Holiday Hits",
    ]

    /// Every live station in the order they're shown: Apple Music 1, any seasonal station,
    /// then the rest. The radio shelf looks these up and shows the ones on air.
    public static var all: [String] {
        Array(live.prefix(1)) + seasonal + live.dropFirst()
    }

    /// The stations Settings offers switches for: only Apple Music's live ones. A seasonal
    /// station joins once Tracks has heard it, or while it's off, so it can be turned back on.
    public static func choices(heard: Set<String>, excluded: Set<String>) -> [String] {
        all.filter { !seasonal.contains($0) || heard.contains($0) || excluded.contains($0) }
    }
}
