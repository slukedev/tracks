import Foundation
import Testing
@testable import TracksCore

@Suite("Now Playing's buttons in the car")
struct CarNowPlayingButtonsTests {
    private enum Slot: Equatable { case shuffle, moreLikeThis, autoplay, favorite, notForMe }

    private func slots(_ row: [CarNowPlayingButton]) -> [Slot] {
        row.map { button in
            switch button {
            case .shuffle: .shuffle
            case .moreLikeThis: .moreLikeThis
            case .autoplay: .autoplay
            case .favorite: .favorite
            case .notForMe: .notForMe
            }
        }
    }

    @Test("always four, in the same places, whatever is on", arguments: [
        CarNowPlayingButton.Playing.nothing, .queue, .station,
        .live(canAskForMore: true, askedForMore: false), .live(canAskForMore: false, askedForMore: false),
    ])
    func fixedPlaces(_ playing: CarNowPlayingButton.Playing) {
        let row = CarNowPlayingButton.row(for: playing, autoplay: true, canFavorite: true, isFavorite: true)
        let first: Slot = if case .live = playing { .moreLikeThis } else { .shuffle }
        #expect(slots(row) == [first, .autoplay, .favorite, .notForMe])
    }

    @Test("a queue can shuffle and run on with Autoplay")
    func queue() {
        let row = CarNowPlayingButton.row(for: .queue, autoplay: true, canFavorite: true, isFavorite: false)
        #expect(row == [
            .shuffle(isEnabled: true),
            .autoplay(isEnabled: true, isSelected: true),
            .favorite(isEnabled: true, isSelected: false),
            .notForMe(isEnabled: true),
        ])
    }

    @Test("a station disables what it decides itself, and keeps the rest")
    func station() {
        let row = CarNowPlayingButton.row(for: .station, autoplay: true, canFavorite: true, isFavorite: true)
        #expect(row == [
            .shuffle(isEnabled: false),
            .autoplay(isEnabled: false, isSelected: false),
            .favorite(isEnabled: true, isSelected: true),
            .notForMe(isEnabled: true),
        ])
    }

    @Test("More Like This lights once asked, and is off where it can't be asked")
    func moreLikeThis() {
        let asked = CarNowPlayingButton.row(for: .live(canAskForMore: true, askedForMore: true), autoplay: false, canFavorite: false, isFavorite: false)
        #expect(asked.first == .moreLikeThis(isEnabled: true, isSelected: true))
        let cannot = CarNowPlayingButton.row(for: .live(canAskForMore: false, askedForMore: true), autoplay: false, canFavorite: false, isFavorite: false)
        #expect(cannot.first == .moreLikeThis(isEnabled: false, isSelected: false))
    }

    @Test("a song that can't be rated never shows as a favorite, and nothing on disables everything")
    func disabledStates() {
        let unrated = CarNowPlayingButton.row(for: .queue, autoplay: false, canFavorite: false, isFavorite: true)
        #expect(unrated[2] == .favorite(isEnabled: false, isSelected: false))
        let nothing = CarNowPlayingButton.row(for: .nothing, autoplay: true, canFavorite: true, isFavorite: true)
        #expect(nothing.allSatisfy { !$0.isEnabled && !$0.isSelected })
    }
}

@Suite("What Listen Now leads with in the car")
struct CarListenNowPlanTests {
    @Test("the song left paused leads, with Tracks Radio first among the cards")
    func continueLeads() {
        let plan = CarListenNowPlan(hasContinue: true, hasTracksRadio: true, rightNow: "evening", otherMixes: ["a", "b", "c"], hasDiscover: true)
        #expect(plan.lead == .continueListening)
        #expect(plan.cards == [.tracksRadio, .mix(id: "evening"), .discover, .mix(id: "a")])
        #expect(plan.cardedMixes == ["evening", "a"])
    }

    @Test("Driving stands in for Tracks Radio until there's enough listening for it")
    func drivingStandsIn() {
        let continuing = CarListenNowPlan(hasContinue: true, hasTracksRadio: false, rightNow: nil, otherMixes: [], hasDiscover: false)
        #expect(continuing.cards == [.driving])
        let fresh = CarListenNowPlan(hasContinue: false, hasTracksRadio: false, rightNow: nil, otherMixes: [], hasDiscover: false)
        #expect(fresh.lead == .driving)
        #expect(fresh.cards.isEmpty)
    }

    @Test("Tracks Radio leads when nothing was left paused, and isn't a card as well")
    func radioLeads() {
        let plan = CarListenNowPlan(hasContinue: false, hasTracksRadio: true, rightNow: "morning", otherMixes: ["morning", "a", "b", "c", "d"], hasDiscover: false)
        #expect(plan.lead == .tracksRadio)
        #expect(!plan.cards.contains(.tracksRadio))
        #expect(plan.cards == [.mix(id: "morning"), .mix(id: "a"), .mix(id: "b"), .mix(id: "c")])
    }

    @Test("without a mix for right now, the best mix comes before Discover")
    func noRightNow() {
        let plan = CarListenNowPlan(hasContinue: false, hasTracksRadio: true, rightNow: nil, otherMixes: ["a", "b"], hasDiscover: true)
        #expect(plan.cards == [.mix(id: "a"), .discover, .mix(id: "b")])
    }
}

@Suite("Long car lists by letter")
struct LetterSectionsTests {
    private let english = Locale(identifier: "en_US")

    @Test("letters in order, accents folded, and digits and symbols last under #")
    func grouping() {
        let names = ["ABBA", "Étienne Daho", "elbow", "2Pac", "Bon Iver", "!!!", "adele"]
        let sections = LetterSection.sections(for: names, maximumSections: 50, locale: english)
        #expect(sections == [
            LetterSection(letter: "A", indices: [0, 6]),
            LetterSection(letter: "B", indices: [4]),
            LetterSection(letter: "E", indices: [1, 2]),
            LetterSection(letter: "#", indices: [3, 5]),
        ])
    }

    @Test("past the car's limit, the smallest neighbours share a section and no name is lost")
    func merging() {
        let names = ["Air", "Arca", "Arlo", "Beck", "Cher", "Dido", "Doves", "Drake"]
        let sections = LetterSection.sections(for: names, maximumSections: 3, locale: english)
        #expect(sections.count == 3)
        #expect(sections.map(\.letter) == ["A", "B", "D"])
        #expect(sections.flatMap(\.indices) == Array(names.indices))
    }

    @Test("nothing to group, or no room at all, gives no sections")
    func empty() {
        #expect(LetterSection.sections(for: [], maximumSections: 10).isEmpty)
        #expect(LetterSection.sections(for: ["Air"], maximumSections: 0).isEmpty)
    }
}
