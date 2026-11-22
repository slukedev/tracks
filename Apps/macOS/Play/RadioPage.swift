import SwiftUI
import TracksCore

/// Radio: your own station first, on a field of its colour that runs up under the toolbar and
/// the sidebar, then Apple Music's live stations, then the moods.
struct RadioPage: View {
    @Environment(PlayFeed.self) private var feed
    @Environment(AppModel.self) private var model
    @AppStorage(PlayPreferences.tracksRadioKey) private var isRadioOn = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Design.Margin.section) {
                if isRadioOn {
                    TracksRadioStage()
                } else {
                    VStack(alignment: .leading, spacing: Design.Spacing.large) {
                        title
                        radioOff
                    }
                    .padding(.top, Design.Spacing.xLarge)
                }
                if !feed.liveStations.isEmpty {
                    Shelf(title: String(localized: "Live Radio"), items: feed.liveStations) { item in
                        FeedTile(item: item)
                    }
                } else if feed.appleMusicState == .loading {
                    LoadingRows(count: 2)
                        .padding(.horizontal, Design.Margin.page)
                }
                MoodShelf()
            }
            .padding(.bottom, Design.Margin.section)
        }
        .heroTitle(String(localized: "Radio"))
        .task(id: model.musicAuthorization) { await feed.loadAppleMusic() }
    }

    private var title: some View {
        Text("Radio")
            .font(.pageTitle)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, Design.Margin.page)
    }

    /// Tracks Radio hidden in Settings: said so where it would be, with the way back.
    private var radioOff: some View {
        VStack(alignment: .leading, spacing: Design.Spacing.medium) {
            Text("Tracks Radio Is Off")
                .font(.headline)
            Button("Turn On Tracks Radio") { isRadioOn = true }
                .buttonStyle(.glass)
        }
        .padding(.horizontal, Design.Margin.page)
    }
}

/// Tracks Radio across the top of Radio: the page's title, the station's artwork, what it's
/// tuned to, what's on now, and Play and Tune, on a field of the station's own red.
private struct TracksRadioStage: View {
    @Environment(PlayerModel.self) private var player
    @AppStorage(PlayPreferences.radioTuningKey) private var storedTuning = ""
    @State private var showsTuner = false
    /// From a click on Play until the station plays: its first song is picked as it starts.
    @State private var isStarting = false

    var body: some View {
        let isOn = player.isPlayingTracksRadio
        let tuning = RadioTuning(stored: storedTuning)
        VStack(alignment: .leading, spacing: Design.Spacing.xxLarge) {
            Text("Radio")
                .font(.pageTitle)
                .accessibilityAddTraits(.isHeader)
            HStack(alignment: .center, spacing: Design.Margin.page) {
                Button(action: playOrPause) {
                    // The artwork names the station, so the words beside it don't again.
                    TracksRadioArt(side: Self.artSide, isLive: isOn && player.isPlaying, isDriving: player.isRadioDriving)
                }
                .buttonStyle(CoverPressButtonStyle())
                .help(isOn && player.isPlaying ? "Pause Tracks Radio" : isOn ? "Resume Tracks Radio" : "Play Tracks Radio")
                .accessibilityLabel("Tracks Radio")

                VStack(alignment: .leading, spacing: Design.Spacing.medium) {
                    if player.isRadioDriving {
                        Text("Driving")
                            .eyebrowStyle()
                    }
                    Text(tuning.summary)
                        .font(.title2.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    onAir(isOn: isOn)
                    HStack(spacing: Design.Control.spacing) {
                        Button(action: playOrPause) {
                            Label {
                                Text(isOn && player.isPlaying ? "Pause" : isOn ? "Resume" : "Play")
                            } icon: {
                                PlayStartingIcon(systemImage: isOn && player.isPlaying ? "pause.fill" : "play.fill", isStarting: isStarting)
                                    .contentTransition(.symbolEffect(.replace))
                            }
                        }
                        .buttonStyle(.stagePrimary(tint: TracksRadioArt.color))
                        .keyboardShortcut(.defaultAction)
                        Button("Tune…", systemImage: "slider.horizontal.3") { showsTuner = true }
                            .buttonStyle(.stageSecondary)
                            .help("Tune Tracks Radio")
                    }
                    .padding(.top, Design.Spacing.xSmall)
                }
                .frame(maxWidth: 520, alignment: .leading)
                Spacer(minLength: 0)
            }
        }
        .environment(\.colorScheme, .dark)
        .foregroundStyle(.white)
        .padding(.horizontal, Design.Margin.page)
        .padding(.top, Design.Spacing.xLarge)
        .padding(.bottom, Design.Margin.section)
        .frame(maxWidth: .infinity, alignment: .leading)
        .heroBackground { RadioField() }
        .contextMenu {
            Button(isOn && player.isPlaying ? "Pause" : isOn ? "Resume" : "Play Tracks Radio", systemImage: isOn && player.isPlaying ? "pause" : "play", action: playOrPause)
            Button("Tune Tracks Radio…", systemImage: "slider.horizontal.3") { showsTuner = true }
        }
        .sheet(isPresented: $showsTuner) { RadioTunerSheet() }
        .endsPlayStart($isStarting, hasStarted: isOn)
    }

    private func playOrPause() {
        if player.isPlayingTracksRadio {
            player.togglePlayPause()
        } else {
            isStarting = true
            player.playTracksRadio()
        }
    }

    /// What's on it now, while it plays: a line in the field's own type, not a panel.
    @ViewBuilder
    private func onAir(isOn: Bool) -> some View {
        if isOn, let track = player.current {
            HStack(spacing: Design.Spacing.small) {
                CoverImage(cover: track.cover, size: 32)
                VStack(alignment: .leading, spacing: Design.Shelf.lineGap) {
                    HStack(spacing: Design.Spacing.xSmall) {
                        PlayingWaveform(isActive: player.isPlaying, color: .white.opacity(0.7))
                        Text("On Air")
                    }
                    .eyebrowStyle()
                    HStack(spacing: Design.Spacing.small) {
                        Text(track.title)
                            .fontWeight(.medium)
                        Text(track.artistName)
                            .foregroundStyle(.secondary)
                    }
                    .font(.callout)
                }
            }
            .lineLimit(1)
            .transition(.opacity)
            .id(track.id)
        }
    }

    private static let artSide: CGFloat = 200
}

/// The station's red, deepened, fading into the page at its foot so the shelves below sit on
/// the page rather than under a hard edge.
private struct RadioField: View {
    var body: some View {
        Rectangle()
            .fill(TracksRadioArt.color.mix(with: .black, by: 0.4).gradient)
            .mask {
                LinearGradient(stops: [
                    .init(color: .black, location: 0),
                    .init(color: .black, location: 0.82),
                    .init(color: .clear, location: 1),
                ], startPoint: .top, endPoint: .bottom)
            }
    }
}

/// The station's cover as a button: it gives a little under a click, as Music's covers do.
private struct CoverPressButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shadow(color: .black.opacity(0.3), radius: 18, y: 10)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.98 : 1)
            .animation(Design.Motion.press, value: configuration.isPressed)
    }
}

#if DEBUG
#Preview("Radio") {
    PlayPreviewHost { RadioPage() }
        .frame(width: 1100, height: 760)
}
#endif
