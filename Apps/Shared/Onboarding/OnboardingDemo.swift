import Foundation
import TracksCore

struct OnboardingDemo: Equatable {
    var page: Onboarding.Page
    var restore: HistoryRestore
    var isReturning = false
    var access: MusicAccessStatus = .notAsked
    var canPlayCatalog: Bool?
    var lastFMUsername: String?
    var servers: [String] = []

    static var requested: OnboardingDemo? {
        #if DEBUG
        UserDefaults.standard.string(forKey: "TracksOnboarding").flatMap(scene(named:))
        #else
        nil
        #endif
    }

    static func scene(named name: String) -> OnboardingDemo? {
        switch name {
        case "new":
            OnboardingDemo(page: .welcome, restore: .looking)
        case "setup":
            OnboardingDemo(page: .setup, restore: .nothingToRestore)
        case "back":
            OnboardingDemo(page: .setup, restore: .restoring(restored: 3_100, expected: 12_400), isReturning: true, lastFMUsername: "marasolis")
        case "slow":
            OnboardingDemo(page: .setup, restore: .slow(restored: 3_100, expected: 12_400), isReturning: true, access: .allowed, canPlayCatalog: true)
        case "done":
            OnboardingDemo(page: .setup, restore: .finished(restored: 12_400), isReturning: true, access: .allowed, canPlayCatalog: true, lastFMUsername: "marasolis", servers: ["Living Room"])
        case "denied":
            OnboardingDemo(page: .setup, restore: .nothingToRestore, access: .denied, canPlayCatalog: false)
        case "noiCloud":
            OnboardingDemo(page: .setup, restore: .unavailable(CloudSyncStatus.notSignedIn.line()))
        default:
            nil
        }
    }
}
