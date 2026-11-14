import Foundation

/// Turns track metadata into an Apple Music catalog song ID.
///
/// Tracks’ Apple Music player gives the ID with every song, so this is for rows that arrived
/// without one, such as older Mac history. It's declared here, without MusicKit, so the capture
/// pipeline can be tested without a network or a subscription.
public protocol CatalogResolving: Sendable {
    /// Returns the whole match, since it also brings the artwork.
    func resolve(_ query: CatalogQuery) async throws -> CatalogCandidate?

    /// Artwork for songs that already have IDs. Also backfills older rows.
    func artworkURLs(forSongIDs ids: [String]) async throws -> [String: String]
}
