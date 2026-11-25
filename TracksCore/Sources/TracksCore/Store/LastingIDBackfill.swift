import Foundation
import SwiftData

extension HistoryWriter {
    /// Gives every play and session kept before lasting ids existed the id every other device
    /// gives it too (``PlayID``), in batches, so a long history is done without holding it all.
    ///
    /// Plays get a UUIDv5 of the key the history index has always known them by, so the index
    /// moves to the new ids without counting anything twice, and the same row synced through
    /// iCloud gets the same id on every device whichever does it first.
    /// - Returns: how many rows were given an id.
    @discardableResult
    func backfillLastingIDs(batchSize: Int = 2_000) throws -> Int {
        try backfillPlayIDs(batchSize: batchSize) + backfillSessionIDs()
    }

    private struct Unnamed: Sendable {
        let id: PersistentIdentifier
        let key: String
        let order: String
    }

    private func backfillPlayIDs(batchSize: Int) throws -> Int {
        let rows: [Unnamed] = try read { context in
            var descriptor = FetchDescriptor<Capture>(predicate: #Predicate { $0.playID == nil })
            descriptor.propertiesToFetch = [
                \.capturedAt, \.kindRawValue, \.sourceRawValue, \.title, \.artistName,
                \.capturedByDeviceID, \.songKey, \.songID, \.platformRawValue, \.albumTitle,
            ]
            return try context.fetch(descriptor).map { capture in
                Unnamed(
                    id: capture.persistentModelID,
                    key: PlayID.contentKey(
                        capturedAt: capture.capturedAt,
                        kind: capture.kind.rawValue,
                        source: capture.source.rawValue,
                        songIdentity: SongIdentity.key(title: capture.title, artistName: capture.artistName)
                    ),
                    // What exact copies can still differ in, in an order every device agrees on.
                    order: [capture.capturedByDeviceID, capture.songKey, capture.songID, capture.platformRawValue, capture.albumTitle ?? ""].joined(separator: "\u{1F}")
                )
            }
        }
        guard !rows.isEmpty else { return 0 }
        let named = PlayID.backfill(rows, key: \.key, precedes: { $0.order < $1.order })
        var given = 0
        for start in stride(from: 0, to: named.count, by: max(1, batchSize)) {
            let batch = Array(named[start..<min(start + batchSize, named.count)])
            given += try write { context in
                let found = TracksStore.existingCaptures(batch.map(\.row.id), in: context)
                var count = 0
                for (row, playID) in batch {
                    guard let capture = found[row.id], capture.playID == nil else { continue }
                    capture.playID = playID
                    count += 1
                }
                return count
            }
        }
        return given
    }

    private func backfillSessionIDs() throws -> Int {
        try write { context in
            let sessions = try context.fetch(FetchDescriptor<Session>(predicate: #Predicate { $0.sessionID == nil }))
            for session in sessions {
                let key = "session\u{1F}\(CloudJSON.milliseconds(session.startedAt))\u{1F}\(session.deviceID ?? "")"
                session.sessionID = PlayID.derived(fromKey: key)
            }
            return sessions.count
        }
    }
}
