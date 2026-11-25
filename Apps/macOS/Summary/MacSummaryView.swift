import SwiftUI
import TracksCore

/// Summary on the Mac: the same cards as the iPhone, laid out as a dashboard.
struct MacSummaryView: View {
    @Environment(AppModel.self) private var model
    @SeenRevision private var revision
    @AppStorage("statsRange") private var range: StatsRange = .month
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Worked out since the page appeared. Until then, the one it last showed for the same
    /// period, from ``SummaryMemory``.
    @State private var computed: StatsSummary?
    /// 0 for this week, month or year; -1 for the one before. Back to 0 when the range changes.
    @State private var periodOffset = LaunchScene.periodOffset
    private var sources = SourceScopeSetting()

    private var key: SummaryMemory.Key {
        SummaryMemory.Key(range: range, periodOffset: periodOffset, scope: sources.scope, revision: revision)
    }

    /// Coming back to Summary draws the dashboard it left at once, rather than its skeleton
    /// and then the dashboard, and catches up only if something was played since.
    private var summary: StatsSummary? {
        computed ?? SummaryMemory.summary(for: key)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: Design.Card.sectionSpacing) {
                    StoreWarningBanner()

                    if model.library.isLoaded, model.library.history.isEmpty {
                        WelcomeView()
                            .padding(.top, Design.Spacing.xxxLarge)
                    } else if let summary {
                        // Hides itself once Apple Music access is granted.
                        MusicAccessCard()
                        if summary.captureCount == 0 {
                            NothingPlayedView(summary: summary)
                                            .padding(.top, Design.Spacing.xxxLarge)
                        } else {
                            Dashboard(summary: summary)
                        }
                    } else {
                        // All Time over years of history takes a moment: the dashboard's
                        // shape rather than an empty window while it's worked out.
                        DashboardSkeleton()
                    }
                }
                .padding(Design.Margin.page)
                // Room for a dashboard on a big display, short of stretching every chart.
                .frame(maxWidth: 1480)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: summary != nil) {
                if let anchor = LaunchScene.scrollAnchor {
                    proxy.scrollTo(anchor, anchor: .top)
                }
            }
        }
        .groupedBackground()
        .navigationTitle("Summary")
        .toolbar {
            ToolbarItem(placement: .principal) {
                // Sized by its segments, so the insets either side match, as in Calendar.
                RangePicker(range: $range)
                    .fixedSize()
            }
            if sources.isOffered {
                ToolbarItem(placement: .primaryAction) {
                    SourceScopeMenu(scope: sources.selection, offered: sources.offered)
                }
            }
        }
        .task(id: "\(range.rawValue)|\(periodOffset)|\(sources.scope.rawValue)|\(revision)") {
            // Not from the empty history before it's read, which would say nothing was played
            // and then animate the real week in as if it had just been heard.
            guard model.library.isLoaded else { return }
            let key = key
            if computed == nil, let remembered = SummaryMemory.summary(for: key), SummaryMemory.isCurrent(key) {
                computed = remembered
                return
            }
            let (range, offset, scope, history, sessions) = (range, periodOffset, sources.scope, model.library.history, model.library.sessions)
            let includesStations = scope.includesStations
            let next = await OffMainActor.run {
                StatsCalculator.summary(
                    range: range,
                    history: history.scoped(to: scope),
                    sessions: includesStations ? sessions : [],
                    periodOffset: offset,
                    topLimit: 8
                )
            }
            guard !Task.isCancelled else { return }
            SummaryMemory.remember(next, for: key)
            // Only new plays animate: a revision from a lookup or a sync changes nothing to watch.
            let isLive = computed?.range == range && summary?.periodOffset == next.periodOffset
                && computed?.captureCount != next.captureCount
            LiveUpdate.apply(isLive: isLive, reduceMotion: reduceMotion) {
                computed = next
            }
        }
        .onChange(of: range) { periodOffset = 0 }
        .environment(\.statsPaging, paging)
    }

    /// Steps through periods from the one on show. A step waits for the summary it asked for,
    /// so a quick double tap can't run past the first period.
    private var paging: StatsPeriodPaging? {
        guard let summary, summary.range != .allTime else { return nil }
        let isSettled = summary.periodOffset == periodOffset
        let offset = $periodOffset
        return StatsPeriodPaging(
            canGoBack: isSettled && summary.hasEarlierPeriod,
            canGoForward: isSettled && !summary.isCurrentPeriod
        ) { step in
            offset.wrappedValue = min(0, offset.wrappedValue + step)
        }
    }
}

