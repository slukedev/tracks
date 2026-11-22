import Foundation
import MusicKit
import TracksCore

/// What the Apple Music account can do, kept up to date from MusicKit's updates, so playing
/// doesn't ask Apple's servers first every time.
@MainActor
public enum AppleMusicSubscription {
    /// An account that can play is taken at its word until told otherwise. One that can't is
    /// asked about again after a minute, in case the updates that would say so have stopped.
    private static let followed = FollowedValue(isSettled: \.canPlayCatalogContent, recheck: .seconds(60)) {
        try await MusicSubscription.current
    }
    private static var following: Task<Void, Never>?

    /// As last heard, or nil before anything has been.
    public static var latest: MusicSubscription? { followed.latest }

    /// Follows the account's updates from now on. Again after they end, as they can while
    /// access hasn't been granted.
    public static func follow() {
        guard following == nil else { return }
        following = Task {
            for await update in MusicSubscription.subscriptionUpdates {
                followed.update(update)
            }
            following = nil
        }
    }

    /// The account as last heard, or read from Apple if nothing has been yet. Nil when that
    /// read isn't back within `limit`, as offline, or when it fails for a reason playing would
    /// run into anyway. Throws only the failure Apple Music needs the person for: its terms,
    /// which the Music app has to be opened once to accept.
    public static func current(within limit: Duration) async throws -> MusicSubscription? {
        follow()
        do {
            return try await followed.value(within: limit)
        } catch MusicSubscription.Error.privacyAcknowledgementRequired {
            throw MusicSubscription.Error.privacyAcknowledgementRequired
        } catch {
            return nil
        }
    }
}
