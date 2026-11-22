import SwiftUI
import TracksCore

/// Plays per week or month on a detail page, with the selected bar read out in the header.
struct PlaysOverTimeCard: View {
    let timeline: [TimeBucket]
    let unit: Calendar.Component
    @State private var selected: TimeBucket?

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: Design.Spacing.medium) {
                HStack {
                    CardLabel(title: "Plays Over Time", systemImage: "chart.bar.fill")
                    Spacer()
                    if let selected {
                        ChartReadout(bucket: selected, unit: unit, measure: .plays)
                    }
                }
                .animation(Design.Motion.value, value: selected?.start)
                ActivityChart(buckets: timeline, unit: unit, measure: .plays, height: 150, selected: $selected)
            }
        }
    }
}