private struct Dashboard: View {
    let summary: StatsSummary
    /// For the section titles, which select the matching Top Charts row rather than pushing a second
    /// copy of the list and leaving the sidebar on Summary.
    @Environment(AppModel.self) private var model
    @Environment(\.openPlayRoute) private var openPlayRoute
    /// Three albums to a row, or four once the card is wide enough to keep them a good size.
    @State private var albumColumns = SummaryMemory.albumColumns
    /// The dashboard's width, which picks each row's arrangement. Measured rather than left to
    /// `ViewThatFits`, which would build and measure both arrangements on every layout. Kept as
    /// the arrangements alone, so a resize redraws the dashboard only when one changes.
    @State private var room = SummaryMemory.room

    nonisolated struct Room: Equatable {
        let isWide: Bool
        let pairsCharts: Bool
        let pairsRhythm: Bool

        init(width: CGFloat) {
            isWide = width >= 836
            pairsCharts = width >= 856
            pairsRhythm = width >= 760
        }
    }

    var body: some View {
        // Lazy, so a click on Summary builds only the sections in the window, not every chart
        // down to Records.
        LazyVStack(alignment: .leading, spacing: Design.Card.sectionSpacing) {
            if room.isWide {
                HStack(alignment: .top, spacing: Design.Card.spacing) {
                    // The tiles set the row's height at their natural size and the chart
                    // takes up the rest, so neither side has empty space at the bottom.
                    ListeningCard(summary: summary, chartHeight: 150, fillsHeight: true)
                        .frame(minWidth: 560)
                    GlanceGrid(summary: summary, columns: 1)
                        .frame(width: 260)
                }
                .fixedSize(horizontal: false, vertical: true)
            } else {
                VStack(spacing: Design.Card.spacing) {
                    ListeningCard(summary: summary, chartHeight: 190)
                    GlanceGrid(summary: summary, columns: 4)
                }
            }

            highlights

            if !summary.topArtists.isEmpty {
                SummarySection("Top Artists", action: { model.sidebarSelection = SidebarItem(chart: .artists) }) {
                    TopArtistsShelf(artists: summary.topArtists, artworkSize: 110)
                }
                .id("artists")
            }

            if room.pairsCharts {
                HStack(alignment: .top, spacing: Design.Card.spacing) {
                    songs.frame(minWidth: 420)
                    albums.frame(minWidth: 420)
                }
            } else {
                VStack(alignment: .leading, spacing: Design.Card.sectionSpacing) {
                    songs
                    albums
                }
            }

            NewFavoritesSection(summary: summary, limit: 6)
            GenresSection(summary: summary)

            SummarySection("Listening Rhythm") {
                VStack(spacing: Design.Card.spacing) {
                    let rhythm = room.pairsRhythm
                        ? AnyLayout(HStackLayout(alignment: .top, spacing: Design.Card.spacing))
                        : AnyLayout(VStackLayout(spacing: Design.Card.spacing))
                    rhythm {
                        Card { ListeningClockCard(hourly: summary.hourly) }
                            .frame(width: room.pairsRhythm ? 320 : nil)
                        weekCard
                    }
                    RhythmDetailsRow(summary: summary)
                }
            }
            .id("rhythm")

            TrendsSection(summary: summary)
            VarietySection(summary: summary)
            RecordsSection(summary: summary, columns: 3)

            Text("Listening time is estimated from when each song started.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
        }
        .onGeometryChange(for: Room.self) { Room(width: $0.size.width) } action: {
            room = $0
            SummaryMemory.room = $0
        }
    }

    /// Up to six highlights, in two panels side by side once there are four or more.
    @ViewBuilder
    private var highlights: some View {
        let shown = Array(summary.summaryHighlights.prefix(6))
        if !shown.isEmpty {
            let hasMore = summary.insights.count > shown.count
            SummarySection("Highlights", action: hasMore ? { openPlayRoute(.stats(.highlights(summary.range, periodOffset: summary.periodOffset))) } : nil) {
                if shown.count >= 4 {
                    let half = (shown.count + 1) / 2
                    CardPair {
                        HighlightsCard(insights: Array(shown.prefix(half)))
                        HighlightsCard(insights: Array(shown.dropFirst(half)))
                    }
                } else {
                    HighlightsCard(insights: shown)
                }
            }
            .id("highlights")
        }
    }

    private var songs: some View {
        SummarySection("Top Songs", action: { model.sidebarSelection = SidebarItem(chart: .songs) }) {
            // Eight beside four albums across, so the two cards end together.
            TopSongsCard(songs: summary.topSongs, limit: albumColumns == 4 ? 8 : 6)
        }
    }

    private var albums: some View {
        SummarySection("Top Albums", action: { model.sidebarSelection = SidebarItem(chart: .albums) }) {
            Card {
                // Two whole rows, three across or four in a wide window, never a row with gaps.
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14, alignment: .top), count: albumColumns), spacing: 14) {
                    ForEach(summary.topAlbums.prefix(albumColumns * 2)) { album in
                        NavigationLink(value: Route.album(album.id)) {
                            VStack(alignment: .leading, spacing: 5) {
                                ArtworkView(url: album.artworkURL, seed: album.title, size: nil)
                                    .aspectRatio(1, contentMode: .fit)
                                Text(album.title)
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(1)
                                Text(album.artistName)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .onGeometryChange(for: Int.self) { $0.size.width >= 600 ? 4 : 3 } action: {
                    albumColumns = $0
                    SummaryMemory.albumColumns = $0
                }
            }
        }
    }

    private var weekCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                CardLabel(title: "Your Week", systemImage: "calendar")
                if summary.heatMapIsInformative {
                    // Fills the card, which is as tall as the Listening Clock beside it.
                    HeatMapChart(cells: summary.heatMap, fillsHeight: true)
                } else {
                    // Fills the card, which is as tall as the Listening Clock beside it.
                    WeekdayChart(weekdays: summary.weekdays, height: nil)
                }
                if let cell = summary.busiestCell {
                    Text("Busiest around \(Format.hour(cell.hour)) on \(Calendar.current.weekdaySymbols[cell.weekday - 1])s.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                DayTypeFootnotes(summary: summary, keysWeekdayChart: !summary.heatMapIsInformative)
            }
        }
    }
}

/// The top of the dashboard as plain cards, in the same arrangement, while the summary is
/// worked out.
private struct DashboardSkeleton: View {
    @State private var isWide = true

