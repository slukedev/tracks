import SwiftUI

struct FullCoverPicture: Equatable {
    var shown: CGRect
    var side: CGFloat
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

struct CoverHandoffSourceKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

extension EnvironmentValues {
    @Entry var liftsFullCover = false
    @Entry var coverIsLifted = false
    @Entry var playerIsMoving = false
}

extension View {
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
