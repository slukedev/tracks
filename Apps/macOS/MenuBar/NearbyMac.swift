import SwiftUI
import TracksCore
import TracksMusic

/// Your other devices, on the Mac: they see what Tracks is playing here and can play, pause
/// and skip it; the menu bar shows what they're playing, with their controls.
///
/// The commands and the song itself are the app model's, as on iPhone. What's the Mac's own
/// is where its sound goes, which changes without the player knowing.
@MainActor
enum NearbyMac {
    static func start(_ model: AppModel) {
        guard !model.isDemoLaunch else { return }
        let nearby = model.nearby
        nearby.start()
        // Only while a device is connected, so a Mac alone never follows its output.
        Task {
            var output: SystemOutputFollower?
            var isFollowing = false
            for await isConnected in Observations({ !nearby.devices.isEmpty }) where isConnected != isFollowing {
                isFollowing = isConnected
                output?.stop()
                output = isConnected ? SystemOutputFollower { model.tellNearby() } : nil
            }
        }
    }
}

/// What your other devices are playing, under the Mac's own, with their controls.
struct NearbyMenuSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let devices = model.nearby.withSongs
        if !devices.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(devices) { device in
                    NearbyMenuRow(device: device)
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 12)
        }
    }
}

private struct NearbyMenuRow: View {
    let device: NearbyDevices.Device
    @Environment(AppModel.self) private var model

    var body: some View {
        let state = model.nearby.shownState(of: device) ?? NearbyState()
        HStack(spacing: 10) {
            ArtworkView(url: state.artworkURL, seed: state.album ?? state.title ?? device.name, size: 36)
            VStack(alignment: .leading, spacing: 1) {
                Label(state.isPlaying ? "Playing on \(device.nameWithSpeakers)" : "Paused on \(device.nameWithSpeakers)", systemImage: device.kind.symbol)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(state.isPlaying ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    .lineLimit(1)
                Text([state.title, state.artist].compactMap(\.self).joined(separator: " · "))
                    .font(.subheadline)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            // Always all three, so the row doesn't reflow as a station or queue end comes and goes.
            HStack(spacing: 0) {
                control("Previous", "backward.fill", .previous)
                    .disabled(!state.canSkipBack)
                control(state.isPlaying ? "Pause" : "Play", state.isPlaying ? "pause.fill" : "play.fill", state.isPlaying ? .pause : .play)
                control("Next", "forward.fill", .next)
                    .disabled(!state.canSkipForward)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func control(_ title: LocalizedStringKey, _ symbol: String, _ action: RemoteAction) -> some View {
        Button(title, systemImage: symbol) { model.nearby.perform(action, on: device) }
            .labelStyle(.iconOnly)
            .buttonStyle(BarIconButtonStyle(diameter: 28))
            .help(Text(title))
    }
}
