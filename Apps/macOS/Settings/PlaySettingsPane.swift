import SwiftUI
import AppKit
import TracksCore

/// The Mac's Play pane: where Listen Now's music comes from, Tracks Radio, where Tracks opens,
/// explicit songs, Listen Now's sections and the memory behind the mixes. Only what applies on
/// a Mac: no Shake to Play or Offline Mode, no cellular streaming, and no song transitions,
/// since neither MusicKit's player nor Your Music's crossfades on the Mac.
struct PlaySettingsPane: View {
    @AppStorage(OpeningTab.storageKey) private var openingTab: OpeningTab = .standard
    @AppStorage(PlayPreferences.allowsExplicitKey) private var allowsExplicit = true
    @AppStorage(PlayPreferences.tracksRadioKey) private var isRadioOn = true
    @AppStorage(PlayPreferences.radioTuningKey) private var storedTuning = ""
    @AppStorage(PlayPreferences.radioFollowsTimeKey) private var radioFollowsTime = true
    @AppStorage(PlayPreferences.radioDayKey) private var storedRadioDay = ""
    @AppStorage(PlayPreferences.layoutKey) private var storedLayout = ""
    @AppStorage(PlayPreferences.songDestinationKey) private var songDestination = SongDestination.tracks
    @AppStorage(AutomaticDownloads.storageKey) private var automaticDownloads = true
    /// The Mac's one streaming setting, for every network.
    @AppStorage(StreamQuality.wiFiKey) private var streamingQuality = StreamQuality.original
    @AppStorage(StreamQuality.downloadKey) private var downloadQuality = StreamQuality.original
    @AppStorage(PlayPreferences.radioDownloadsFirstKey) private var radioDownloadsFirst = true
    @AppStorage(PlayPreferences.radioDeletesAfterPlayingKey) private var radioDeletesAfterPlaying = false
    @AppStorage(QuickSwitch.storageKey) private var quickSwitch = false
    @AppStorage(SuggestionMode.storageKey) private var suggestionMode = SuggestionMode.everything
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openWindow) private var openWindow
    @State private var showsTuner = false
    @State private var showsRadioDay = ThroughTheDaySheet.opensAtLaunch
    @State private var editsLayout = false

    private var layout: PlayLayout { PlayLayout(stored: storedLayout) }

    #if DEBUG
    private static var debugAnchor: UnitPoint {
        switch UserDefaults.standard.string(forKey: "TracksSettingsAnchor") {
        case "center": .center
        case "bottom": .bottom
        default: .top
        }
    }
    #endif
    private var isYourMusic: Bool { model.musicSource == .yourMusic }

    var body: some View {
        let status = PlaySettingsWords.status(PlaySettingsWords.Standing(model: model, music: music))
        Form {
            SettingsHero("Play", subtitle: status.tone.apply(to: Text(status.line)), systemImage: "play.fill", tint: .indigo)

            sourceSection
            if isYourMusic {
                yourMusicSections
            }
            radioSection
            aroundSection
            ResumeSettingsSection()
            NowPlayingBackdropSection()
            contentSection
            BlockedArtistsSection()
            if !isYourMusic {
                // Your Music has its own Listen Now, without these sections.
                layoutSection
            }
            PlaySettingsMixes()
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: model.musicSource)
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: isRadioOn)
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: radioDownloadsFirst)
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: radioFollowsTime)
        #if DEBUG
        // -TracksSettingsAnchor center (or bottom) opens the pane scrolled there, for screenshots.
        .defaultScrollAnchor(Self.debugAnchor)
        #endif
        .settingsPane()
        #if DEBUG
        .onAppear(perform: PlaySettingsScreenshot.applyAppearance)
        #endif
        .sheet(isPresented: $showsTuner) { RadioTunerSheet() }
        .sheet(isPresented: $showsRadioDay) { ThroughTheDaySheet() }
        .sheet(isPresented: $editsLayout) { PlayLayoutEditor() }
    }

    // MARK: - Source

    private var sourceSection: some View {
        @Bindable var model = model
        return Section {
            Picker("Music Source", selection: $model.musicSource) {
                ForEach(MusicSource.allCases) { source in
                    Label(source.title, systemImage: source.symbol).tag(source)
                }
            }
            if QuickSwitch.isAvailable(music) {
                SettingsSwitch("Quick Switch", isOn: $quickSwitch)
            }
        } header: {
            Text("Source")
        } footer: {
            if let footer = PlaySettingsWords.sourceFooter(quickSwitch: .init(music: music, isOn: quickSwitch)) {
                Text(footer)
                    .contentTransition(.opacity)
            }
        }
    }

    // MARK: - Your Music

    @ViewBuilder
    private var yourMusicSections: some View {
        Section {
            LabeledContent("Songs on This Mac") {
                Text(music.fileTracks.count, format: .number)
                    .monospacedDigit()
                    .contentTransition(.numericText(value: Double(music.fileTracks.count)))
            }
            // A size of nothing reads as a fault, not a fact.
            if music.fileBytes > 0 {
                LabeledContent("Space Used", value: LocalFormat.bytes(music.fileBytes))
            }
            LabeledContent("Music Folder") {
                Button("Show in Finder", action: showMusicFolder)
                    .help(LibraryFolders.music.path(percentEncoded: false))
            }
            LabeledContent("Look for New Songs") {
                if music.isScanning {
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 80)
                } else {
                    Button("Look Now") { Task { await music.scan() } }
                }
            }
        } header: {
            Text("On This Mac")
        }

        ServersSection()

        Section {
            Picker("Suggest", selection: $suggestionMode) {
                ForEach(SuggestionMode.allCases) { Text($0.title).tag($0) }
            }
        } header: {
            Text("Suggestions")
        } footer: {
            if let footer = PlaySettingsWords.suggestionFooter(suggestionMode) {
                Text(footer)
                    .contentTransition(.opacity)
            }
        }

        PlaySettingsPlaylistMerge()

        PlaySettingsLidarrRow()

        Section {
            LabeledContent("Downloaded") {
                HStack(spacing: SettingsSpacing.row) {
                    // A size of nothing reads as a fault, not a fact.
                    if music.downloads.totalBytes > 0 {
                        Text(LocalFormat.bytes(music.downloads.totalBytes))
                            .foregroundStyle(.secondary)
                    }
                    Button("Show Downloads", action: showDownloads)
                }
            }
            SettingsSwitch("Automatic Downloads", isOn: $automaticDownloads)
            StreamQualityPicker(title: "Streaming", selection: $streamingQuality)
            StreamQualityPicker(title: "Download Quality", selection: $downloadQuality)
        } header: {
            Text("Downloads and Streaming")
        } footer: {
            if let footer = PlaySettingsWords.downloadQualityFooter(downloadQuality) {
                Text(footer)
                    .contentTransition(.opacity)
            }
        }
    }

    /// The Music folder in Finder, made first, so there's always somewhere to put songs.
    private func showMusicFolder() {
        let folder = LibraryFolders.music
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.activateFileViewerSelecting([folder])
    }

    /// Downloads live in the main window's sidebar, under Your Music.
    private func showDownloads() {
        model.sidebarSelection = .downloads
        MainWindow.bringForward(openWindow: openWindow)
    }

    // MARK: - Tracks Radio

    private var radioSection: some View {
        Section {
            SettingsSwitch("Show Tracks Radio", isOn: $isRadioOn)
            if isRadioOn {
                SettingsDetailRow(title: Text("Tuning"), detail: Text(RadioTuning(stored: storedTuning).summary)) {
                    Button("Tune…") { showsTuner = true }
                }
                SettingsSwitch(
                    "Follow the Time of Day",
                    detail: Text(RadioMomentWords.timeDetail(radioFollowsTime)),
                    isOn: $radioFollowsTime
                )
                .onChange(of: radioFollowsTime) {
                    Task { await model.player.retuneTracksRadio() }
                }
                if radioFollowsTime {
                    SettingsDetailRow(title: Text("Through the Day"), detail: Text(RadioDayWords.summary(RadioDay(stored: storedRadioDay)))) {
                        Button("Customize…") { showsRadioDay = true }
                    }
                }
                if isYourMusic {
                    SettingsSwitch("Downloaded Songs First", isOn: $radioDownloadsFirst)
                    if radioDownloadsFirst {
                        SettingsSwitch(
                            "Delete After Playing",
                            detail: PlaySettingsWords.deleteAfterPlayingFooter(radioDeletesAfterPlaying).map { Text($0) },
                            isOn: $radioDeletesAfterPlaying
                        )
                    }
                }
            }
        } header: {
            Text("Tracks Radio")
        }
    }

    // MARK: - Around Tracks

    private var aroundSection: some View {
        Section {
            Picker("Open To", selection: $openingTab) {
                ForEach(OpeningTab.allCases) { tab in
                    Text(PlaySettingsWords.openingName(tab)).tag(tab)
                }
            }
            Picker("Play Songs In", selection: $songDestination) {
                ForEach(SongDestination.allCases) { destination in
                    Text(PlaySettingsWords.destinationName(destination)).tag(destination)
                }
            }
        } header: {
            Text("Around Tracks")
        } footer: {
            if let footer = PlaySettingsWords.destinationFooter(songDestination) {
                Text(footer)
                    .contentTransition(.opacity)
            }
        }
    }

    // MARK: - Content

    private var contentSection: some View {
        Section {
            SettingsSwitch("Explicit Songs", isOn: $allowsExplicit)
        } header: {
            Text("Content")
        }
    }

    // MARK: - Layout

    private var layoutSection: some View {
        Section {
            if let detail = PlaySettingsWords.layoutDetail(layout) {
                SettingsDetailRow(title: Text("Sections"), detail: Text(detail)) {
                    Button("Edit Listen Now…") { editsLayout = true }
                }
            } else {
                LabeledContent("Sections") {
                    Button("Edit Listen Now…") { editsLayout = true }
                }
            }
        } header: {
            Text("Layout")
        }
    }
}

