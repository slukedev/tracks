import Foundation
import Observation

/// How often a moving background draws: every frame the display shows, so slow motion glides
/// at 120 Hz on ProMotion as it does at 60 elsewhere. Reduce Motion's calmer movement needs
/// fewer, and so does Low Power Mode, which keeps the motion at fewer frames.
enum BackdropFrameRate {
    /// For what moves with the music, Living Cover and Stage: every frame the display shows,
    /// as often as it refreshes, up to 120 times a second on ProMotion. Low Power Mode keeps
    /// the motion at 20.
    static var everyFrame: Double? {
        LowPowerMode.shared.cappedFrameRate(nil).map { 1 / $0 }
    }

    static func interval(reduceMotion: Bool) -> Double? {
        reduceMotion ? 1 / 20 : everyFrame
    }
}

@Observable
final class LowPowerMode {
    static let shared = LowPowerMode()
    private(set) var isOn = ProcessInfo.processInfo.isLowPowerModeEnabled
    private(set) var thermalState = ProcessInfo.processInfo.thermalState
    @ObservationIgnored private var observers: [any NSObjectProtocol] = []

    var isHot: Bool { thermalState == .serious || thermalState == .critical }
    var isOverheated: Bool { thermalState == .critical }

    func cappedFrameRate(_ frameRate: Double?) -> Double? {
        if isOn { return min(frameRate ?? 20, 20) }
        if isHot { return min(frameRate ?? 30, 30) }
        return frameRate
    }

    private init() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: nil) { _ in
                Task { @MainActor in
                    LowPowerMode.shared.isOn = ProcessInfo.processInfo.isLowPowerModeEnabled
                }
            },
            center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil) { _ in
                Task { @MainActor in
                    LowPowerMode.shared.thermalState = ProcessInfo.processInfo.thermalState
                }
            },
        ]
    }
}
