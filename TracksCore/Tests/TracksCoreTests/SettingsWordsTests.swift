import Testing
import Foundation
@testable import TracksCore

/// The words Settings shows. The root row, the page's status line and the Mac pane all read
/// these, so a state nobody rendered still says the right thing.
@Suite("Settings words")
struct SettingsWordsTests {

    // MARK: - History

    /// A pause wins over what's kept: the row and the page mustn't say "All Music" while
    /// nothing is being kept at all.
    @Test("the History row and status say what's kept, and that it's paused when it is")
    func historyShortAndStatus() {
        #expect(HistorySettingsWords.short(keepsOnDemand: true) == "All Music")
        #expect(HistorySettingsWords.short(keepsOnDemand: false) == "Radio Only")
        #expect(HistorySettingsWords.short(keepsOnDemand: true, isPaused: true) == "Paused")
        #expect(HistorySettingsWords.status(keepsOnDemand: true, isPaused: false) == "Keeping everything you play")
        #expect(HistorySettingsWords.status(keepsOnDemand: false, isPaused: false) == "Keeping radio only")
        #expect(HistorySettingsWords.status(keepsOnDemand: false, isPaused: true) == "Paused")
    }

    @Test("Counts After reads Straight Away at zero, and only then warns that skips are kept")
    func countsAfter() {
        #expect(HistorySettingsWords.countsAfterValue(0) == "Straight Away")
        #expect(HistorySettingsWords.countsAfterValue(0.25) == "25%")
        #expect(HistorySettingsWords.countingFooter(share: 0, windowMinutes: 10)
            == "Songs you skip are kept too. Hearing the same song again within 10 minutes counts once.")
        #expect(HistorySettingsWords.countingFooter(share: 0.5, windowMinutes: 10)
            == "Hearing the same song again within 10 minutes counts once.")
    }

    /// Steps of 0.05 don't add up exactly, so the slider can hand over 0.15000000000000002.
    @Test("a slider step a hair off a whole percent still reads as one")
    func percentRounds() {
        #expect(HistorySettingsWords.countsAfterValue(0.05 * 3) == "15%")
    }

    @Test("the same-song window reads in minutes, singular at one")
    func sameSongWindow() {
        #expect(HistorySettingsWords.windowValue(minutes: 10) == "10 min")
        #expect(HistorySettingsWords.countingFooter(share: 0.5, windowMinutes: 1) == "Hearing the same song again within 1 minute counts once.")
    }

    // MARK: - Radio

    @Test("Radio's status names the playlist songs go into")
    func radioStatus() {
        let adding = RadioSettingsWords.Playlist(addsSongs: true, name: "Heard on Radio")

        #expect(RadioSettingsWords.status(adding) == "Adding to “Heard on Radio”")
        #expect(RadioSettingsWords.short(adding) == "Heard on Radio")
    }

    @Test("with adding off, the row shows no playlist")
    func radioOff() {
        let off = RadioSettingsWords.Playlist(addsSongs: false, name: "Heard on Radio")

        #expect(RadioSettingsWords.short(off) == nil)
        #expect(RadioSettingsWords.status(off) == "Not adding to a playlist")
    }

    @Test("a blank name reads as the default playlist, as capture reads it")
    func blankPlaylistName() {
        let blank = RadioSettingsWords.Playlist(addsSongs: true, name: "   ")

        #expect(blank.name == CaptureSettings.defaultPlaylistName)
    }

    // MARK: - Last.fm

    @Test("Last.fm's row and line follow the connection", arguments: [
        (LastFMSettingsStatus.notSetUp, "Not Set Up", SettingsTone.plain),
        (.connecting, "Connecting…", .plain),
        (.connected(username: "rj", scrobbles: true), "rj", .plain),
        (.connected(username: "rj", scrobbles: false), "Off", .plain),
        (.failed("Last.fm is down."), "Not Set Up", .failure),
    ])
    func lastFM(status: LastFMSettingsStatus, short: String, tone: SettingsTone) {
        #expect(status.short == short)
        #expect(status.tone == tone)
    }

    @Test("a connected account says who is scrobbling")
    func lastFMLine() {
        #expect(LastFMSettingsStatus.connected(username: "rj", scrobbles: true).line == "Scrobbling as rj")
        #expect(LastFMSettingsStatus.connected(username: "rj", scrobbles: false).line == "Connected as rj · scrobbling is off")
        #expect(LastFMSettingsStatus.failed("Last.fm is down.").line == "Last.fm is down.")
    }

    @Test("unsent plays are counted, and too-old ones explained only when there are some", arguments: [
        (UnsentScrobbles(retryable: 12, tooOld: 0), "12 Not Sent", String?.none),
        (UnsentScrobbles(retryable: 1, tooOld: 1), "1 Not Sent", "Last.fm takes only the last two weeks, so 1 play can’t be sent."),
        (UnsentScrobbles(retryable: 0, tooOld: 204), "0 Not Sent", "Last.fm takes only the last two weeks, so 204 plays can’t be sent."),
    ])
    func unsentScrobbles(unsent: UnsentScrobbles, value: String, footer: String?) {
        #expect(unsent.notSentValue == value)
        #expect(unsent.tooOldLine == footer)
    }

    // MARK: - Apple Music

    @Test("Apple Music access asks for attention only when it's blocked", arguments: [
        (MusicAccessStatus.allowed, "Allowed", SettingsTone.plain),
        (.notAsked, "Not Set Up", .plain),
        (.denied, "Off", .attention),
        (.restricted, "Restricted", .attention),
    ])
    func musicAccess(status: MusicAccessStatus, short: String, tone: SettingsTone) {
        #expect(status.short == short)
        #expect(status.tone == tone)
        #expect(!status.line.isEmpty)
    }

    // MARK: - iCloud

    private func cloud(
        configured: Bool = true,
        wants: Bool = true,
        running: Bool = true,
        startFailure: String? = nil,
        account: CloudSyncStatus.Account? = .available,
        accountError: String? = nil,
        failure: CloudSyncMonitor.Failure? = nil,
        lastSynced: Date? = nil
    ) -> CloudSyncStatus {
        CloudSyncStatus.resolve(
            isConfigured: configured,
            wantsSync: wants,
            isRunning: running,
            startFailure: startFailure,
            account: account,
            accountError: accountError,
            failure: failure,
            lastSynced: lastSynced
        )
    }

    /// Turning sync on showed "The iCloud container could not be opened." in red, because the
    /// store that was open had been opened without sync. It starts next launch; say that.
    @Test("a switch flipped this session takes effect next launch")
    func switchTakesEffectNextLaunch() {
        #expect(cloud(wants: true, running: false) == .startsNextLaunch)
        #expect(cloud(wants: false, running: true) == .stopsNextLaunch)
        #expect(cloud(wants: false, running: false) == .off)
        #expect(cloud(wants: true, running: false, startFailure: "Refused") == .couldNotStart("Refused"))
    }

    @Test("the account is checked before what the mirror reported")
    func accountComesFirst() {
        let failure = CloudSyncMonitor.Failure(activity: .export, message: "Refused", date: .now)

        #expect(cloud(account: .noAccount, failure: failure) == .notSignedIn)
        #expect(cloud(account: nil) == .checking)
        #expect(cloud(accountError: "Offline") == .accountError("Offline"))
        #expect(cloud(failure: failure) == .failed(.export, "Refused"))
        #expect(cloud(configured: false, wants: false) == .unavailable)
    }

    @Test("an export failure is said plainly, with iCloud's reason kept for underneath")
    func failureWords() {
        let status = CloudSyncStatus.failed(.export, "Refused")

        #expect(status.line() == "iCloud isn’t accepting this device’s history, so your other devices won’t see it.")
        #expect(status.reason == "Refused")
        #expect(status.short == "Not Syncing")
        #expect(status.tone == .failure)
    }

    @Test("on says when it last synced")
    func onWords() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)

        #expect(CloudSyncStatus.on(lastSynced: now.addingTimeInterval(-10)).line(now: now) == "On · synced just now")
        #expect(CloudSyncStatus.on(lastSynced: nil).line(now: now) == "On · your history syncs to all your devices")
        #expect(CloudSyncStatus.on(lastSynced: now.addingTimeInterval(-300)).line(now: now).hasPrefix("On · synced "))
        #expect(CloudSyncStatus.notSignedIn.short == "Not Signed In")
    }
}
