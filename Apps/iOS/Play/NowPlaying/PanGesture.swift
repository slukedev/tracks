import SwiftUI
import UIKit

/// A drag measured on the screen, from UIKit's own pan, which runs alongside every other
/// gesture. SwiftUI's drags never begin inside the tab bar's accessory, where the mini player
/// lives, so its swipes come through this.
struct PanGesture: UIGestureRecognizerRepresentable {
    struct Value {
        var translation: CGSize
        var velocity: CGSize

        var predictedEndTranslation: CGSize {
            CGSize(width: translation.width + velocity.width * 0.25, height: translation.height + velocity.height * 0.25)
        }
    }

    var isEnabled = true
    var onChanged: (Value) -> Void
    var onEnded: (Value) -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator()
    }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let recognizer = UIPanGestureRecognizer()
        recognizer.delegate = context.coordinator
        recognizer.maximumNumberOfTouches = 1
        recognizer.isEnabled = isEnabled
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        recognizer.isEnabled = isEnabled
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let space = recognizer.view?.window
        let translation = recognizer.translation(in: space)
        let velocity = recognizer.velocity(in: space)
        var value = Value(translation: CGSize(width: translation.x, height: translation.y), velocity: CGSize(width: velocity.x, height: velocity.y))
        switch recognizer.state {
        case .began, .changed:
            onChanged(value)
        case .ended:
            onEnded(value)
        case .cancelled, .failed:
            value.velocity = .zero
            onEnded(value)
        default:
            break
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }
    }
}