/// Lidarr in the Mac pane: where it stands, and a sheet to connect it or change how it files
/// what Tracks adds. A pane has nowhere to push its page.
private struct PlaySettingsLidarrRow: View {
    @Environment(Lidarr.self) private var lidarr
    /// `-TracksLidarrSheet YES` opens the sheet at once, for screenshots.
    @State private var showsLidarr = Self.opensSheet

    private static var opensSheet: Bool {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "TracksLidarrSheet")
        #else
        false
        #endif
    }

    var body: some View {
        Section {
            SettingsDetailRow(title: Text("Lidarr"), detail: status) {
                Button(isConnected ? "Settings…" : "Connect…") { showsLidarr = true }
            }
        } header: {
            Text("Requests")
        } footer: {
            Text("Connect Lidarr to add artists and ask for albums from anywhere in Tracks. What Lidarr files, your server plays, and Tracks can download.")
        }
        .sheet(isPresented: $showsLidarr) {
            if isConnected {
                PlaySettingsLidarrSheet()
            } else {
                LidarrConnectForm()
            }
        }
    }

    /// Set up, or answering: sample data's Lidarr answers without a key.
    private var isConnected: Bool {
        if case .connected = lidarr.status { return true }
        return lidarr.isSetUp
    }

    private var status: Text {
        switch lidarr.status {
        case .off: Text("Not connected")
        case .connecting: Text("Connecting…")
        case .connected(let version): Text("Connected · Lidarr \(version)")
        case .wrongKey: Text("The API key was refused").foregroundStyle(.ink(.orange))
        case .failed(let reason): Text(reason).foregroundStyle(.ink(.orange))
        }
    }
}

