import WidgetKit
import SwiftUI
import AppIntents
import TracksCore

@main
struct TracksWidgetBundle: WidgetBundle {
    var body: some Widget {
        ListeningWidget()
        TopSongsWidget()
        LastCapturedWidget()
        TodayOnRadioWidget()
        PlayInTracksControl()
        IdentifySongControl()
    }
}

/// The most recent song kept.
struct LastCapturedWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: WidgetKind.lastPlayed,
            provider: CaptureTimelineProvider(content: .lastPlayed)
        ) { entry in
            // Sets its own background, from the cover.
            LastCapturedView(entry: entry)
                .tint(.tracks)
        }
        .configurationDisplayName("Last Played")
        .description("The last song you played.")
        .supportedFamilies([.systemSmall])
    }
}

/// Today's listening.
struct TodayOnRadioWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: WidgetKind.today,
            provider: CaptureTimelineProvider(content: .today)
        ) { entry in
            TodayOnRadioView(entry: entry)
                .tint(.tracks)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Today")
        .description("What you've played today.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}
