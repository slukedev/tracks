import SwiftUI
import MotifStats

/// Listening for a week, month, year or all time: the numbers first, then who and what.
struct SummaryScreen: View {
    @Environment(AppModel.self) var model
    @AppStorage("summaryRange") var range: StatsRange = .month
    @State var summary: StatsSummary?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.cards) {
                Picker("Period", selection: $range) {
                    ForEach(StatsRange.allCases, id: \.self) { range in
                        Text(range.title).tag(range)
                    }
                }
                .pickerStyle(.segmented)

                if let summary {
                    content(summary)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 64)
                }
            }
            .padding(.horizontal, Space.screen)
            .padding(.bottom, 24)
        }
        .navigationTitle("Stats")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: Route.history) {
                    SymbolImage(.history, role: .onSurfaceVariant)
                        .frame(width: Space.touchTarget, height: Space.touchTarget)
                }
                .accessibilityLabel("History")
            }
        }
        .task(id: "\(range.rawValue)|\(model.revision)") {
            let (range, history) = (range, model.history)
            let next = await Task.detached(priority: .userInitiated) {
                StatsCalculator.summary(range: range, history: history, sessions: [], topLimit: 10, songLimit: 5)
            }.value
            summary = next
        }
    }

    @ViewBuilder
    private func content(_ summary: StatsSummary) -> some View {
        ListeningCard(summary: summary)
        GlanceGrid(summary: summary)

        if !summary.topArtists.isEmpty {
            SectionHeader(title: "Top artists", destination: .charts(.artists))
            TopArtistsRow(artists: summary.topArtists)
        }

        if !summary.topSongs.isEmpty {
            SectionHeader(title: "Top songs", destination: .charts(.songs))
            TopSongsCard(songs: Array(summary.topSongs.prefix(5)))
        }

        let insights = summary.insights.filter(\.appliesToAndroid)
        if !insights.isEmpty {
            SectionHeader(title: "Highlights")
            ForEach(insights.prefix(4)) { insight in
                HighlightCard(insight: insight)
            }
        }

        if summary.captureCount > 0 {
            SectionHeader(title: "When you listen")
            DayPartsCard(parts: summary.dayParts)
        }
    }
}

// MARK: - Listening

/// The headline number, how it compares, and a bar for every day (or month) in the period.
struct ListeningCard: View {
    let summary: StatsSummary

    var body: some View {
        MaterialCard(role: .surfaceContainerLow) {
            HStack(spacing: 8) {
                SymbolImage(.listening, size: 20, role: .primary)
                Text("Listening")
                    .materialType(.titleSmall)
                    .materialForeground(.primary)
                Spacer()
                Text(Format.period(summary))
                    .materialType(.bodyMedium)
                    .materialForeground(.onSurfaceVariant)
            }

            total
                .padding(.top, 12)

            if let change {
                HStack(spacing: 6) {
                    SymbolImage(change >= 0 ? .trendingUp : .trendingDown, size: 18, role: .onSurfaceVariant)
                    Text("\(Format.percentChange(change)) from \(Format.previous(summary.range))")
                        .materialType(.bodyMedium)
                        .materialForeground(.onSurfaceVariant)
                }
                .padding(.top, 4)
            }

            BarChart(
                bars: bars,
                average: averagePerBar,
                height: 150,
                scaleLabel: { hours in Format.listening(hours * 3600) }
            )
            .padding(.top, 20)

            HStack(alignment: .top) {
                Fact(label: "Daily average", value: Format.listening(summary.dailyAverageSeconds))
                Spacer()
                Fact(label: "Plays", value: Format.count(summary.captureCount))
                Spacer()
                Fact(label: "Active days", value: Format.count(summary.activeDays))
            }
            .padding(.top, 20)
        }
    }

    private var total: some View {
        let parts = Format.listeningParts(summary.listeningSeconds)
        return HStack(alignment: .firstTextBaseline, spacing: 4) {
            if parts.hours > 0 {
                Text("\(parts.hours.formatted())")
                    .materialType(.displayMedium)
                    .materialForeground(.onSurface)
                Text("hr")
                    .materialType(.titleLarge)
                    .materialForeground(.onSurfaceVariant)
                    .padding(.trailing, 8)
            }
            Text("\(parts.minutes)")
                .materialType(.displayMedium)
                .materialForeground(.onSurface)
            Text("min")
                .materialType(.titleLarge)
                .materialForeground(.onSurfaceVariant)
        }
        .replacedAccessibility()
        .accessibilityLabel("\(parts.hours) hours \(parts.minutes) minutes listening")
    }

    /// Against the same stretch of the period before.
    private var change: Double? {
        guard summary.range != .allTime,
              let previous = summary.previousListeningSeconds, previous > 0
        else { return nil }
        return summary.listeningSeconds / previous - 1
    }

    private var bars: [Bar] {
        let now = Date.now
        let calendar = Calendar.current
        let timeline = summary.timeline
        return timeline.enumerated().map { index, bucket in
            Bar(
                id: "\(bucket.start.timeIntervalSince1970)",
                value: bucket.seconds / 3600,
                label: label(for: bucket.start, index: index, count: timeline.count, calendar: calendar),
                isFuture: bucket.start > now
            )
        }
    }

    private var averagePerBar: Double? {
        guard summary.timelineUnit == .day, summary.dailyAverageSeconds > 0 else { return nil }
        return summary.dailyAverageSeconds / 3600
    }

