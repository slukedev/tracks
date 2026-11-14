import Foundation
import TracksCore
import TracksMusic

/// Where Play Songs In set to Apple Music sends songs on each platform: the Music app through
/// `SystemMusicPlayer` on iOS, and Music.app by scripting on the Mac, which has no
/// `SystemMusicPlayer`.
enum PlatformPlaybackService {
    static func make() -> any PlaybackService {
        #if os(macOS)
        // Read at play time, since the playlist can be renamed in Settings.
        MusicAppPlaybackService(playlistName: { CaptureSettings().playlistName })
        #else
        MusicKitPlaybackService()
        #endif
    }
}
