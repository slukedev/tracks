import SwiftUI
import TracksCore

/// The listening for the range on the page itself, as Health and Replay open: the period with
/// its steps, one big figure, how it compares, the bars, and three small figures under them.
struct ListeningHero: View {
    let summary: StatsSummary
    var chartHeight: CGFloat = 190
    /// Lets the chart grow past `chartHeight`, so a card can match the column beside it.
    var fillsHeight = false
    /// What the chart's "avg" tag is drawn on, so it reads where the line crosses a bar.
    var plate: Color = .pageBackground
    @State private var selected: TimeBucket?
    @Environment(\.statsPaging) private var paging
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Spacing.large) {
            VStack(alignment: .leading, spacing: Design.Spacing.xSmall) {
                period
                ListeningTotal(seconds: selected?.seconds ?? summary.listeningSeconds)
                Group {
                    if let selected {
                        Text(ActivityChart.dateLabel(selected.start, unit: summary.timelineUnit))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        ListeningComparison(summary: summary)
                    }
                }
                .transition(.opacity)
            }
            .accessibilityElement(children: .combine)
            // A bar read, or another period stepped to, rolls the figure. New listening
            // arriving on its own doesn't.
            .animation(Design.Motion.reduced(Design.Motion.value, reduceMotion: reduceMotion), value: selected?.start)
            .animation(Design.Motion.reduced(Design.Motion.value, reduceMotion: reduceMotion), value: periodKey)

            if summary.timelineIsInformative {
                ActivityChart(
                    buckets: summary.timeline,
                    unit: summary.timelineUnit,
                    average: averagePerBucket,
                    height: chartHeight,
                    fillsHeight: fillsHeight,
                    selected: $selected
                )
                .environment(\.chartPlate, plate)
                .animation(Design.Motion.reduced(Design.Motion.value, reduceMotion: reduceMotion), value: periodKey)
            }

            AdaptiveStack(spacing: Design.Spacing.small) {
                StatFootnote(title: "Daily Average", value: Text(Format.listening(summary.dailyAverageSeconds)))
                Spacer(minLength: 0)
                StatFootnote(title: "Plays", value: Text(summary.captureCount.formatted()))
                Spacer(minLength: 0)
                StatFootnote(title: "Active Days", value: Text(summary.activeDays.formatted()))
            }
        }
        .onChange(of: periodKey) { selected = nil }
    }

    @ViewBuilder
    private var period: some View {
        if let paging, summary.range != .allTime {
            PeriodPager(summary: summary, paging: paging)
        } else {
            Text(summary.periodTitle)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }

    /// Changes when the person moves to another range or period, never with a new play.
    private var periodKey: String {
        "\(summary.range.rawValue)|\(summary.periodOffset)"
    }

    /// The dashed line: average per elapsed bucket, in minutes.
    private var averagePerBucket: Double? {
        let elapsed = summary.timeline.filter { $0.start <= summary.generatedAt }
        guard elapsed.count > 1 else { return nil }
        return elapsed.reduce(0) { $0 + $1.seconds } / Double(elapsed.count) / 60
    }
}

/// The Mac dashboard's version of the hero, in a panel beside the figures.
struct ListeningCard: View {
    let summary: StatsSummary
    var chartHeight: CGFloat = 170
    var fillsHeight = false

    var body: some View {
        Card {
            ListeningHero(summary: summary, chartHeight: chartHeight, fillsHeight: fillsHeight, plate: .cardFill)
        }
    }
}

/// How the range compares with the one before, or how far back all time goes.
struct ListeningComparison: View {
    let summary: StatsSummary

