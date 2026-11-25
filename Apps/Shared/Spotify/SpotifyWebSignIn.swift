#if SPOTIFY
import Foundation
import AuthenticationServices
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Spotify's sign-in page in the system's web sheet, with the browser's own cookies so
/// someone signed in to Spotify there taps once. Spotify sends the browser back to an http
/// loopback address, which the sheet can't catch: the engine listens there instead, and the
/// sheet is closed once it has the answer.
@MainActor
final class SpotifyWebSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    /// Opens the page. `closedByPerson` runs if the sheet is closed before the answer comes.
    /// False if the sheet couldn't open.
    func show(_ url: URL, closedByPerson: @escaping @MainActor () -> Void) -> Bool {
        let session = Self.makeSession(url: url) {
            Task { @MainActor in closedByPerson() }
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        self.session = session
        return session.start()
    }

    func dismiss() {
        session?.cancel()
        session = nil
    }

    /// Made outside the main actor: the sheet answers on a queue of its own.
    private nonisolated static func makeSession(url: URL, closed: @escaping @Sendable () -> Void) -> ASWebAuthenticationSession {
        // A scheme nothing opens: the answer comes through the engine's loopback listener.
        ASWebAuthenticationSession(url: url, callback: .customScheme("tracks-spotify-signin")) { _, _ in
            closed()
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            #if canImport(UIKit)
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            if let key = scenes.flatMap(\.windows).first(where: \.isKeyWindow) { return key }
            if let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first {
                return UIWindow(windowScene: scene)
            }
            return ASPresentationAnchor()
            #else
            return NSApp.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor()
            #endif
        }
    }
}
#endif
