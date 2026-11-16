import SwiftUI

/// Where Full Cover draws its picture, in the backdrop's own space, so the player can lift the
/// same picture out and carry it into the mini player.
struct FullCoverPicture: Equatable {
    /// The part of the cover drawn: its top left and width are the square's, its height cut
    /// short where the cover's foot runs on under the song.
    var shown: CGRect
    /// The whole square's side.
    var side: CGFloat
    /// How far into the picture it fades at each edge, as parts of the drawn picture's height
    /// or width.
    var top: Double
    var bottom: Double
    var leading: Double
    var trailing: Double
}

struct FullCoverPictureKey: PreferenceKey {
    static let defaultValue: FullCoverPicture? = nil

    static func reduce(value: inout FullCoverPicture?, nextValue: () -> FullCoverPicture?) {
        value = value ?? nextValue()
    }
}

/// The cover the player gathers into the mini player, marked with ``SwiftUI/View/coverHandoffSource()``.
struct CoverHandoffSourceKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

extension EnvironmentValues {
    /// Full Cover's picture is being carried above the player, so the backdrop leaves it out.
    @Entry var liftsFullCover = false
    /// The player's cover is being carried above it, so its own place stays empty.
    @Entry var coverIsLifted = false
    /// Now Playing is being dragged, or is opening or closing: a background can rest while it moves.
    @Entry var playerIsMoving = false
}

extension View {
    /// Marks this as the cover that travels into the mini player as Now Playing closes, and
    /// leaves its place empty while it's away.
    func coverHandoffSource(isEnabled: Bool = true) -> some View {
        modifier(CoverHandoffSource(isEnabled: isEnabled))
    }
}

private struct CoverHandoffSource: ViewModifier {
    let isEnabled: Bool
    @Environment(\.coverIsLifted) private var isLifted

    func body(content: Content) -> some View {
        content
            .opacity(isLifted && isEnabled ? 0 : 1)
            .transformAnchorPreference(key: CoverHandoffSourceKey.self, value: .bounds) { value, bounds in
                if isEnabled { value = bounds }
            }
    }
}