/// Lidarr's page, in a sheet with its title in the content and Done at its foot.
private struct PlaySettingsLidarrSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: SettingsSpacing.tight) {
                Text("Lidarr")
                    .font(.title2.bold())
                Text("How Lidarr files the artists and albums you add from Tracks.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding([.horizontal, .top], 20)

            NavigationStack {
                LidarrSettingsPage()
                    .formStyle(.grouped)
                    .toolbar(removing: .title)
            }
            .frame(width: SettingsPaneLayout.width, height: 440)

            HStack {
                Spacer()
                Button("Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(20)
        }
        .onExitCommand { dismiss() }
    }
}

#if DEBUG
/// `-TracksAppearance dark` draws the app dark whatever the system's set to, for screenshots
/// of the pane and the sheets it opens. Debug builds only.
enum PlaySettingsScreenshot {
    static func applyAppearance() {
        if UserDefaults.standard.string(forKey: "TracksAppearance") == "dark" {
            NSApp.appearance = NSAppearance(named: .darkAqua)
        }
    }
}

/// A model on sample data. The flag goes in a volatile domain, so it never reaches the real
/// app's defaults, and a preview never opens the real store.
@MainActor
private let previewModel: AppModel = {
    UserDefaults.standard.setVolatileDomain(["TracksDemoData": true], forName: UserDefaults.argumentDomain)
    return AppModel()
}()

#Preview("Play") {
    PlaySettingsPane()
        .environment(previewModel)
        .environment(previewModel.player)
        .environment(previewModel.yourMusic)
        .environment(previewModel.lidarr)
}
#endif