    var body: some View {
        Group {
            if isWide {
                HStack(alignment: .top, spacing: Design.Card.spacing) {
                    CardBackground()
                        .frame(minWidth: 560)
                        .frame(height: 332)
                    CardBackground()
                        .frame(width: 260, height: 332)
                }
            } else {
                VStack(spacing: Design.Card.spacing) {
                    CardBackground().frame(height: 330)
                    CardBackground().frame(height: 110)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .onGeometryChange(for: Bool.self) { $0.size.width >= 836 } action: { isWide = $0 }
        .accessibilityHidden(true)
    }
}

/// What Summary last showed, kept while the app runs so going back to it is instant. A page
/// of the window's, so one is enough.
private enum SummaryMemory {
    struct Key: Equatable {
        let range: StatsRange
        let periodOffset: Int
        let scope: SourceScope
        let revision: Int
    }

    private static var last: (key: Key, summary: StatsSummary)?
    /// The dashboard's arrangement for the window's last width, so it isn't laid out for a
    /// guess first and then again.
    static var room = Dashboard.Room(width: 1000)
    static var albumColumns = 3

    /// The last summary, if it's of the same period. One a play or two behind still shows,
    /// until the new one is worked out.
    static func summary(for key: Key) -> StatsSummary? {
        guard let last, last.key.range == key.range, last.key.periodOffset == key.periodOffset,
              last.key.scope == key.scope
        else { return nil }
        return last.summary
    }

    static func isCurrent(_ key: Key) -> Bool {
        last?.key == key
    }

    static func remember(_ summary: StatsSummary, for key: Key) {
        last = (key, summary)
    }
}
