import Foundation

/// Where a player handed part of a queue has got to, from what it reports: the song it's on
/// and how many it has left after that.
public enum QueueRunPosition {
    /// The queue position of the song the player is on.
    /// - Parameters:
    ///   - identities: each queued song's identity (``SongIdentity``), in queue order.
    ///   - run: the positions handed to the player.
    ///   - current: where the queue had the player last.
    ///   - playing: the identity of the song the player says it's on.
    ///   - left: how many songs the player has after it.
    public static func follow(identities: [String], run: Range<Int>, current: Int, playing: String, left: Int) -> Int {
        let run = run.clamped(to: identities.indices)
        guard !run.isEmpty else { return current }
        let last = run.upperBound - 1
        let byCount = min(max(last - max(0, left), run.lowerBound), last)
        // The same song twice in a row is told apart by how many are left.
        if identities[byCount] == playing { return byCount }
        // Still on it, as while the songs after it are handed over again.
        if run.contains(current), identities[current] == playing { return current }
        let matches = run.filter { identities[$0] == playing }
        if let nearest = matches.min(by: { abs($0 - current) < abs($1 - current) }) { return nearest }
        // Named differently by its player.
        return byCount
    }
}
