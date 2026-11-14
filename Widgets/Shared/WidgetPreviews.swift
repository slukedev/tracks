import SwiftUI
import WidgetKit

// The Today widget at both sizes, and Last Played, since the widget process is hard to watch.

#Preview("Medium", as: .systemMedium) {
    TodayOnRadioWidget()
} timeline: {
    CaptureEntry.sample(capacity: .today(family: .systemMedium))
}

#Preview("Large", as: .systemLarge) {
    TodayOnRadioWidget()
} timeline: {
    CaptureEntry.sample(capacity: .today(family: .systemLarge))
}

#Preview("Last Played", as: .systemSmall) {
    LastCapturedWidget()
} timeline: {
    CaptureEntry.sample(capacity: .lastPlayed)
}
