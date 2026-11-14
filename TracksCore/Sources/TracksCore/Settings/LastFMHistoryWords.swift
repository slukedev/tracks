import Foundation

/// The words for bringing the Last.fm history into Tracks, on the Last.fm page and pane.
///
/// Pure, so tests pin every state's line and button, and the iPhone footer and the Mac row's
/// detail can't drift apart.
public enum LastFMHistoryWords {

    /// Where importing stands, as Settings shows it.
    public enum State: Sendable, Equatable {
        /// Never imported for this account.
        case notImported
        /// Reading now.
        case importing(read: Int, total: Int?, added: Int)
        /// A run stopped part way, by the person or a failure, with what it had added in all.
        case stopped(added: Int)
        /// Everything up to the last sync is in.
        case upToDate(lastSynced: Date, added: Int)
        case failed(String)

        public init(progress: LastFMHistory.Progress?, running: LastFMHistoryImporter.Report?, failure: String?) {
            if let running {
                self = .importing(read: running.read, total: running.total, added: running.imported)
            } else if let failure {
                self = .failed(failure)
            } else if let progress, progress.isUnfinished {
                self = .stopped(added: progress.imported)
            } else if let progress, let finished = progress.lastFinished {
                self = .upToDate(lastSynced: finished, added: progress.imported)
            } else {
                self = .notImported
            }
        }

        public var isImporting: Bool {
            if case .importing = self { return true }
            return false
        }
    }

    /// The Mac row's title, under a History header.
    public static let title = String(localized: "Import Scrobbles")

    /// The iPhone row, which is the button itself. It stops a run that's going, and
    /// otherwise starts one.
    public static func action(_ state: State) -> String {
        switch state {
        case .notImported: String(localized: "Import Scrobbles")
        case .importing: String(localized: "Stop Importing")
        case .stopped: String(localized: "Continue Importing")
        case .upToDate: String(localized: "Sync Now")
        case .failed: String(localized: "Try Again")
        }
    }

    /// The Mac row's button, beside ``title``.
    public static func button(_ state: State) -> String {
        switch state {
        case .notImported: String(localized: "Import")
        case .importing: String(localized: "Stop")
        case .stopped: String(localized: "Continue")
        case .upToDate: String(localized: "Sync Now")
        case .failed: String(localized: "Try Again")
        }
    }

    /// The line under the title on the Mac, and the footer on iPhone. Before the first import
    /// it says what the button does, since nothing asks first; after, where it stands. The
    /// progress itself shows in the row.
    public static func detail(_ state: State) -> String {
        switch state {
        case .notImported:
            String(localized: "Adds the songs you scrobbled on Last.fm, from any app, to your history. Songs already in Tracks are skipped.")
        case .importing:
            String(localized: "Importing…")
        case .stopped:
            String(localized: "Stopped. Continue picks up where it left off.")
        case .upToDate(let lastSynced, _):
            String(localized: "Synced \(lastSynced.formatted(.relative(presentation: .named, unitsStyle: .wide))).")
        case .failed(let message):
            String(localized: "Couldn’t import. \(message)")
        }
    }
}
