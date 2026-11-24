import SwiftUI
import TracksCore

/// Where the song on is playing from, under the scrubber beside its audio quality: shown once
/// there's more than one source, and always for Spotify. Its menu plays the song from another
/// source that has it.
struct SourceBadge: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let sources = model.sourceEntries.filter(\.canPlay)
        if let source = player.playingSource, source == .spotify || sources.count > 1 {
            let name = sources.first { $0.id == source }?.name ?? SourceWords.fallbackName(for: source)
            let others = model.playerSources.filter { $0 != MusicSource(source) }
            Menu {
                if !others.isEmpty {
                    Section("Play From") {
                        ForEach(others, id: \.self) { other in
                            Button {
                                Task { await model.switchSourceCarryingOn(to: other) }
                            } label: {
                                Label(other.title, systemImage: other.symbol)
                            }
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    SourceGlyph(source: source)
                        .frame(width: 12, height: 12)
                        .contentTransition(.symbolEffect(.replace))
                    Text(verbatim: name)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .contentTransition(.opacity)
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(minHeight: Design.Control.minimumTarget)
                .contentShape(.rect)
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .animation(Design.Motion.reduced(Design.Motion.value, reduceMotion: reduceMotion), value: source)
            .accessibilityLabel(Text("Playing from \(name)"))
        }
    }
}

extension SourceWords {
    /// A source's name when it's no longer listed, as a server removed while its song plays.
    static func fallbackName(for source: SourceID) -> String {
        switch source {
        case .appleMusic: String(localized: "Apple Music")
        case .spotify: String(localized: "Spotify")
        case .device: MusicSource.deviceName
        case .server, .unknown: String(localized: "Server")
        }
    }
}

/// "Skipped 3 songs" with why, above the mini player for a moment: songs left out of the
/// queue because they can't play now. Swiped down, it goes at once.
struct SkipNoticeCapsule: View {
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let notice = player.skipNotice {
                VStack(spacing: 1) {
                    Text(notice.title)
                        .font(.subheadline.weight(.semibold))
                    if let reason = notice.reason {
                        Text(reason)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, Design.Spacing.large)
                .padding(.vertical, Design.Spacing.small)
                .glassEffect(.regular, in: .capsule)
                .id(notice.id)
                .gesture(DragGesture(minimumDistance: 8).onEnded { value in
                    if value.translation.height > 12 { player.dismissSkipNotice() }
                })
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                .onAppear {
                    let spoken = [notice.title, notice.reason].compactMap(\.self).joined(separator: ". ")
                    AccessibilityNotification.Announcement(spoken).post()
                }
            }
        }
        .animation(Design.Motion.reduced(Design.Motion.panel, reduceMotion: reduceMotion), value: player.skipNotice)
    }
}
