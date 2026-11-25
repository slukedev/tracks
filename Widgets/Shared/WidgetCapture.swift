import Foundation
import WidgetKit
import TracksCore

/// One capture, reduced to what a widget can draw.
///
/// Artwork is `Data` because a widget can't load images while rendering; the entry has to
/// arrive complete.
struct WidgetCapture: Identifiable, Sendable, Hashable {
    let id: String
    let title: String
    let artistName: String
    let capturedAt: Date
    let artwork: Data?
    /// Where it played from: Spotify's plays carry its mark.
    var source: PlaySource = .appleMusic
    /// Only worked out for a song shown on its own, over its colour.
    var coverColor: CoverColor?

    init(snapshot: CaptureSnapshot, artwork: Data?) {
        self.id = snapshot.id
        self.title = snapshot.title
        self.artistName = snapshot.artistName
        self.capturedAt = snapshot.capturedAt
        self.artwork = artwork
        self.source = snapshot.source
    }

    init(id: String, title: String, artistName: String, capturedAt: Date, artwork: Data? = nil) {
        self.id = id
        self.title = title
        self.artistName = artistName
        self.capturedAt = capturedAt
        self.artwork = artwork
    }

    /// This song with its cover's colour, for a widget drawn over it.
    func withCoverColor() -> WidgetCapture {
        var copy = self
        copy.coverColor = artwork.flatMap(CoverColor.init(artwork:))
        return copy
    }
}

/// What the timeline hands to the views.
struct CaptureEntry: TimelineEntry {
    let date: Date
    /// The most recent song kept, from any day. What Last Played shows.
    let lastPlayed: WidgetCapture?
    /// Today's listening, newest first, as many as fit.
    let today: [WidgetCapture]
    /// Tells "nothing today" apart from "couldn't read the database".
    let isStoreReadable: Bool
}

extension CaptureEntry {
    /// This entry as it stands once the day turns over at `midnight`: nothing played yet
    /// today. The last song played is still the last.
    func startingNewDay(at midnight: Date) -> CaptureEntry {
        CaptureEntry(
            date: midnight,
            lastPlayed: lastPlayed,
            today: [],
            isStoreReadable: isStoreReadable
        )
    }

    /// The shape of a real entry, for the system to draw redacted while the real one loads.
    static func placeholder(capacity: WidgetCapacity) -> CaptureEntry {
        let songs = (0..<max(capacity.today, 1)).map { index in
            WidgetCapture(
                id: "placeholder-\(index)",
                title: "Song Title",
                artistName: "Artist Name",
                capturedAt: .now.addingTimeInterval(Double(index) * -600)
            )
        }
        return CaptureEntry(
            date: .now,
            lastPlayed: songs.first,
            today: Array(songs.prefix(capacity.today)),
            isStoreReadable: true
        )
    }

    /// A made-up afternoon of listening for the widget gallery and previews, shown instead
    /// of an empty store, as Apple's own widgets do.
    static func sample(capacity: WidgetCapacity) -> CaptureEntry {
        let today = sampleSongs(
            [
                ("Northern Lights", "The Lanterns"),
                ("Paper Planes at Dusk", "Mira Vale"),
                ("Slow Burn", "Harbour Club"),
                ("Tidewater", "June Atlas"),
                ("Glasshouse", "Ocean Avenue"),
                ("Long Way Home", "The Lanterns"),
            ],
            prefix: "sample-today",
            firstHue: 0.02
        )
        return CaptureEntry(
            date: .now,
            lastPlayed: today.first?.withCoverColor(),
            today: Array(today.prefix(capacity.today)),
            isStoreReadable: true
        )
    }

    /// Each song gets its own colour, stepping round the wheel from `firstHue`.
    private static func sampleSongs(
        _ songs: [(String, String)],
        prefix: String,
        firstHue: Double
    ) -> [WidgetCapture] {
        songs.enumerated().map { index, song in
            WidgetCapture(
                id: "\(prefix)-\(index)",
                title: song.0,
                artistName: song.1,
                capturedAt: .now.addingTimeInterval(Double(index) * -240),
                artwork: SampleArtwork.cover(
                    hue: (firstHue + Double(index) * 0.13).truncatingRemainder(dividingBy: 1)
                )
            )
        }
    }
}
