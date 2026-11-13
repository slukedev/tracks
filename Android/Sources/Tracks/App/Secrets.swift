import Foundation
import SkipFuse

/// Secrets Tracks keeps, such as the music server's password. On Android they're encrypted with a
/// key in the Android Keystore (`SecretStore.kt`); elsewhere they last only as long as the process.
@MainActor enum Secrets {
    #if !os(Android)
    private static var memory: [String: String] = [:]
    #endif

    static func read(_ name: String) -> String? {
        #if os(Android)
        do {
            let value: String? = try store().read(name)
            return value
        } catch {
            logger.error("Couldn't read the secret \(name): \(error)")
            return nil
        }
        #else
        return memory[name]
        #endif
    }

    /// Keeps `value` under `name`, and says whether it was kept.
    @discardableResult
    static func write(_ value: String, for name: String) -> Bool {
        #if os(Android)
        do {
            let kept: Bool? = try store().write(name, value)
            return kept ?? false
        } catch {
            logger.error("Couldn't keep the secret \(name): \(error)")
            return false
        }
        #else
        memory[name] = value
        return true
        #endif
    }

    static func remove(_ name: String) {
        #if os(Android)
        do {
            let _: Void = try store().remove(name)
        } catch {
            logger.error("Couldn't remove the secret \(name): \(error)")
        }
        #else
        memory[name] = nil
        #endif
    }

    #if os(Android)
    private static func store() throws -> AnyDynamicObject {
        try AnyDynamicObject(forStaticsOfClassName: "tracks.module.SecretStore")
    }
    #endif
}