    var body: some View {
        Group {
            if let change = summary.listeningChange, abs(change) < 0.03, summary.range != .allTime {
                Label {
                    Text("About the same as \(summary.previousPhrase)")
                } icon: {
                    Image(systemName: "equal")
                }
            } else if let change = summary.listeningChange, summary.range != .allTime {
                Label {
                    Text("\(Format.percentChange(change)) from \(summary.previousPhrase)")
                } icon: {
                    Image(systemName: change >= 0 ? "arrow.up.right" : "arrow.down.right")
                }
            } else if summary.range == .allTime, let first = summary.timeline.first(where: { $0.seconds > 0 }) {
                // A yearly bar starts in January, whenever in the year the first song was.
                let since = summary.timelineUnit == .year
                    ? first.start.formatted(.dateTime.year())
                    : first.start.formatted(.dateTime.month(.wide).year())
                Text("Since \(since)")
            } else {
                Text("^[\(summary.captureCount) song](inflect: true) from ^[\(summary.uniqueArtistCount) artist](inflect: true)")
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .contentTransition(.numericText())
    }
}

/// "18 hr 20 min" with big numbers and small units.
struct ListeningTotal: View {
    let seconds: TimeInterval
    var numberFont: Font = .system(.largeTitle, design: .rounded, weight: .bold)

    var body: some View {
        Self.text(seconds, numberFont: numberFont)
            .font(.title3.weight(.semibold))
            .foregroundStyle(.secondary)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .contentTransition(.numericText(value: seconds))
            .accessibilityLabel(Format.listening(seconds))
    }

    /// The numbers in `numberFont` and the primary colour, the units left to take whatever
    /// font and colour surround them, so the result can sit inside a longer sentence.
    static func text(_ seconds: TimeInterval, numberFont: Font) -> Text {
        let parts = Format.listeningParts(seconds)
        func number(_ value: Int) -> Text {
            Text(value.formatted())
                .font(numberFont)
                .foregroundStyle(.primary)
        }
        if parts.hours > 0, parts.minutes == 0 {
            return Text("\(number(parts.hours)) hr")
        } else if parts.hours > 0 {
            return Text("\(number(parts.hours)) hr \(number(parts.minutes)) min")
        } else {
            return Text("\(number(parts.minutes)) min")
        }
    }
}

/// The hero's shape before the first summary is worked out, so the page never opens on an
/// empty state or on zeros.
struct ListeningHeroPlaceholder: View {
    var chartHeight: CGFloat = 190

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Spacing.large) {
            VStack(alignment: .leading, spacing: Design.Spacing.xSmall) {
                Text(verbatim: "November 2026")
                    .font(.subheadline.weight(.semibold))
                Text(verbatim: "37 hr 5 min")
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                Text(verbatim: "+20% from this time last month")
                    .font(.subheadline)
            }
            .redacted(reason: .placeholder)
            RoundedRectangle(cornerRadius: Design.Radius.inner(of: Design.Card.radius, inset: Design.Spacing.small), style: .continuous)
                .fill(Color.placeholderFill)
                .frame(height: chartHeight)
            HStack {
                ForEach(0..<3, id: \.self) { index in
                    if index > 0 { Spacer(minLength: 0) }
                    VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
                        Text(verbatim: "Daily").font(.caption)
                        Text(verbatim: "1h 40m").font(.subheadline.weight(.semibold))
                    }
                }
            }
            .redacted(reason: .placeholder)
        }
        .accessibilityHidden(true)
    }
}

#if DEBUG
#Preview("Hero") {
    ScrollView {
        ListeningHero(summary: .preview())
            .padding(Design.Margin.page)
    }
}

#Preview("Hero, all time, dark") {
    ScrollView {
        ListeningHero(summary: .preview(.allTime))
            .padding(Design.Margin.page)
    }
    .preferredColorScheme(.dark)
}

#Preview("Hero, AX5") {
    ScrollView {
        ListeningHero(summary: .preview())
            .padding(Design.Margin.page)
    }
    .dynamicTypeSize(.accessibility5)
}

#Preview("Placeholder") {
    ListeningHeroPlaceholder()
        .padding(Design.Margin.page)
}

#Preview("Card") {
    ListeningCard(summary: .preview())
        .padding(Design.Margin.page)
}
#endif
