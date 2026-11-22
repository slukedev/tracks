import SwiftUI

/// Spacing for Settings, named for where it goes, from the app's own scale.
enum SettingsSpacing {
    /// Between a title and its detail, a glyph and its text.
    static let tight = Design.Spacing.xSmall
    /// Inside a row.
    static let standard = Design.Spacing.small
    /// Between a tile and its text.
    static let row = Design.Spacing.medium
}

/// Settings' names for the app's motion tokens. Every one is under 0.35 s.
enum SettingsMotion {
    /// Rows and sections arriving or leaving as a switch turns something on.
    static let row = Design.Motion.row
    /// A number rolling to a new value, with `.contentTransition(.numericText())`.
    static let value = Design.Motion.value
    /// Opacity only, for Reduce Motion and for footers that change their words.
    static let fade = Design.Motion.fade

    /// Rows moving, or only fading under Reduce Motion.
    static func row(reduceMotion: Bool) -> Animation {
        Design.Motion.reduced(row, reduceMotion: reduceMotion)
    }
}
