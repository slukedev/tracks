import SwiftUI
import SwiftData
import TracksCore
import TracksMusic

/// The window that drops down from the menu bar: what's playing, today at a glance, and the
/// last few songs.
///
/// The width is fixed and the scrolling part has a definite height. A `ScrollView` has no
/// intrinsic height and a `.window` MenuBarExtra sizes itself to its content, so without one
/// the list collapsed to nothing (see docs/PlatformNotes.md).
struct MenuBarContent: View {
    let model: AppModel
    @AppStorage(PlayPreferences.songDestinationKey) private var songDestination = SongDestination.tracks

    static let width: CGFloat = 340

    var body: some View {
        if let store = model.store, let capture = model.capture, let playback = model.playback {
            let player = model.player
            MenuBarBody()
                .frame(width: Self.width)
                .modelContainer(store.container)
                .environment(model)
                .environment(capture)
                .environment(playback)
                .environment(player)
                .environment(model.playFeed)
                // Where the main window plays songs, so a click here plays them the same way.
                .environment(\.playSongs, songDestination == .tracks ? PlaySongsAction { items, title in
                    player.play(.history(items.map(HistorySong.init)), from: .songs(title))
                } : nil)
        } else {
            ContentUnavailableView {
                Label("Tracks Can't Open Your History", systemImage: "exclamationmark.triangle")
            } description: {
                Text("Quit Tracks and open it again. If that doesn’t help, restart your Mac.")
            }
            .frame(width: Self.width)
            .padding(.vertical)
        }
    }
}

/// The window's one leading edge, and the gap between its parts: spacing rather than rules,
/// since the window's glass already frames it.
private enum MenuBarMetrics {
    static let margin: CGFloat = 14
}

private struct MenuBarBody: View {
    @Environment(PlayerModel.self) private var player
    @Environment(CaptureService.self) private var capture
    /// Only what the list shows. Today's figures have their own query in ``TodaySummary``,
    /// so opening the menu never loads the whole history.
    @Query private var recent: [Capture]
    /// The day ``TodaySummary`` counts. Moved on at midnight and whenever the window opens,
    /// since the window's views can outlive the day they were made on.
    @State private var day = Calendar.current.startOfDay(for: .now)
    @Environment(\.isOnScreen) private var isOnScreen

    static let recentLimit = 12

    init() {
        var descriptor = FetchDescriptor<Capture>(sortBy: [SortDescriptor(\.capturedAt, order: .reverse)])
        descriptor.fetchLimit = Self.recentLimit
        _recent = Query(descriptor)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Tracks’ player, while it has something on; the last song played otherwise.
            Group {
                if player.hasQueue {
                    TracksPlayerHeader()
                } else {
                    LastPlayedHeader(latest: recent.first)
                }
            }
            .padding(MenuBarMetrics.margin)

            NearbyMenuSection()

            if !capture.isRunning {
                HistoryPausedNote()
            }

            PlaybackErrorNote()

            // A new identity each day, so the query's cutoff moves with it.
            TodaySummary(day: day)
                .id(day)
                .padding(.horizontal, MenuBarMetrics.margin)
                .padding(.bottom, Design.Spacing.small)

            RecentList(captures: recent, day: day)

            MenuBarFooter()
        }
        // The panel's views outlive its closing, so opening it is the moment to look again.
        .task(id: isOnScreen) {
            guard isOnScreen else { return }
            moveToToday()
        }
        // Posted on whichever thread noticed the change, so read on the main actor.
        .task {
            for await _ in NotificationCenter.default.notifications(named: .NSCalendarDayChanged) {
                moveToToday()
            }
        }
    }

    private func moveToToday() {
        let today = Calendar.current.startOfDay(for: .now)
        if today != day { day = today }
    }
}

/// Says capture is paused, where it can't be missed, with the way back.
private struct HistoryPausedNote: View {
    @Environment(CaptureService.self) private var capture

