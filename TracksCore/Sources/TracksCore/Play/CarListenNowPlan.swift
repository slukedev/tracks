import Foundation

/// What Listen Now leads with in the car, and which cards follow it: one thing to start with a
/// single tap at the top, then up to four more, each shown once.
public struct CarListenNowPlan: Equatable, Sendable {
    public enum Lead: Equatable, Sendable {
        /// The song left paused when Tracks last closed.
        case continueListening
        case tracksRadio
        /// The mood made for the car, before there's enough listening for Tracks Radio.
        case driving
    }

    public enum Card: Equatable, Sendable {
        case tracksRadio
        case driving
        case mix(id: String)
        case discover
    }

    public static let maximumCards = 4

    public let lead: Lead
    public let cards: [Card]

    /// - Parameters:
    ///   - rightNow: The mix for this time of day, if there is one.
    ///   - otherMixes: The rest of the mixes, best first.
    public init(hasContinue: Bool, hasTracksRadio: Bool, rightNow: String?, otherMixes: [String], hasDiscover: Bool) {
        lead = hasContinue ? .continueListening : hasTracksRadio ? .tracksRadio : .driving
        var cards: [Card] = []
        if lead == .continueListening {
            cards.append(hasTracksRadio ? .tracksRadio : .driving)
        }
        let mixes = ([rightNow].compactMap(\.self) + otherMixes).reduce(into: [String]()) { kept, id in
            if !kept.contains(id) { kept.append(id) }
        }
        if let first = mixes.first { cards.append(.mix(id: first)) }
        if hasDiscover { cards.append(.discover) }
        for id in mixes.dropFirst() where cards.count < Self.maximumCards {
            cards.append(.mix(id: id))
        }
        self.cards = Array(cards.prefix(Self.maximumCards))
    }

    /// The mixes on a card, so the shelf of mixes below can leave them out.
    public var cardedMixes: Set<String> {
        Set(cards.compactMap { card in
            if case .mix(let id) = card { id } else { nil }
        })
    }
}
