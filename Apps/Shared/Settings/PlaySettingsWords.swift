import Foundation
import TracksCore

/// The words for Settings ▸ Play on iPhone and the Mac's Play pane, worked out from plain
/// values so both read the same in every state and the views only lay them out.
enum PlaySettingsWords {

    // MARK: - Status

    /// Where Play stands, as the hero's status line reads it.
    struct Standing: Equatable {
        var source: MusicSource
        var opening: OpeningTab
        /// Whether Tracks may play Apple Music.
        var allowsAppleMusic: Bool
        /// iPhone's Offline Mode, with how many songs are downloaded. Nil when it's off.
        var offlineSongs: Int?
        /// Songs in your music: files, and every song on your servers.
        var songs: Int
        var servers: Int
        /// The first server with a problem, by name.
        var troubledServer: TroubledServer?
        /// Every source on, with more than one to play from: each song plays its best copy.
        var mixes = false
    }

    struct TroubledServer: Equatable {
        var name: String
        var needsPassword: Bool
        /// A Trackside server that signed this device out: it's paired again, not given a password.
        var isTrackside = false
    }

    static func status(_ standing: Standing) -> (line: String, tone: SettingsTone) {
        if standing.mixes {
            if let server = standing.troubledServer {
                return (troubled(server), .attention)
            }
            return (String(localized: "Plays All Sources"), .plain)
        }
        switch standing.source {
        case .appleMusic:
            guard standing.allowsAppleMusic else {
                #if os(macOS)
                return (String(localized: "Allow Apple Music in General to play it here."), .attention)
                #else
                return (String(localized: "Allow Apple Music in Settings ▸ Apple Music to play it here."), .attention)
                #endif
            }
            if standing.offlineSongs != nil {
                return (String(localized: "Offline Mode"), .plain)
            }
            return (String(localized: "Plays Apple Music"), .plain)
        case .yourMusic:
            if let server = standing.troubledServer {
                return (troubled(server), .attention)
            }
            guard standing.songs > 0 || standing.servers > 0 else {
                return (String(localized: "Add songs or connect a server to start."), .attention)
            }
            return (String(localized: "Plays Files and Servers"), .plain)
        case .spotify:
            return (String(localized: "Plays Spotify"), .plain)
        }
    }

    private static func troubled(_ server: TroubledServer) -> String {
        let name = quoted(server.name)
        if server.needsPassword {
            return server.isTrackside
                ? String(localized: "\(name) needs pairing again")
                : String(localized: "\(name) needs its password")
        }
        return String(localized: "\(name) can’t be reached")
    }

    // MARK: - Around Tracks

    /// Where Tracks opens: "Play" on iPhone, "Listen Now" on the Mac, where Play is the
    /// sidebar's Listen Now.
    static func openingName(_ tab: OpeningTab) -> String {
        switch tab {
        case .summary: String(localized: "Summary")
        #if os(macOS)
        case .play: String(localized: "Listen Now")
        #else
        case .play: String(localized: "Play")
        #endif
        }
    }

    /// "Tracks" or "Apple Music"; on the Mac, "Music" is the Music app.
    static func destinationName(_ destination: SongDestination) -> String {
        switch destination {
        case .tracks: String(localized: "Tracks")
        #if os(macOS)
        case .appleMusic: String(localized: "Music")
        #else
        case .appleMusic: String(localized: "Apple Music")
        #endif
        }
    }

    /// Only for Apple Music: Tracks keeps only what plays in Tracks, and that's easy to miss.
    static func destinationFooter(_ destination: SongDestination) -> String? {
        guard destination == .appleMusic else { return nil }
        #if os(macOS)
        return String(localized: "Songs played in Music aren’t kept in your history.")
        #else
        return String(localized: "Songs played in Apple Music aren’t kept in your history.")
        #endif
    }

    /// What a shake plays, which the switch's name doesn't say.
    static func shakeFooter(_ shakeToPlay: Bool) -> String? {
        shakeToPlay ? String(localized: "Plays a song you haven’t heard, then Tracks Radio.") : nil
    }

    /// Where to turn on finding your devices.
    static var localNetworkOff: String {
        #if os(macOS)
        String(localized: "Turn on Tracks in System Settings ▸ Privacy & Security ▸ Local Network.")
        #else
        String(localized: "Turn on Local Network in Settings ▸ Apps ▸ Tracks.")
        #endif
    }

    // MARK: - Your Music

    /// Only for Everything, the one that reaches past what you have.
    static func suggestionFooter(_ mode: SuggestionMode) -> String? {
        mode == .everything ? String(localized: "Includes songs you don’t have yet.") : nil
    }

    /// Only when downloads are made smaller, since the ones already made stay as they were.
    static func downloadQualityFooter(_ download: StreamQuality) -> String? {
        download.maxBitRate.map { AudioQualityWords.downloadDetail($0) }
    }

    /// Show Audio Quality's detail on the Mac, and its sentence in iPhone's footer.
    static func audioQualityDetail(isShown: Bool) -> String {
        AudioQualityWords.showsAudioQuality(isShown)
    }

