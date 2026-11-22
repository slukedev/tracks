import Testing
@testable import TracksCore

@Suite("Listening kept aside")
struct LibraryKeptAsideTests {
    @Test("says what happened, and asks for a relaunch only once one would help", arguments: [false, true])
    func words(opensOnRelaunch: Bool) {
        let keptAside = LibraryKeptAside(opensOnRelaunch: opensOnRelaunch)
        #expect(LibraryKeptAside.title == "Listening Kept Aside")
        #expect(keptAside.line == "Tracks couldn’t open its library. What you play is kept and saved once it opens.")
        #expect(keptAside.action == (opensOnRelaunch ? "Reopen Tracks to save your listening." : nil))
    }
}
