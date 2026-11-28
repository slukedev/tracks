import SwiftUI
import TracksCore

#if os(iOS)
/// The mini player while a speaker's song is on show: the song, the speaker, and its play
/// button. Tapping it opens the full player for the speaker.
struct SpeakerMiniPlayer: View {
    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    let open: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        let track = model.speakerTrack(speaker, on: address.serverID)
        let isConnecting = model.speakers.isConnecting(address)
        HStack(spacing: 10) {
            Button(action: open) {
                HStack(spacing: 10) {
                    CoverImage(cover: track.cover, size: placement == .inline ? 26 : 32)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(track.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        if placement != .inline {
                            Label(speaker.name, systemImage: speaker.symbol)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(speaker.isReachable ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("\(track.title), playing on \(speaker.name)"))
            .accessibilityHint("Shows the player")
            if isConnecting {
                ProgressView()
                    .controlSize(.small)
                    .frame(width: 44, height: 44)
                    .transition(.opacity)
            } else {
                SpeakerTransport(address: address, speaker: speaker, showsPrevious: false)
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .animation(Design.Motion.fade, value: isConnecting)
    }
}
#endif