    /// Every day of a week, every seventh of a month, every month's initial across a year.
    private func label(for date: Date, index: Int, count: Int, calendar: Calendar) -> String? {
        switch summary.timelineUnit {
        case .day where count <= 7:
            return String(date.formatted(.dateTime.weekday(.narrow)))
        case .day:
            return index % 7 == 0 ? date.formatted(.dateTime.month(.abbreviated).day()) : nil
        case .month where count <= 12:
            return String(date.formatted(.dateTime.month(.narrow)))
        default:
            let stride = max(1, count / 5)
            return index % stride == 0 ? date.formatted(.dateTime.year()) : nil
        }
    }
}

// MARK: - At a glance

/// Four numbers in a two-column grid, each with its own tonal symbol.
struct GlanceGrid: View {
    let summary: StatsSummary

    var body: some View {
        VStack(spacing: Space.grid) {
            HStack(spacing: Space.grid) {
                GlanceTile(symbol: .artist, container: .primaryContainer, content: .onPrimaryContainer,
                           value: Format.count(summary.uniqueArtistCount), label: "Artists")
                GlanceTile(symbol: .song, container: .secondaryContainer, content: .onSecondaryContainer,
                           value: Format.count(summary.uniqueSongCount), label: "Different songs")
            }
            HStack(spacing: Space.grid) {
                GlanceTile(symbol: .newToYou, container: .tertiaryContainer, content: .onTertiaryContainer,
                           value: Format.count(summary.firstTimeHeardCount), label: "New to you",
                           detail: summary.newArtistCount == 1 ? "1 new artist" : "\(summary.newArtistCount) new artists")
                GlanceTile(symbol: .streak, container: .primaryContainer, content: .onPrimaryContainer,
                           value: summary.streak.current == 1 ? "1 day" : "\(summary.streak.current) days", label: "Streak",
                           detail: "Best: \(summary.streak.longest) days")
            }
        }
    }
}

struct GlanceTile: View {
    let symbol: Symbol
    let container: MaterialRole
    let content: MaterialRole
    let value: String
    let label: String
    var detail: String?

    var body: some View {
        MaterialCard(role: .surfaceContainerLow) {
            IconBadge(symbol: symbol, container: container, content: content, size: 40)
            Text(value)
                .materialType(.headlineSmall)
                .materialForeground(.onSurface)
                .lineLimit(1)
                .padding(.top, 12)
            Text(label)
                .materialType(.bodyMedium)
                .materialForeground(.onSurfaceVariant)
                .lineLimit(1)
            Text(detail ?? " ")
                .materialType(.bodySmall)
                .materialForeground(.onSurfaceVariant)
                .lineLimit(1)
        }
        .combinedAccessibility()
    }
}

// MARK: - Top artists and songs

/// The period's favourite artists, side by side, to scroll through.
struct TopArtistsRow: View {
    let artists: [ArtistTally]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: Space.screen) {
                ForEach(artists) { artist in
                    NavigationLink(value: Route.artist(artist.id)) {
                        VStack(spacing: 8) {
                            Monogram(name: artist.name, size: 88)
                            VStack(spacing: 0) {
                                Text(artist.name)
                                    .materialType(.labelLarge)
                                    .materialForeground(.onSurface)
                                    .lineLimit(1)
                                Text(Format.plays(artist.count))
                                    .materialType(.bodySmall)
                                    .materialForeground(.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                        }
                        .frame(width: 96)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// The period's top five songs, ranked, in one container.
struct TopSongsCard: View {
    let songs: [SongTally]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                NavigationLink(value: Route.song(song.id)) {
                    ListRow(headline: song.title, supporting: song.artistName) {
                        HStack(spacing: 12) {
                            Text("\(index + 1)")
                                .materialType(.titleMedium)
                                .materialForeground(.onSurfaceVariant)
                                .frame(width: 20)
                            Artwork(url: song.artworkURL, size: 48)
                        }
                    } trailing: {
                        Text(Format.plays(song.count))
                            .materialType(.labelMedium)
                            .materialForeground(.onSurfaceVariant)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 8)
        .materialBackground(.surfaceContainerLow, cornerRadius: Corner.medium)
    }
}

// MARK: - When and where

/// Morning, afternoon, evening and night, each as a share of the period's listening.
struct DayPartsCard: View {
    let parts: [DayPartCount]

    var body: some View {
        let total = max(parts.reduce(0) { $0 + $1.seconds }, 1)
        MaterialCard(role: .surfaceContainerLow) {
            VStack(spacing: 14) {
                ForEach(parts) { part in
                    ShareRow(
                        symbol: part.part.symbol,
                        title: part.part.title,
                        detail: Format.listening(part.seconds),
                        fraction: part.seconds / total
                    )
                }
            }
        }
    }
}

struct ShareRow: View {
    let symbol: Symbol
    let title: String
    let detail: String
    let fraction: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                SymbolImage(symbol, size: 20, role: .onSurfaceVariant)
                Text(title)
                    .materialType(.bodyLarge)
                    .materialForeground(.onSurface)
                Spacer()
                Text(detail)
                    .materialType(.labelLarge)
                    .materialForeground(.onSurfaceVariant)
            }
            ShareBar(fraction: fraction)
        }
        .combinedAccessibility()
    }
}

extension DayPart {
    var title: String {
        switch self {
        case .morning: "Morning"
        case .afternoon: "Afternoon"
        case .evening: "Evening"
        case .night: "Night"
        }
    }

    var symbol: Symbol {
        switch self {
        case .morning: .earlyBird
        case .afternoon: .weekend
        case .evening: .clock
        case .night: .nightOwl
        }
    }
}
