import Foundation

/// How each row of getting started says where it stands. The app maps MusicKit, the Last.fm
/// connection and the servers onto these, so the words can be tested without them.
public enum OnboardingWords {

    /// Apple Music access, and whether the account can play the catalog.
    public static func appleMusic(_ access: MusicAccessStatus, canPlayCatalog: Bool?) -> String {
        switch access {
        case .notAsked: String(localized: "Play Apple Music in Tracks")
        case .allowed where canPlayCatalog == false: String(localized: "Allowed · No subscription")
        case .allowed: String(localized: "Allowed")
        case .denied: String(localized: "Access is off")
        case .restricted: String(localized: "Restricted on this device")
        }
    }

    public static func lastFM(connectedAs username: String?, isWaiting: Bool, failure: String?) -> String {
        if let username { return String(localized: "Connected as \(username)") }
        if isWaiting { return String(localized: "Waiting for Last.fm…") }
        if let failure { return failure }
        return String(localized: "Scrobble what you play")
    }

    /// The servers connected so far, by name.
    public static func yourMusic(servers: [String]) -> String {
        switch servers.count {
        case 0: String(localized: "Play music from your own server")
        case 1: String(localized: "Connected to \(servers[0])")
        default: String(localized: "Connected to \(servers.formatted(.list(type: .and)))")
        }
    }

    /// The heading: a welcome, or a welcome back when iCloud already holds a history.
    public static func setupTitle(isReturning: Bool) -> String {
        isReturning ? String(localized: "Welcome Back") : String(localized: "Set Up Tracks")
    }
}
