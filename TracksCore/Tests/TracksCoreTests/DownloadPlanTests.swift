import Testing
@testable import TracksCore

@Suite("Download plan")
struct DownloadPlanTests {

    private func plan(
        apple: AppleMusicReach = .ready,
        appleHolding: AppleMusicHolding = .missing,
        server: YourMusicReach = .ready,
        serverHolding: YourMusicHolding = .missing,
        preferred: DownloadService? = nil,
        last: DownloadService? = nil
    ) -> DownloadPlan {
        DownloadPlan(appleMusic: apple, appleHolding: appleHolding, yourMusic: server, serverHolding: serverHolding, preferred: preferred, last: last)
    }

    @Test("with both services able, a tap asks which, leading with the last one chosen")
    func asksWithBoth() {
        let fresh = plan()
        #expect(fresh.tap == .choose)
        #expect(fresh.options.map(\.service) == [.appleMusic, .yourMusic])
        #expect(plan(last: .yourMusic).options.map(\.service) == [.yourMusic, .appleMusic])
        #expect(plan(last: .yourMusic).tap == .choose)
    }

    @Test("a service set to always be used is used straight away, unless it has nothing to keep")
    func alwaysUse() {
        #expect(plan(preferred: .yourMusic).tap == .perform(.yourMusic))
        #expect(plan(preferred: .yourMusic, last: .appleMusic).options.first?.service == .yourMusic)
        let held = plan(serverHolding: .held, preferred: .yourMusic)
        #expect(held.tap == .perform(.appleMusic))
        let offline = plan(server: .offline, preferred: .yourMusic)
        #expect(offline.tap == .perform(.appleMusic))
        #expect(offline.option(for: .yourMusic)?.action == .offline)
    }

    @Test("with one service able, a tap uses it")
    func oneService() {
        let noServer = plan(server: .notSetUp)
        #expect(noServer.options.map(\.service) == [.appleMusic])
        #expect(noServer.tap == .perform(.appleMusic))

        let serverOnly = plan(apple: .notInCatalog)
        #expect(serverOnly.options.map(\.service) == [.yourMusic])
        #expect(serverOnly.tap == .perform(.yourMusic))

        let noSubscription = plan(apple: .unavailable, server: .cannotGet)
        #expect(noSubscription.isEmpty)
        #expect(noSubscription.tap == nil)
    }

    @Test("Apple Music's library without the download opens Music to download it")
    func inLibrary() {
        let added = plan(appleHolding: .inLibrary, serverHolding: .held)
        #expect(added.option(for: .appleMusic)?.action == .openInMusic)
        #expect(added.tap == .perform(.appleMusic))
        #expect(added.summary == .partlyKept)

        let both = plan(appleHolding: .downloaded, serverHolding: .held)
        #expect(both.tap == nil)
        #expect(both.summary == .kept)
    }

    @Test("an album your server has some of offers the rest, or reads as partly kept when it can't")
    func partialAlbum() {
        #expect(plan(serverHolding: .partial).option(for: .yourMusic)?.action == .keepRest)
        let cannot = plan(apple: .unavailable, server: .cannotGet, serverHolding: .partial)
        #expect(cannot.option(for: .yourMusic)?.action == .partlyDone)
        #expect(cannot.tap == nil)
        #expect(cannot.summary == .partlyKept)
    }

    @Test("work under way shows and can't be started twice; a failure offers to try again")
    func progressAndFailure() {
        let working = plan(appleHolding: .adding, serverHolding: .fetching(0.4))
        #expect(working.tap == nil)
        #expect(working.summary == .working)
        #expect(working.option(for: .yourMusic)?.action == .inProgress(0.4))

        let failed = plan(apple: .unavailable, serverHolding: .failed("Couldn't reach your server."))
        #expect(failed.option(for: .yourMusic)?.action == .retry)
        #expect(failed.tap == .perform(.yourMusic))
        #expect(failed.summary == .failed)

        let failedOffline = plan(apple: .unavailable, server: .offline, serverHolding: .failed("Offline"))
        #expect(failedOffline.option(for: .yourMusic)?.action == .offline)
        #expect(failedOffline.tap == nil)
    }

    @Test("something your server has stays marked even once nothing there can get more")
    func heldWithoutGetting() {
        let held = plan(apple: .unavailable, server: .cannotGet, serverHolding: .held)
        #expect(held.option(for: .yourMusic)?.action == .done)
        #expect(held.summary == .kept)
        #expect(plan(apple: .unavailable, server: .notSetUp, serverHolding: .held).isEmpty)
    }
}
