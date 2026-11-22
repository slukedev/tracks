import Testing
@testable import TracksCore

@Suite("Downloads Tracks Radio made for itself")
struct DownloadsForRadioTests {
    @Test("Delete After Playing deletes the radio's copy and never the album you downloaded")
    func keepsYourCopy() {
        var radio = DownloadsForRadio()
        radio.fetched("server:home:single")
        let copies = ["server:home:album-track", "server:home:single"]
        #expect(radio.played(copies: copies) == ["server:home:single"])
        #expect(!radio.contains("server:home:single"))
    }

    @Test("a copy the radio fetched that you then asked for is yours")
    func askedForAfter() {
        var radio = DownloadsForRadio(ids: ["server:home:single"])
        let changed = radio.asked(["server:home:single"])
        #expect(changed)
        #expect(radio.played(copies: ["server:home:single"]).isEmpty)
        let changedAgain = radio.asked(["server:home:single"])
        #expect(!changedAgain)
    }

    @Test("a song with any copy already on this device isn't fetched again")
    func noSecondCopy() {
        let onDevice: Set = ["server:home:album-track"]
        #expect(!DownloadsForRadio.shouldFetch(copies: ["server:home:album-track", "server:home:single"], isOnDevice: onDevice.contains))
        #expect(DownloadsForRadio.shouldFetch(copies: ["server:home:single"], isOnDevice: onDevice.contains))
    }

    @Test("a song the radio didn't fetch is left alone once it plays")
    func notTheRadios() {
        var radio = DownloadsForRadio(ids: ["server:home:other"])
        #expect(radio.played(copies: ["server:home:album-track"]).isEmpty)
        #expect(radio.contains("server:home:other"))
    }
}
