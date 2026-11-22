import SwiftUI

/// Tracks' design tokens: one place for spacing, margins, sizes, radii, motion and haptics, so
/// every page measures and moves the same way. `docs/design.md` explains each and when to use it.
///
/// The older names (`Metrics`, `PlayMetrics`, `PlayMotion`, `LiveUpdate`) read from here.
enum Design {
    /// The spacing scale. Everything between two things on a page is one of these.
    enum Spacing {
        static let xxSmall: CGFloat = 2
        static let xSmall: CGFloat = 4
        static let small: CGFloat = 8
        static let medium: CGFloat = 12
        static let large: CGFloat = 16
        static let xLarge: CGFloat = 20
        static let xxLarge: CGFloat = 24
        static let xxxLarge: CGFloat = 32
    }

    /// Where a page's content sits. The large title, section headers, rows' text and the first
    /// tile of every shelf share `page` as their leading edge.
    enum Margin {
        #if os(macOS)
        static let page: CGFloat = 28
        /// Between one section of a page and the next.
        static let section: CGFloat = 36
        /// Between a section's header and its content.
        static let header: CGFloat = 10
        #else
        static let page: CGFloat = 20
        static let section: CGFloat = 28
        static let header: CGFloat = 10
        #endif
    }

    /// Shelves of covers that scroll sideways.
    enum Shelf {
        #if os(macOS)
        static let tile: CGFloat = 176
        static let spacing: CGFloat = 18
        #else
        static let tile: CGFloat = 160
        static let spacing: CGFloat = 12
        #endif
        /// The most a tile grows to with Dynamic Type, so one tile never fills the screen.
        static let maximumTile: CGFloat = 220
        /// Between a cover and the title under it.
        static let captionGap: CGFloat = 6
        /// Between a tile's title and its subtitle.
        static let lineGap: CGFloat = 1
    }

    /// Content panels: insight cards, the featured release, a well of stats.
    enum Card {
        #if os(macOS)
        static let radius: CGFloat = 16
        static let padding: CGFloat = 16
        /// Between cards in a row or a column.
        static let spacing: CGFloat = 16
        /// Between one titled group of cards and the next.
        static let sectionSpacing: CGFloat = 24
        #else
        static let radius: CGFloat = 26
        static let padding: CGFloat = 16
        static let spacing: CGFloat = 12
        static let sectionSpacing: CGFloat = 28
        #endif
    }

    enum Radius {
        /// A rounded rectangle inside another, inset by `inset`: concentric with it, never
        /// tighter than `minimum`. Prefer `ConcentricRectangle()` where the container shape is
        /// set (a `Card` sets it).
        static func inner(of outer: CGFloat, inset: CGFloat, minimum: CGFloat = 6) -> CGFloat {
            max(minimum, outer - inset)
        }
    }

    /// Buttons and floating controls.
    enum Control {
        #if os(macOS)
        /// A secondary action in a glass circle beside Play.
        static let glassCircle: CGFloat = 32
        /// The opaque Play capsule's height and its least width.
        static let playHeight: CGFloat = 32
        static let playMinimumWidth: CGFloat = 92
        /// Play alone, as a circle, on a hero.
        static let playCircle: CGFloat = 44
        /// Play over a cover, on a tile.
        static let coverButton: CGFloat = 28
        /// The least a pointer target is, drawn smaller or not.
        static let minimumTarget: CGFloat = 28
        #else
        static let glassCircle: CGFloat = 44
        static let playHeight: CGFloat = 48
        static let playMinimumWidth: CGFloat = 150
        static let playCircle: CGFloat = 64
        static let coverButton: CGFloat = 32
        static let minimumTarget: CGFloat = 44
        #endif
        /// Between the buttons of one row.
        static let spacing: CGFloat = 12
    }

    /// How Tracks moves, named by what's moving. Every animation answers the person's own
    /// action and is under 0.35 s; the colour settling and live figures are the two that
    /// answer data instead, and both fade rather than move. Under Reduce Motion, use
    /// ``Motion/reduced(_:reduceMotion:)``.
    enum Motion {
        /// A control answering a press.
        static let press = Animation.snappy(duration: 0.14)
        /// The pointer arriving over something on the Mac.
        static let hover = Animation.easeOut(duration: 0.13)
        /// A number or a word changing in place, with `.contentTransition(.numericText())`.
        static let value = Animation.snappy(duration: 0.25)
        /// A panel, a list or a page's part opening or closing.
        static let panel = Animation.smooth(duration: 0.3)
        /// Rows arriving, leaving or moving.
        static let row = Animation.smooth(duration: 0.28)
        /// Opacity only: for Reduce Motion, and for words that change.
        static let fade = Animation.easeOut(duration: 0.22)
        /// A cover's colour settling onto the page once it's read.
        static let tint = Animation.easeOut(duration: 0.4)
        /// The sleeve turning over: the one flourish, from a tap or click only.
        static let flip = Animation.spring(duration: 0.5, bounce: 0.12)
        /// New listening arriving on a screen that's already showing.
        static let live = Animation.smooth(duration: 0.5)

        /// `animation`, or a fade under Reduce Motion, so a change still eases but nothing moves.
        static func reduced(_ animation: Animation, reduceMotion: Bool) -> Animation {
            reduceMotion ? fade : animation
        }
    }

    /// What a tap feels like. Fired from a counter the action itself bumps, never from synced
    /// or loaded state.
    enum Haptic {
        /// Play, and anything that starts music.
        static let start = SensoryFeedback.impact(weight: .medium)
        /// Shuffle, Add, a secondary action beside Play.
        static let secondary = SensoryFeedback.impact(weight: .light)
        /// A choice among options: a filter, a scope, a mode.
        static let choice = SensoryFeedback.selection
        /// Something finished that took a while: a download, an import.
        static let done = SensoryFeedback.success
    }
}

extension Font {
    /// A page's title in its content: a Mac page's title, or a hero's name.
    static var pageTitle: Font {
        .largeTitle.bold()
    }

    /// An album's, playlist's or mix's name in its header.
    static var collectionTitle: Font {
        #if os(macOS)
        .system(size: 34, weight: .bold)
        #else
        .title2.bold()
        #endif
    }

    /// A section's or a shelf's header: 22 points on iPhone, 17 on the Mac.
    static var sectionTitle: Font {
        .title2.bold()
    }

    /// The first line of a row.
    static var rowTitle: Font {
        .body
    }

    /// The second line of a row, and a tile's caption.
    static var rowSubtitle: Font {
        .subheadline
    }

    /// The small word over a title ("ALBUM", "COMING JULY 24"), set in capitals by
    /// ``SwiftUI/View/eyebrowStyle()``.
    static var eyebrow: Font {
        #if os(macOS)
        .caption.weight(.semibold)
        #else
        .caption2.weight(.semibold)
        #endif
    }

    /// A figure that's the point of a panel: minutes listened, a play count.
    static var statFigure: Font {
        .system(.title, design: .rounded, weight: .bold).monospacedDigit()
    }
}

extension View {
    /// The eyebrow's capitals, tracking and colour.
    func eyebrowStyle() -> some View {
        font(.eyebrow)
            .textCase(.uppercase)
            .kerning(0.6)
            .foregroundStyle(.secondary)
    }
}
