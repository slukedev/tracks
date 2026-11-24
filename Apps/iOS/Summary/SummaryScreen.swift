import SwiftUI
import TracksCore

struct SummaryScreen: View {
    @Environment(AppModel.self) private var model
    @SeenRevision private var revision
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("statsRange") private var range: StatsRange = .month
    @State private var summary: StatsSummary?
    /// 0 for this week, month or year; -1 for the one before. Back to 0 when the range changes.
    @State private var periodOffset = LaunchScene.periodOffset
    /// The paging last handed down, kept while what it allows is the same: it can't be
    /// compared, so a new one each redraw (a song starting, say) redrew every card reading it.
    @State private var pagingMemo = Memo<PagingKey?, StatsPeriodPaging?>()
    private var sources = SourceScopeSetting()

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // Lazy, with each of the summary's sections a row of its own (see
                // `SummaryContent`): built eagerly, a long history's charts and shelves were all
                // laid out before the tab could show.
                LazyVStack(alignment: .leading, spacing: Design.Margin.section) {
                    if showsWelcome {
                        WelcomeView()
                            .padding(.top, Design.Spacing.xxxLarge)
                    } else {
                        VStack(alignment: .leading, spacing: Design.Spacing.xxLarge) {
                            RangePicker(range: $range)
                            StatusPanels()
                            if let summary {
                                if summary.captureCount == 0 {
                                    NothingPlayedView(summary: summary)
                                } else {
                                    ListeningHero(summary: summary)
                                }
                            } else {
                                ListeningHeroPlaceholder()
                            }
                        }
                        if let summary, summary.captureCount > 0 {
                            SummaryContent(summary: summary)
                        }
                    }
                }
                .padding(.horizontal, Design.Margin.page)
                .padding(.top, Design.Spacing.small)
                .padding(.bottom, Design.Margin.section)
                .frame(maxWidth: .infinity)
            }
            .scrollEdgeEffectStyle(.soft, for: .top)
            // Only Now Playing arriving or leaving moves the page, never the song inside it
            // changing, so a new play doesn't set the whole page moving.
            .animation(LiveUpdate.animation(reduceMotion: reduceMotion), value: model.capture?.nowPlaying != nil)
            .onChange(of: summary != nil) {
                if let anchor = LaunchScene.scrollAnchor {
                    proxy.scrollTo(anchor, anchor: .top)
                }
            }
        }
        .groupedBackground()
        .sourceScopeSubtitle(sources.scope)
        .tabRoot("Summary", opensSettingsAtLaunch: LaunchScene.opensSettings) {
            if sources.isOffered {
                ToolbarItem(placement: .primaryAction) {
                    SourceScopeMenu(scope: sources.selection, offered: sources.offered)
                }
            }
        }
        // Holds the model only. Capturing the whole screen would keep each summary it was
        // drawn with alive through the environment, a summary for every song.
        .refreshable { [model] in
            if !model.isShowingSampleData { await model.capture?.catchUp() }
        }
        .task(id: "\(range.rawValue)|\(periodOffset)|\(sources.scope.rawValue)|\(revision)") {
            // Not from the empty history before it's read, which would say nothing was played
            // and then animate the real week in as if it had just been heard.
            guard model.library.isLoaded else { return }
            let (range, offset, scope, history, sessions) = (range, periodOffset, sources.scope, model.library.history, model.library.sessions)
            let includesStations = scope.includesStations
            let next = await OffMainActor.run {
                StatsCalculator.summary(
                    range: range,
                    history: history.scoped(to: scope),
                    sessions: includesStations ? sessions : [],
                    periodOffset: offset,
                    topLimit: 5
                )
            }
            guard !Task.isCancelled else { return }
            // Without a transaction: the hero rolls its figure when the period changes, and
            // nothing moves when a new play only adds to it.
            summary = next
        }
        .onChange(of: range) { periodOffset = 0 }
        .environment(\.statsPaging, paging)
    }

    /// First run: the library is read and there's nothing in it.
    private var showsWelcome: Bool {
        model.library.isLoaded && model.library.history.isEmpty
    }

    /// Steps through periods from the one on show. A step waits for the summary it asked for,
    /// so a quick double tap can't run past the first period.
    private var paging: StatsPeriodPaging? {
        let key: PagingKey? = summary.flatMap { summary in
            guard summary.range != .allTime else { return nil }
            let isSettled = summary.periodOffset == periodOffset
            return PagingKey(
                canGoBack: isSettled && summary.hasEarlierPeriod,
                canGoForward: isSettled && !summary.isCurrentPeriod
            )
        }
        let offset = $periodOffset
        return pagingMemo.value(for: key) {
            key.map { key in
                StatsPeriodPaging(canGoBack: key.canGoBack, canGoForward: key.canGoForward) { step in
                    offset.wrappedValue = min(0, offset.wrappedValue + step)
                }
            }
        }
    }

    private struct PagingKey: Equatable {
        let canGoBack: Bool
        let canGoForward: Bool
    }
}

/// Everything below the range picker. Its sections join the screen's lazy stack one by one,
/// with the same spacing and alignment a stack of their own had.
struct SummaryContent: View {
    let summary: StatsSummary
    @Environment(AppModel.self) private var model
    @Environment(\.openPlayRoute) private var openPlayRoute

    var body: some View {
        Group {
            // Hides itself once access is granted.
            MusicAccessCard()

            GlanceGrid(summary: summary, columns: 2)

            highlights

            if !summary.topArtists.isEmpty {
                SummarySection("Top Artists", action: { showChart(.artists) }) {
                    TopArtistsShelf(artists: summary.topArtists)
                }
                .id("artists")
            }

            if !summary.topSongs.isEmpty {
                SummarySection("Top Songs", action: { showChart(.songs) }) {
                    TopSongsCard(songs: summary.topSongs)
                }
                .id("songs")
            }

            if !summary.topAlbums.isEmpty {
                SummarySection("Top Albums", action: { showChart(.albums) }) {
                    TopAlbumsShelf(albums: summary.topAlbums)
                }
                .id("albums")
            }

            NewFavoritesSection(summary: summary, limit: 5)
            GenresSection(summary: summary)

            SummarySection("Listening Rhythm") {
                VStack(spacing: Design.Card.spacing) {
                    CardPair {
                        Card { ListeningClockCard(hourly: summary.hourly) }
                        Card { WeekdayCard(summary: summary) }
                    }
                    RhythmDetailsRow(summary: summary)
                }
            }
            .id("rhythm")

            TrendsSection(summary: summary)
            VarietySection(summary: summary)
            RecordsSection(summary: summary, columns: 2)

            Text("Listening time is estimated from when each song started.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private var highlights: some View {
        let highlights = Array(summary.summaryHighlights.prefix(3))
        if !highlights.isEmpty {
            let hasMore = summary.insights.count > highlights.count
            SummarySection("Highlights", action: hasMore ? { showHighlights() } : nil) {
                HighlightsCard(insights: highlights)
            }
            .id("highlights")
        }
    }

    private func showHighlights() {
        openPlayRoute(.stats(.highlights(summary.range, periodOffset: summary.periodOffset)))
    }

    /// Moves to History's charts, which hold the full lists, rather than pushing a second copy
    /// of one inside Summary and leaving the tab bar pointing at the wrong place.
    private func showChart(_ kind: ChartKind) {
        kind.select()
        model.historyMode = .charts
        model.selectedTab = .history
    }
}
