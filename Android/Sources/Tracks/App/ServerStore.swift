import Foundation
import Observation
import SkipFuse
import TracksStats

/// The music server Tracks plays from: Navidrome, Gonic, Airsonic or any other that speaks the
/// Subsonic API.
@MainActor @Observable
final class ServerStore {
    private(set) var server: SubsonicServer?
    private(set) var client: SubsonicClient?

    private let defaults: UserDefaults
    private static let serverKey = "musicServer"
<<<<<<< HEAD:Android/Sources/Tracks/App/ServerStore.swift
    /// Kept in the app's private preferences, which only Tracks can read.
=======
    /// Kept in `Secrets`, encrypted with a key in the Android Keystore. Earlier versions kept it
    /// in plain text in `defaults` under the same name.
>>>>>>> 167ff4b (feat(android): Material 3 Expressive components, encrypted secrets, and screen updates):Android/Sources/Tracks/App/ServerStore.swift
    private static let passwordKey = "musicServerPassword"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        Self.moveLegacyPassword(in: defaults)
        if let data = defaults.data(forKey: Self.serverKey),
           let saved = try? JSONDecoder().decode(SubsonicServer.self, from: data),
           let password = Secrets.read(Self.passwordKey) ?? defaults.string(forKey: Self.passwordKey) {
            server = saved
            client = SubsonicClient(server: saved, password: password)
        }
    }

    /// Moves a password from plain text in `defaults` into `Secrets`, and removes the plain copy
    /// once the encrypted one is kept. If it can't be kept, the plain copy stays for this launch
    /// and the next one tries again.
    private static func moveLegacyPassword(in defaults: UserDefaults) {
        guard let legacy = defaults.string(forKey: passwordKey) else { return }
        if Secrets.write(legacy, for: passwordKey) {
            defaults.removeObject(forKey: passwordKey)
        }
    }

    var isConnected: Bool { client != nil }

    /// Checks the address and sign-in with the server, and keeps them if it answers.
    func connect(address: String, username: String, password: String) async throws(ConnectionError) {
        guard let url = ServerAddress.url(from: address) else { throw .badAddress }
        let trimmedUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedUser.isEmpty, !password.isEmpty else { throw .missingSignIn }
        let candidate = SubsonicServer(name: url.host ?? "Music server", url: url, username: trimmedUser)
        let client = SubsonicClient(server: candidate, password: password)
        do {
            try await client.ping()
        } catch let error as SubsonicError {
            throw ConnectionError(error)
        } catch {
            throw .unreachable(error.localizedDescription)
        }
        if let data = try? JSONEncoder().encode(candidate), Secrets.write(password, for: Self.passwordKey) {
            defaults.set(data, forKey: Self.serverKey)
        }
        server = candidate
        self.client = client
    }

    func disconnect() {
        defaults.removeObject(forKey: Self.serverKey)
        Secrets.remove(Self.passwordKey)
        server = nil
        client = nil
    }

    // MARK: - Covers

    /// A cover as the history keeps it: which server, which cover, and no credentials.
    nonisolated static func coverRef(serverID: String, coverID: String?) -> String? {
        coverID.map { "subsonic-cover:\(serverID)/\($0)" }
    }

    /// Where to load a cover from: a reference resolved against the connected server, or an
    /// address as it is.
    func coverURL(_ reference: String?, size: Int = 600) -> URL? {
        guard let reference else { return nil }
        guard reference.hasPrefix("subsonic-cover:") else { return URL(string: reference) }
        let path = reference.dropFirst("subsonic-cover:".count)
        guard let slash = path.firstIndex(of: "/"), let client, client.server.id == String(path[..<slash]) else { return nil }
        return client.coverArtURL(id: String(path[path.index(after: slash)...]), size: size)
    }
}

/// Why connecting didn't work, in words for the sign-in form.
enum ConnectionError: Error, Equatable {
    case badAddress
    case missingSignIn
    case wrongCredentials
    case notSubsonic
    case unreachable(String)
    case other(String)

    init(_ error: SubsonicError) {
        switch error {
        case .wrongCredentials: self = .wrongCredentials
        case .notSubsonic, .http: self = .notSubsonic
        case .incompatible: self = .other("This server is too old for Tracks.")
        case .notFound: self = .notSubsonic
        case .server(_, let message): self = .other(message)
        }
    }

    var message: String {
        switch self {
        case .badAddress: "That doesn't look like a server address. Try one like 192.168.1.20:4533."
        case .missingSignIn: "Enter the username and password you use for your server."
        case .wrongCredentials: "The username or password isn't right."
        case .notSubsonic: "Something answered at that address, but it isn't a Subsonic music server."
        case .unreachable: "Tracks couldn't reach that address. Check it, and that your phone is on the same network as the server."
        case .other(let message): message
        }
    }
}
