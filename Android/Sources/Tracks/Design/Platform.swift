import SwiftUI

/// SwiftUI modifiers Skip doesn't have on Android, where Compose already does the same job:
/// a clickable row merges its children's semantics and takes taps across its whole bounds.
extension View {
    /// Reads the view's children as one element to TalkBack and VoiceOver.
    @ViewBuilder
    func combinedAccessibility() -> some View {
        #if os(Android)
        self
        #else
        self.accessibilityElement(children: .combine)
        #endif
    }

    /// Replaces the children with the view's own accessibility label.
    @ViewBuilder
    func replacedAccessibility() -> some View {
        #if os(Android)
        self
        #else
        self.accessibilityElement(children: .ignore)
        #endif
    }

    /// Takes taps anywhere in the view's frame, not only on what's drawn.
    @ViewBuilder
    func fullTapArea() -> some View {
        #if os(Android)
        self
        #else
        self.contentShape(Rectangle())
        #endif
    }

    /// Marks the view selected for TalkBack. Only ever adds a trait: Skip overflows the stack
    /// bridging an empty `AccessibilityTraits`, so `isSelected ? .isSelected : []` can't be used.
    @ViewBuilder
    func selectedTrait(_ isSelected: Bool) -> some View {
        if isSelected {
            self.accessibilityAddTraits(.isSelected)
        } else {
            self
        }
    }

    /// A keyboard for typing addresses. Phones only.
    @ViewBuilder
    func addressKeyboard() -> some View {
        #if os(macOS)
        self
        #else
        self.keyboardType(.URL)
        #endif
    }

    /// A small title in the top app bar, for pages whose header already says what they are.
    @ViewBuilder
    func inlineTitle() -> some View {
        #if os(macOS)
        self
        #else
        self.navigationBarTitleDisplayMode(.inline)
        #endif
    }

    /// A page over everything, as Now Playing opens. A sheet where there's no full-screen cover.
    @ViewBuilder
    func fullScreenPage<Content: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> Content) -> some View {
        #if os(macOS)
        self.sheet(isPresented: isPresented, content: content)
        #else
        self.fullScreenCover(isPresented: isPresented, content: content)
        #endif
    }
}
