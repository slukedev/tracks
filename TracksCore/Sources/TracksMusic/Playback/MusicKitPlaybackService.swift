import Foundation
import MusicKit
import TracksCore

/// Plays songs from the history in the Music app, for Play Songs In set to Apple Music.
///
/// On iOS that's `SystemMusicPlayer`, which hands the queue to the Music app. The Mac uses
/// ``MusicAppPlaybackService``.
public struct MusicKitPlaybackService: PlaybackService {
    /// On macOS only tests and the probe use this type, but it still has to compile.
    #if os(iOS)
    private typealias Player = SystemMusicPlayer
    #else
    private typealias Player = ApplicationMusicPlayer
    #endif

    public init() {}

    public func play(songIDs: [String]) async throws {
        guard !songIDs.isEmpty else { throw PlaybackError.nothingToPlay }

        if let subscription = try? await MusicSubscription.current,
           !subscription.canPlayCatalogContent {
            throw PlaybackError.notSubscribed
        }

        let ordered = try await Self.songs(for: songIDs)
        guard !ordered.isEmpty else { throw PlaybackError.nothingToPlay }

        do {
            try await Self.start(ordered)
        } catch {
            Log.playback.error("MusicKit couldn't start playback: \(String(describing: error), privacy: .public)")
            throw PlaybackError.failed(PlaybackFailure(error).words)
        }
    }

    /// Looks the songs up, in the order asked for, leaving out any that can't be found.
    ///
    /// Rows imported from Recently Played have library IDs ("i.aJGY3G9fEGApOQP"), and one of
    /// those in a catalog request fails the whole request. Catalog and library IDs go to their own requests, as in
    /// ``CatalogLookup``, and each request failing only loses its own songs. An ID that is
    /// neither is left out, since neither request would take it.
    ///
    /// Throws only when nothing was found and a request failed, so the failure is reported
    /// rather than passed off as nothing to play.
    public static func songs(for songIDs: [String]) async throws -> [Song] {
        var found: [String: Song] = [:]
        var failure: (any Error)?

        let catalogIDs = Array(Set(songIDs.filter(MusicItemIdentity.isCatalogID)))
        if !catalogIDs.isEmpty {
            let request = MusicCatalogResourceRequest<Song>(
                matching: \.id,
                memberOf: catalogIDs.map { MusicItemID($0) }
            )
            do {
                for song in try await request.response().items { found[song.id.rawValue] = song }
            } catch {
                failure = error
            }
        }

        let libraryIDs = Array(Set(songIDs.filter(MusicItemIdentity.isLibraryID)))
        if !libraryIDs.isEmpty {
            var request = MusicLibraryRequest<Song>()
            request.filter(matching: \.id, memberOf: libraryIDs.map { MusicItemID($0) })
            do {
                for song in try await request.response().items { found[song.id.rawValue] = song }
            } catch {
                failure = failure ?? error
            }
        }

        // Each request returns its own order; keep the caller's.
        let ordered = songIDs.compactMap { found[$0] }
        if ordered.isEmpty, let failure { throw failure }
        return ordered
    }

    /// On the main actor, as every touch of a MusicKit player is: MediaPlayer's player behind
    /// it isn't safe to use from more than one thread.
    @MainActor
    private static func start(_ songs: [Song]) async throws {
        let player = Player.shared
        player.queue = Player.Queue(for: songs)
        // Needed for the play to reach listening history, on either player.
        player.queue.affectsListeningHistory = true
        try await player.play()
    }

    @MainActor
    public func pause() async {
        Player.shared.pause()
    }

    @MainActor
    public func skipToNext() async throws {
        try await Player.shared.skipToNextEntry()
    }
}
