import Foundation

/// The downloads Tracks Radio made for itself, by track id, which Delete After Playing removes
/// once the song has played.
///
/// By id rather than by song: an album you downloaded and the single the radio fetched are the
/// same song as far as names go, and only the radio's copy is the radio's to delete.
public struct DownloadsForRadio: Sendable, Equatable {
    public private(set) var ids: Set<String>

    public init(ids: some Sequence<String> = []) {
        self.ids = Set(ids)
    }

    /// Whether the radio should fetch a song: not when any copy of it is on this device or on
    /// its way, which plays just as well.
    public static func shouldFetch(copies: [String], isOnDevice: (String) -> Bool) -> Bool {
        !copies.contains(where: isOnDevice)
    }

    public func contains(_ id: String) -> Bool {
        ids.contains(id)
    }

    /// The radio started downloading this copy.
    public mutating func fetched(_ id: String) {
        ids.insert(id)
    }

    /// You asked for these, so they're yours and stay.
    @discardableResult
    public mutating func asked(_ asked: some Sequence<String>) -> Bool {
        let before = ids.count
        ids.subtract(asked)
        return ids.count != before
    }

    /// The song played: the radio's own copies of it to delete, which it lets go of.
    /// - Parameter copies: every copy of the song, by id.
    public mutating func played(copies: [String]) -> Set<String> {
        let mine = ids.intersection(copies)
        ids.subtract(mine)
        return mine
    }
}
