import SwiftUI
import TracksCore

/// Your other devices and your servers' speakers, beside AirPlay in Now Playing, while there
/// are any. Its own view, so what they say every few seconds redraws only this, not the
/// player.
struct NowPlayingDevicesButton: View {
    let action: () -> Void
    @Environment(AppModel.self) private var model
    @AppStorage(NearbyDevices.storageKey) private var findsDevices = true

    var body: some View {
        if (findsDevices && !model.nearby.devices.isEmpty) || model.speakers.hasSpeakers {
            Button(action: action) {
                Image(systemName: "laptopcomputer.and.iphone")
                    .font(.title3)
                    .foregroundStyle(.primary.opacity(0.7))
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Your Devices")
            .transition(.opacity)
            Spacer()
        }
    }
}
