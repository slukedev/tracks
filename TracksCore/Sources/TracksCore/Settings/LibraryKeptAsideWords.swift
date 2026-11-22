import Foundation

/// A run that couldn't open the library on disk, in words: what's played is kept aside and
/// saved once the library opens, and a relaunch is what opens it once the library answers
/// again.
public struct LibraryKeptAside: Sendable, Equatable {
    public var opensOnRelaunch: Bool

    public init(opensOnRelaunch: Bool) {
        self.opensOnRelaunch = opensOnRelaunch
    }

    public static var title: String { String(localized: "Listening Kept Aside") }

    public var line: String {
        String(localized: "Tracks couldn’t open its library. What you play is kept and saved once it opens.")
    }

    /// The one thing to do, once doing it would help.
    public var action: String? {
        opensOnRelaunch ? String(localized: "Reopen Tracks to save your listening.") : nil
    }
}