    var body: some View {
        HStack(spacing: 8) {
            Label("History Paused", systemImage: "record.circle")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.orange)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Resume") { Task { await capture.resume() } }
                .controlSize(.small)
                .accessibilityLabel("Resume History")
        }
        .padding(.horizontal, MenuBarMetrics.margin)
        .padding(.bottom, Design.Spacing.medium)
    }
}

// MARK: - Now playing

/// What Tracks is playing, with its controls.
private struct TracksPlayerHeader: View {
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isOnScreen) private var isOnScreen
    @AppStorage(CaptureSettings.showsMenuBarProgressKey, store: CaptureSettings.sharedDefaults)
    private var showsProgress = true
    @State private var previousPresses = 0
    @State private var nextPresses = 0

    var body: some View {
        if let track = player.current {
            VStack(spacing: Design.Spacing.small) {
                MenuBarSongButton(songIdentity: track.songIdentity) {
                    CoverImage(cover: track.cover, size: 58)
                    VStack(alignment: .leading, spacing: 2) {
                        Label {
                            Text(player.context.map { "\($0.title)" } ?? String(localized: "Playing in Tracks"))
                        } icon: {
                            // The window's views stay alive while it's closed: the bars rest then.
                            PlayingWaveform(isActive: player.isPlaying && isOnScreen)
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tint)
                        .lineLimit(1)
                        Text(track.title)
                            .font(.headline)
                            .lineLimit(1)
                        HStack(spacing: 6) {
                            Text(track.artistName)
                                .lineLimit(1)
                            PlayCountLine(track: track, showsSince: false)
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    }
                }
                .animation(LiveUpdate.animation(reduceMotion: reduceMotion), value: track.id)

                if showsProgress, let duration = track.duration, duration > 0 {
                    MenuBarProgress(
                        duration: duration,
                        isPlaying: player.isPlaying,
                        time: { player.playbackTime },
                        onSeek: { player.seek(to: $0) }
                    )
                    .id(track.id)
                }

                // Plain glyphs: the window is the glass, and a control inside glass isn't glass
                // again. Each answers the pointer and a press as the playback bar's do.
                HStack(spacing: Design.Spacing.xxLarge) {
                    Button {
                        previousPresses += 1
                        player.skipToPrevious()
                    } label: {
                        SkipArrows(direction: .backward, height: 12, trigger: previousPresses)
                    }
                    .buttonStyle(BarIconButtonStyle(diameter: 36))
                    .keyboardShortcut(.leftArrow, modifiers: .command)
                    .disabled(player.context?.isStation == true)
                    .help("Previous (⌘←)")
                    .accessibilityLabel("Previous")

                    Button {
                        player.togglePlayPause()
                    } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 24))
                            .contentTransition(reduceMotion ? .opacity : .symbolEffect(.replace))
                    }
                    .buttonStyle(BarIconButtonStyle(diameter: 44))
                    .keyboardShortcut(.space, modifiers: [])
                    .help(player.isPlaying ? "Pause (Space)" : "Play (Space)")
                    .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                    Button {
                        nextPresses += 1
                        player.skipToNext()
                    } label: {
                        SkipArrows(direction: .forward, height: 12, trigger: nextPresses)
                    }
                    .buttonStyle(BarIconButtonStyle(diameter: 36))
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                    .help("Next (⌘→)")
                    .accessibilityLabel("Next")
                }
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// The last song played, while Tracks’ player has nothing on.
private struct LastPlayedHeader: View {
    let latest: Capture?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        MenuBarSongButton(songIdentity: latest.map { SongIdentity.key(title: $0.title, artistName: $0.artistName) }) {
            artwork
            VStack(alignment: .leading, spacing: 2) {
                if latest != nil {
                    sourceLine
                }
                Text(latest?.title ?? String(localized: "Nothing Played Yet"))
                    .font(.headline)
                    .lineLimit(1)
                if let latest {
                    Text([latest.artistName, latest.albumTitle].compactMap(\.self).joined(separator: " · "))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        // The next song crossfades in rather than snapping.
        .animation(LiveUpdate.animation(reduceMotion: reduceMotion), value: latest?.title)
    }

    @ViewBuilder
    private var artwork: some View {
        if let latest {
            ArtworkView(url: latest.artworkURL, seed: latest.albumTitle ?? latest.title, size: 58)
        } else {
            RoundedRectangle(cornerRadius: CoverImage.radius(for: 58), style: .continuous)
                .fill(Color.placeholderFill)
                .frame(width: 58, height: 58)
                .overlay { Image(systemName: "music.note").foregroundStyle(.secondary) }
                .accessibilityHidden(true)
        }
    }

    /// History stopped says so once, in the note under the song with its Resume.
    private var sourceLine: some View {
        Label("Last Played", systemImage: "clock")
            .foregroundStyle(.secondary)
            .font(.caption.weight(.semibold))
            .labelStyle(.titleAndIcon)
            .lineLimit(1)
    }
}

/// The song at the top of the menu: a click opens its page in the main window and closes the
/// menu, as clicking the song in Music's mini player shows it. It highlights under the pointer
/// as the rows below do.
private struct MenuBarSongButton<Content: View>: View {
    /// Nil before anything has played, when there's no page to open.
    let songIdentity: String?
    @ViewBuilder var content: Content
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismiss) private var dismiss
    @State private var isHovered = false

    var body: some View {
        Button {
            guard let songIdentity else { return }
            model.pendingRoute = .song(songIdentity)
            MainWindow.bringForward(openWindow: openWindow)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                content
                Spacer(minLength: 0)
            }
            .padding(6)
            .contentShape(.rect)
            .background(.quaternary.opacity(isHovered && songIdentity != nil ? 0.6 : 0), in: .rect(cornerRadius: 10))
            .padding(-6)
        }
        .buttonStyle(.plain)
        // Not disabled: that would grey out "Nothing Played Yet" too.
        .allowsHitTesting(songIdentity != nil)
        .onHover { isHovered = $0 }
        .help("Show in Tracks")
        .accessibilityHint("Opens the song in Tracks")
    }
}

/// Why the song someone just clicked didn't start. Shown for a few seconds, and only for a
/// failure that happens while the window is open, not a stale one from earlier.
private struct PlaybackErrorNote: View {
    @Environment(PlaybackController.self) private var playback
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var message: String?
    @State private var isArmed = false

    var body: some View {
        Group {
            if let message {
                Label(message, systemImage: "exclamationmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, MenuBarMetrics.margin)
                    .padding(.bottom, 12)
                    .transition(.opacity)
            }
        }
        .task(id: playback.lastError) {
            defer { isArmed = true }
            guard isArmed, let error = playback.lastError else { return }
            withAnimation(LiveUpdate.animation(reduceMotion: reduceMotion)) { message = error }
            try? await Task.sleep(for: .seconds(6))
            withAnimation(LiveUpdate.animation(reduceMotion: reduceMotion)) { message = nil }
        }
    }
}

// MARK: - Today

/// Today's songs, listening time and streak, from today's rows only.
private struct TodaySummary: View {
    let day: Date
    @Query private var todays: [Capture]
    @Environment(\.modelContext) private var context
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var stats: TodayStats?
    /// Days in a row before today. Fixed for the day (this view's identity is the day), so
    /// it's counted once, off the main actor, and not again for every song: with years of
    /// history, counting back on each song read thousands of rows on the main thread.
    @State private var daysBefore: Int?

    init(day: Date) {
        self.day = day
        _todays = Query(filter: #Predicate<Capture> { $0.capturedAt >= day }, sort: \Capture.capturedAt)
    }

    var body: some View {
        TodayStrip(stats: stats ?? TodayStats())
            .task(id: "\(todays.count)|\(todays.last?.capturedAt.timeIntervalSinceReferenceDate ?? 0)") {
                if daysBefore == nil {
                    daysBefore = await TodayStats.daysInARow(before: day, container: context.container)
                }
                guard !Task.isCancelled else { return }
                let next = TodayStats(
                    todays: todays,
                    streak: (todays.isEmpty ? 0 : 1) + (daysBefore ?? 0)
                )
                LiveUpdate.apply(isLive: stats != nil, reduceMotion: reduceMotion) { stats = next }
            }
    }
}

private struct TodayStats: Equatable {
    var songs = 0
    var seconds: TimeInterval = 0
    var streak = 0

    init() {}

    /// - Parameter todays: today's rows, oldest first.
    init(todays: [Capture], streak: Int) {
        let stats = todays.map(\.stat)
        songs = stats.count
        seconds = ListeningEstimate.seconds(for: stats).reduce(0, +)
        self.streak = streak
    }

    /// Days in a row with a play just before `day`, which with today's play (if any) makes
    /// the current streak as ``StatsCalculator/streak(in:calendar:now:)`` counts it.
    ///
    /// Reads only play dates, a few weeks at a time and further back only while the streak
    /// continues, instead of loading the whole history.
    @concurrent
    nonisolated static func daysInARow(
        before day: Date,
        container: ModelContainer,
        calendar: Calendar = .current
    ) async -> Int {
        let context = ModelContext(container)
        var run = 0
        var days = Set<Date>()
        var fetchedFrom = day
        var span = 32
        guard var cursor = calendar.date(byAdding: .day, value: -1, to: day) else { return run }

        while true {
            if cursor < fetchedFrom {
                guard let from = calendar.date(byAdding: .day, value: -span, to: fetchedFrom) else { break }
                let upTo = fetchedFrom
                var descriptor = FetchDescriptor<Capture>(
                    predicate: #Predicate { $0.capturedAt >= from && $0.capturedAt < upTo }
                )
                descriptor.propertiesToFetch = [\.capturedAt]
                let rows = (try? context.fetch(descriptor)) ?? []
                days.formUnion(rows.map { calendar.startOfDay(for: $0.capturedAt) })
                fetchedFrom = from
                span *= 2
            }
            guard days.contains(cursor),
                  let before = calendar.date(byAdding: .day, value: -1, to: cursor)
            else { break }
            run += 1
            cursor = before
        }
        return run
    }
}

private struct TodayStrip: View {
    let stats: TodayStats

    var body: some View {
        HStack(spacing: 0) {
            stat(value: stats.songs.formatted(), label: "Songs Today", symbol: "music.note", tint: .accentColor)
            stat(value: Format.listening(stats.seconds), label: "Listening", symbol: "headphones", tint: .blue)
            stat(
                value: String(AttributedString(localized: "^[\(stats.streak) day](inflect: true)").characters),
                label: "Streak",
                symbol: "flame.fill",
                tint: .orange
            )
        }
    }

    private func stat(value: String, label: LocalizedStringKey, symbol: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(label, systemImage: symbol)
                .labelStyle(.titleAndIcon)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(tint)
                .lineLimit(1)
            Text(value)
                .font(.system(.title3, design: .rounded, weight: .semibold))
                .monospacedDigit()
                .contentTransition(.numericText())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Recent

private struct RecentList: View {
    let captures: [Capture]
    /// Today, moved at midnight and when the window opens, so a time from today turns into
    /// "Yesterday" without waiting for the next song.
    let day: Date
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss
    private var playback = CapturePlayback()

    init(captures: [Capture], day: Date) {
        self.captures = captures
        self.day = day
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Recently Played")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button("Show All") {
                    model.sidebarSelection = .history
                    MainWindow.bringForward(openWindow: openWindow)
                    dismiss()
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
                .font(.subheadline)
            }
            .padding(.horizontal, MenuBarMetrics.margin)
            .padding(.top, Design.Spacing.medium)

            if captures.isEmpty {
                Text("Songs you play in Tracks show up here.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(captures) { item in
                            row(item)
                                .transition(.push(from: .top))
                                .contextMenu { menu(for: item) }
                        }
                    }
                    .padding(.horizontal, 6)
                    .padding(.bottom, 6)
                    // A new song pushes in at the top and the rest slide down to make room.
                    .animation(LiveUpdate.animation(reduceMotion: reduceMotion), value: captures.map(\.persistentModelID))
                }
            }
        }
        .frame(height: 300)
    }

    /// Clicking plays the song. One the catalog never identified can't be played, so it
    /// isn't a button; its menu still leads to the stats.
    @ViewBuilder
    private func row(_ item: Capture) -> some View {
        if !playback.canPlay([item]) {
            RecentRow(capture: item, day: day)
        } else {
            Button {
                playback.play([item])
            } label: {
                RecentRow(capture: item, day: day)
            }
            // The system's own press feedback. Rows are content, so no glass: that's for
            // the controls floating above it.
            .buttonStyle(.plain)
            .help("Play")
            .accessibilityHint("Plays the song")
        }
    }

    /// The song's menu, as everywhere else, with its pages opening in the main window.
    private func menu(for item: Capture) -> some View {
        let song = MixSong(item)
        let credits = ArtistCredits.song(artist: item.artistName, title: item.title)
        let artist = credits.first?.identity ?? StatsCalculator.folded(item.artistName)
        var actions = SongActions(
            stats: { show(.song(song.songIdentity)) },
            info: SongInfoSubject(song),
            share: .song(id: item.songID, title: item.title, artist: item.artistName),
            suggestLess: song.songIdentity,
            block: item.artistName
        )
        if playback.canPlay([item]) {
            actions.play = { playback.play([item]) }
        }
        if !item.songID.isEmpty {
            // No API can take a song back out of a playlist, so point at the app that can.
            actions.reveal.append(SongMenuButton(title: "Show in Music", systemImage: "arrow.up.forward.app") {
                if let url = URL(string: "https://music.apple.com/song/\(item.songID)") { openURL(url) }
            })
        }
        if let album = item.albumTitle.map(StatsCalculator.folded), !album.isEmpty {
            actions.goToAlbum = { show(.album("\(album)\u{1F}\(artist)")) }
        }
        actions.goToArtist = { show(.artist(artist)) }
        actions.linkArtists(credits) { show(.artist($0.identity)) }
        return SongMenuItems(actions)
            .environment(\.showSongInfo, ShowSongInfoAction { subject in
                SongInfoWindow.show(subject, model: model, openWindow: openWindow)
                dismiss()
            })
    }

    /// Opens a page in the main window and closes this one.
    private func show(_ route: Route) {
        model.pendingRoute = route
        MainWindow.bringForward(openWindow: openWindow)
        dismiss()
    }
}

/// A compact history row that highlights on hover, like a menu item.
private struct RecentRow: View {
    let capture: Capture
    /// Only changes at midnight; the time it shows changes no more often than that.
    let day: Date
    @State private var isHovering = false

    var body: some View {
        HStack(spacing: 10) {
            ArtworkView(url: capture.artworkURL, seed: capture.albumTitle ?? capture.title, size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(capture.title)
                    .font(.callout)
                    .lineLimit(1)
                HStack(spacing: 3) {
                    if capture.kind == .radio {
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .foregroundStyle(.pink)
                            .accessibilityLabel("Radio")
                    }
                    Text(capture.artistName)
                        .foregroundStyle(.secondary)
                }
                .font(.caption)
                .lineLimit(1)
            }
            Spacer(minLength: 6)
            Text(Format.relativeTime(capture.capturedAt, now: day))
                .font(.caption)
                .foregroundStyle(.tertiary)
                .monospacedDigit()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        // The whole row is the click target, not just its text.
        .contentShape(.rect)
        .background(isHovering ? AnyShapeStyle(.quaternary.opacity(0.6)) : AnyShapeStyle(.clear), in: .rect(cornerRadius: 7))
        .onHover { isHovering = $0 }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Footer

struct MenuBarFooter: View {
    @Environment(CaptureService.self) private var capture
    @Environment(\.openSettings) private var openSettings
    @Environment(\.openWindow) private var openWindow
    /// Closes the menu bar window. Anything that brings up another window calls it, so the
    /// menu doesn't stay open over what it just opened.
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        HStack(spacing: 6) {
            Button("Open Tracks") {
                MainWindow.bringForward(openWindow: openWindow)
                dismiss()
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)

            Spacer()

            // Plain glyphs that answer the pointer and a press, as the transport above does:
            // the window is the glass. A record glyph for history, not pause and play, which
            // would read as pausing the music; red while it's kept, like a recording light.
            HStack(spacing: Design.Spacing.xSmall) {
                Button {
                    if capture.isPaused { Task { await capture.resume() } } else { capture.pause() }
                } label: {
                    Image(systemName: capture.isRunning ? "record.circle.fill" : "record.circle")
                        .foregroundStyle(capture.isRunning ? AnyShapeStyle(.red) : AnyShapeStyle(.orange))
                        .contentTransition(.symbolEffect(.replace))
                }
                .accessibilityLabel(capture.isRunning ? "Pause History" : "Resume History")
                .accessibilityValue(capture.isRunning ? "Keeping what you play" : "Paused")
                .help(capture.isRunning ? "Pause History: stop keeping what you play for now" : "Resume History: keep what you play again")
                Button("Settings", systemImage: "gearshape") {
                    Self.bringSettingsForward(openSettings: openSettings)
                    dismiss()
                }
                .keyboardShortcut(",", modifiers: .command)
                .help("Settings (⌘,)")
                Button("Quit Tracks", systemImage: "power") { NSApp.terminate(nil) }
                    .keyboardShortcut("q", modifiers: .command)
                    .help("Quit Tracks (⌘Q)")
            }
            .labelStyle(.iconOnly)
            .buttonStyle(BarIconButtonStyle(diameter: 32))
        }
        .padding(.horizontal, Design.Spacing.medium)
        .padding(.vertical, Design.Spacing.medium)
    }

    /// Opens Settings and puts it in front.
    ///
    /// `openSettings()` on its own makes the window but leaves Tracks in the background, which
    /// from the menu bar it almost always is. The window then sat behind whatever the user
    /// was in and only appeared when they pressed "Open Tracks", which activates Tracks as a
    /// side effect. Activating first means SwiftUI opens the window into an app that is
    /// already frontmost; the second pass covers a window that was made before the activation
    /// landed, and raises one left over from a previous visit.
    static func bringSettingsForward(openSettings: OpenSettingsAction) {
        NSApp.activate(ignoringOtherApps: true)
        openSettings()
        Task { @MainActor in
            NSApp.activate(ignoringOtherApps: true)
            settingsWindow?.makeKeyAndOrderFront(nil)
        }
    }

    /// The window the `Settings` scene puts up.
    ///
    /// SwiftUI gives it no public identity; AppKit calls it `com_apple_SwiftUI_Settings_window`.
    /// Matched loosely so a rename between releases doesn't quietly stop this working. No
    /// other Tracks window has "Settings" in its identifier. A miss is harmless: the window
    /// may not exist yet, and activation alone usually brings it up.
    private static var settingsWindow: NSWindow? {
        NSApp.windows.first { $0.identifier?.rawValue.contains("Settings") == true }
    }
}

#if DEBUG
@MainActor
private let previewModel: AppModel = {
    UserDefaults.standard.setVolatileDomain(["TracksDemoData": true], forName: UserDefaults.argumentDomain)
    return AppModel()
}()

private struct MenuBarPreview: View {
    var isPlaying = false

    var body: some View {
        let model = previewModel
        MenuBarContent(model: model)
            .environment(MacAppBehaviour())
            .task {
                guard isPlaying else { return }
                // The demo feed builds its mixes a moment after the model starts.
                for _ in 0..<20 where !model.player.hasQueue {
                    if let mix = model.playFeed.mixes.mixes.first {
                        model.player.play(.history(model.player.songs(in: mix)), from: PlayContext(kind: .mix, title: mix.kind.title))
                    } else {
                        try? await Task.sleep(for: .milliseconds(250))
                    }
                }
            }
    }
}

#Preview("Last played, light") { MenuBarPreview() }
#Preview("Last played, dark") { MenuBarPreview().preferredColorScheme(.dark) }
#Preview("Playing, light") { MenuBarPreview(isPlaying: true) }
#Preview("Playing, dark") { MenuBarPreview(isPlaying: true).preferredColorScheme(.dark) }
#endif
