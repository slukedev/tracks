import Foundation

/// A problem as the car says it: a short title and the condition, nothing to do. The phone's
/// own words send people to Settings or another app, which a driver mustn't be asked to do.
struct CarProblem: Equatable {
    let title: String
    let message: String?

    init(title: String, message: String? = nil) {
        self.title = title
        self.message = message
    }

    init(_ problem: PlayerProblem) {
        let couldnt = String(localized: "Couldn’t Play")
        if problem == .accessNotAsked {
            self.init(title: String(localized: "Apple Music Access Is Off"))
            return
        }
        if problem == .musicTermsNeeded {
            self.init(title: String(localized: "Apple Music Isn’t Set Up"))
            return
        }
        switch problem {
        case .accessDenied:
            self.init(title: String(localized: "Apple Music Access Is Off"))
        case .needsSubscription:
            self.init(title: couldnt, message: String(localized: "No Apple Music subscription."))
        case .nothingToPlay:
            self.init(title: couldnt, message: String(localized: "These songs aren’t available."))
        case .onlyExplicit, .explicitSong:
            self.init(title: couldnt, message: String(localized: "Explicit songs are off."))
        case .notInYourMusic:
            self.init(title: couldnt, message: String(localized: "These songs aren’t in your music."))
        case .needsAppleMusic:
            self.init(title: couldnt, message: String(localized: "This plays from Apple Music."))
        case .blockedArtist(let name):
            self.init(title: couldnt, message: String(localized: "You blocked \(name)."))
        case .failed(let detail, let title):
            if let known = Self.known[detail] {
                self = known
            } else {
                self.init(title: title ?? couldnt, message: Self.firstSentence(of: detail))
            }
        }
    }

    /// The player's own words for a failure, by the same strings, so they match in every
    /// language.
    private static var known: [String: CarProblem] {
        let couldnt = String(localized: "Couldn’t Play")
        return [
            String(localized: "Tracks couldn’t connect. Check your internet connection, then try again."): CarProblem(title: String(localized: "No Connection")),
            String(localized: "Sign in to Apple Music in Settings, then try again."): CarProblem(title: couldnt, message: String(localized: "Not signed in to Apple Music.")),
            String(localized: "Apple Music couldn’t confirm your account just now. Try again in a moment."): CarProblem(title: couldnt, message: String(localized: "Apple Music didn’t respond.")),
            String(localized: "It took too long to start. Try again in a moment."): CarProblem(title: couldnt, message: String(localized: "It took too long to start.")),
            String(localized: "Something stopped it from starting. Try again in a moment."): CarProblem(title: couldnt),
        ]
    }

    /// The condition alone, without the advice that follows it.
    private static func firstSentence(of text: String) -> String? {
        var first: String?
        text.enumerateSubstrings(in: text.startIndex..<text.endIndex, options: .bySentences) { sentence, _, _, stop in
            first = sentence?.trimmingCharacters(in: .whitespacesAndNewlines)
            stop = true
        }
        return first.flatMap { $0.isEmpty ? nil : $0 }
    }
}
