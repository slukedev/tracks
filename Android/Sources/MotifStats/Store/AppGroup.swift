import Foundation

/// Android has no App Groups, so there's no shared container: ``ArtistArtworkCache`` and the
/// other caches in `Shared` fall back to the app's own defaults.
///
/// Stands in for MotifCore's `Store/AppGroup.swift`, which reads the group from Info.plist and
/// asks for its container with an API only Apple platforms have.
public enum AppGroup {
    public static var identifier: String? { nil }
}
