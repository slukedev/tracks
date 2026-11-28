import SwiftUI
import TracksCore

/// Where the song on is playing from, under the scrubber beside its audio quality: shown once
/// there's more than one source. Its menu plays the song from another
/// copy, as Play From ranks them, and goes to its album.
struct SourceBadge: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(YourMusic.self) private var music
    @Environment(\.nowPlayingNavigate) private var navigate
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let sources = model.sourceEntries.filter(\.canPlay)
        if let address = player.playingService, let service = model.streaming.service(address) {
            // A music service through your server: the service is where it plays from.
            ServiceSourceLabel(service: service)
        } else if let track = player.current, let source = player.playingSource, sources.count > 1 {
            let name = sources.first { $0.id == source }?.name ?? SourceWords.fallbackName(for: source)
            Menu {
                let copies = model.mixesSources ? model.otherCopies(of: track, playingFrom: source) : []
                if !copies.isEmpty {
                    Section("Play From") {
                        ForEach(copies, id: \.source) { copy in
                            Button {
                                player.playFrom(copy.item)
                            } label: {
                                Label {
                                    Text(verbatim: sources.first { $0.id == copy.source }?.name ?? SourceWords.fallbackName(for: copy.source))
                                } icon: {
                                    SourceGlyph(source: copy.source)
                                }
                            }
                        }
                    }
                } else if !model.mixesSources {
                    let others = model.playerSources.filter { $0 != MusicSource(source) }
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
                }
                if let navigate, let goToAlbum = albumAction(track, navigate: navigate) {
                    Button("Go to Album", systemImage: "square.stack", action: goToAlbum)
                }
            } label: {
                HStack(spacing: Design.Spacing.xSmall) {
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
            .menuIndicator(.hidden)
            .animation(Design.Motion.reduced(Design.Motion.value, reduceMotion: reduceMotion), value: source)
            .accessibilityLabel(Text("Playing from \(name)"))
            .help("Play From")
        }
    }

    /// The song's album, wherever it's from: your own, Apple Music's, or your history's page.
    private func albumAction(_ track: PlayerTrack, navigate: @escaping (NowPlayingDestination) -> Void) -> (() -> Void)? {
        let go: (PlayRoute) -> Void = { navigate(.play($0)) }
        if let local = track.local {
            return SongActions.local(local, music: music, player: player, go: go).goToAlbum
        }
        if let song = track.song {
            return SongActions.catalog(song, player: player, go: go).goToAlbum
        }
        guard let album = track.albumTitle.map(StatsCalculator.folded), !album.isEmpty else { return nil }
        let artist = ArtistCredits.song(artist: track.artistName, title: track.title).first?.identity ?? StatsCalculator.folded(track.artistName)
        return { navigate(.stats(.album("\(album)\u{1F}\(artist)"))) }
    }
}

/// A music service's glyph and name, as the other sources' are shown.
private struct ServiceSourceLabel: View {
    let service: TracksideCatalogService

    var body: some View {
        HStack(spacing: Design.Spacing.xSmall) {
            ServiceGlyph(logoKey: service.logoKey ?? "", side: 12)
                .imageScale(.small)
            Text(verbatim: service.name)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .frame(minHeight: Design.Control.minimumTarget)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Playing from \(service.name)"))
    }
}

extension EnvironmentValues {
    /// Where Now Playing's menus lead, set by the player that shows them.
    @Entry var nowPlayingNavigate: ((NowPlayingDestination) -> Void)?
}

extension SourceWords {
    /// A source's name when it's no longer listed, as a server removed while its song plays.
    static func fallbackName(for source: SourceID) -> String {
        switch source {
        case .appleMusic: String(localized: "Apple Music")
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
                AdaptiveStack(horizontalAlignment: .center, verticalAlignment: .center, spacing: Design.Spacing.medium) {
                    VStack(spacing: 1) {
                        Text(notice.title)
                            .font(.subheadline.weight(.semibold))
                            .multilineTextAlignment(.center)
                        if let reason = notice.reason {
                            Text(reason)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if notice.nothingLeft, player.canPlayDownloads {
                        Button("Play Downloads", action: player.playDownloads)
                            .font(.subheadline.weight(.semibold))
                            .buttonStyle(.glassProminent)
                            .buttonBorderShape(.capsule)
                    }
                }
                .padding(.horizontal, Design.Spacing.large)
                .padding(.vertical, Design.Spacing.small)
                .frame(minHeight: Design.Control.minimumTarget)
                .glassEffect(.regular, in: .capsule)
                .padding(.horizontal, Design.Margin.page)
                .id(notice.id)
                .gesture(DragGesture(minimumDistance: 8).onEnded { value in
                    if value.translation.height > 12 { player.dismissSkipNotice() }
                })
                .transition(reduceMotion ? .opacity : .asymmetric(
                    insertion: .move(edge: .bottom).combined(with: .opacity),
                    removal: .move(edge: .bottom).combined(with: .opacity).animation(.easeOut(duration: 0.2))
                ))
                .onAppear {
                    let spoken = [notice.title, notice.reason].compactMap(\.self).joined(separator: ". ")
                    AccessibilityNotification.Announcement(spoken).post()
                }
            }
        }
        .animation(Design.Motion.reduced(Design.Motion.panel, reduceMotion: reduceMotion), value: player.skipNotice)
    }
}