    /// Animated Covers' detail on the Mac, and its sentence in iPhone's footer.
    static func animatedCoversDetail(isOn: Bool) -> String {
        isOn
            ? String(localized: "An album's moving cover plays in its place while the music plays, where your music server has one.")
            : String(localized: "Covers stay still.")
    }

    /// Living Cover's footer in Stage's choices, which follows Move with the Music.
    static func livingCoverFooter(movesWithMusic: Bool) -> String {
        movesWithMusic
            ? String(localized: "The cover comes alive and moves with the music: it breathes with the bass, stirs with the snare and glints with the cymbals, and settles when it’s paused.")
            : String(localized: "The cover comes alive: its colours flow and swirl into one another at a calm pace of their own, and settle when it’s paused.")
    }

    // MARK: - Tracks Radio

    /// Only when a download can go: how to keep one.
    static func deleteAfterPlayingFooter(_ deletes: Bool) -> String? {
        deletes ? String(localized: "Keep a song from Up Next, or add it to a playlist, and its download stays.") : nil
    }

    // MARK: - Transitions

    /// Only for crossfade, which doesn't change what's already playing.
    static func transitionFooter(_ transition: SongTransition) -> String? {
        transition == .crossfade ? String(localized: "Starts with the next thing you play.") : nil
    }

    // MARK: - Layout

    /// "All Shown", or "9 of 12 Shown".
    static func layoutValue(_ layout: PlayLayout) -> String {
        let all = layout.offered.count
        let shown = layout.offered.count(where: layout.isVisible)
        return shown == all
            ? String(localized: "All Shown")
            : String(localized: "\(shown) of \(all) Shown")
    }

    /// For the Mac's row: how many sections are hidden, when any are.
    static func layoutDetail(_ layout: PlayLayout) -> String? {
        let hidden = layout.offered.count { !layout.isVisible($0) }
        guard hidden > 0 else { return nil }
        return plain(AttributedString(localized: "^[\(hidden) section](inflect: true) hidden"))
    }

    // MARK: - Mixes

    /// What the mixes remember from skips and "Suggest Less".
    struct MixMemory: Equatable {
        /// Songs the mixes leave out: skipped early twice lately, or asked to hear less of.
        var leftOut: Int
        var askedLess: Int
        /// Skipped once, so still in the mixes, but one skip from leaving.
        var skippedOnce: Int

        init(leftOut: Int, askedLess: Int, skippedOnce: Int) {
            self.leftOut = leftOut
            self.askedLess = askedLess
            self.skippedOnce = skippedOnce
        }

        init(_ signals: ListeningSignals, now: Date = .now) {
            // Skips and "suggest less" only: blocked artists are a list of their own, which
            // Reset leaves alone.
            var signals = signals
            signals.blocked = BlockedArtists()
            let songs = Set(signals.skips.keys).union(signals.suggestLess)
            let excluded = songs.filter { signals.excludes($0, now: now) }
            leftOut = excluded.count
            askedLess = signals.suggestLess.count
            skippedOnce = songs.count { !excluded.contains($0) && signals.recentSkips(of: $0, now: now) > 0 }
        }

        var isEmpty: Bool { leftOut == 0 && skippedOnce == 0 }
    }

    /// "7 songs left out · 2 you asked to hear less of", for the Mac's row and iPhone's value.
    static func mixDetail(_ memory: MixMemory) -> String {
        guard !memory.isEmpty else { return String(localized: "Nothing skipped yet") }
        guard memory.leftOut > 0 else {
            return plain(AttributedString(localized: "Nothing left out · ^[\(memory.skippedOnce) song](inflect: true) skipped once"))
        }
        let leftOut = plain(AttributedString(localized: "^[\(memory.leftOut) song](inflect: true) left out"))
        guard memory.askedLess > 0 else { return leftOut }
        return String(localized: "\(leftOut) · \(memory.askedLess) you asked to hear less of")
    }

    static func resetMessage(_ memory: MixMemory) -> String {
        guard memory.leftOut > 0 else {
            return String(localized: "Tracks forgets the songs you’ve skipped once. Nothing is left out of your mixes yet.")
        }
        let songs = plain(AttributedString(localized: "^[\(memory.leftOut) song](inflect: true)"))
        return String(localized: "The \(songs) your mixes leave out come back into them, and Tracks forgets every skip.")
    }

    /// The confirmation's button, with the count when songs come back.
    static func resetButton(_ memory: MixMemory) -> String {
        guard memory.leftOut > 0 else { return String(localized: "Reset Mix Suggestions") }
        return plain(AttributedString(localized: "Bring Back ^[\(memory.leftOut) Song](inflect: true)"))
    }

    static var mixesFooter: String {
        String(localized: "Mixes leave out songs you skip early twice or ask to hear less of.")
    }

    // MARK: - Helpers

    /// Inflection resolved, for places that show the markup literally: status strings, dialog
    /// titles and messages.
    static func plain(_ text: AttributedString) -> String {
        String(text.characters)
    }

    /// Curly quotes, so a name reads as a name inside a sentence.
    static func quoted(_ name: String) -> String {
        "\u{201C}\(name)\u{201D}"
    }
}
