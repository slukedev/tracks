import Foundation

/// The identity of a song across the history: its normalised title and artist.
///
/// Not the catalog ID. The same song turns up under a catalog ID, a library ID, a server's
/// own ID or none at all, and each has to count as the one song.
public enum SongIdentity {
    public static func key(title: String, artistName: String) -> String {
        let normalise = { (value: String) in
            value
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        }
        return "\(normalise(title))\u{1F}\(normalise(artistName))"
    }
}

/// The old name, kept until the callers in Your Music and Play have moved to ``SongIdentity``.
@available(*, deprecated, renamed: "SongIdentity")
public typealias HistoryImport = SongIdentity
