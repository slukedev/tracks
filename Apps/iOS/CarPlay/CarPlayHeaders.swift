import UIKit
import CarPlay
import CoreMedia
import TracksCore

/// The header at the top of a list: a cover, a name, a line under it, and Play (with Shuffle
/// on an album, playlist or artist). Play turns to Pause while what it plays is playing, on
/// every header still open.
extension CarPlaySceneDelegate {
    /// What a header plays, and how it knows that's what's on.
    struct HeaderPlayback {
        /// Whether what's loaded in the player is this, playing or paused.
        let isOn: @MainActor () -> Bool
        let play: () async -> Void
        var shuffle: (() async -> Void)?
        /// Where it stands, for a song left part played: its place and its length.
        var progress: (elapsed: TimeInterval, duration: TimeInterval)?
    }

    static var headerThumbnailSide: CGFloat {
        CPThumbnailImage.maximumImageSize(forAspectRatio: 1).height
    }

    func detailsHeader(
        cover: UIImage,
        title: String,
        subtitle: String?,
        adaptiveBackground: Bool = false,
        playback: HeaderPlayback
    ) -> CPListTemplateDetailsHeader {
        let configuration = playbackConfiguration(playback)
        let header = CPListTemplateDetailsHeader(
            thumbnail: CPThumbnailImage(image: cover),
            title: title,
            subtitle: subtitle.flatMap { $0.isEmpty ? nil : $0 },
            actionButtons: headerButtons(playback, showing: configuration.playbackAction)
        )
        header.wantsAdaptiveBackgroundStyle = adaptiveBackground
        header.playbackConfiguration = configuration
        if headers.count > 40 { headers.removeAll { $0.header.value == nil } }
        headers.append((WeakHeader(value: header), playback))
        return header
    }

    /// Play, or pause when it's what's playing: CarPlay draws the first button as the control
    /// and calls it either way.
    private func headerPlayTapped(_ playback: HeaderPlayback) {
        guard interface != nil else { return }
        let player = model.player
        guard playback.isOn() else {
            play(playback.play, completion: {})
            return
        }
        let resumes = !player.isPlaying
        player.togglePlayPause()
        refreshHeaders()
        if resumes {
            let tap = tapped()
            showNowPlaying { [weak self] in self?.isLatest(tap) ?? false }
        }
    }

    private func headerButtons(_ playback: HeaderPlayback, showing action: CPPlaybackConfiguration.Action) -> [CPButton] {
        let symbol = action == .pause ? "pause.fill" : "play.fill"
        var buttons = [headerButton(symbol: symbol) { [weak self] in self?.headerPlayTapped(playback) }]
        if let shuffle = playback.shuffle {
            buttons.append(headerButton(symbol: "shuffle") { [weak self] in
                self?.play(shuffle, completion: {})
            })
        }
        return buttons
    }

    private func headerButton(symbol: String, action: @escaping () -> Void) -> CPButton {
        let side = CPListTemplateDetailsHeader.maximumActionButtonSize
        let configuration = UIImage.SymbolConfiguration(pointSize: min(side.width, side.height) * 0.5, weight: .semibold)
        let image = UIImage(systemName: symbol, withConfiguration: configuration) ?? UIImage()
        return CPButton(image: image) { _ in action() }
    }

    func playbackConfiguration(_ playback: HeaderPlayback) -> CPPlaybackConfiguration {
        let isPlaying = playback.isOn() && model.player.isPlaying
        let progress = playback.progress
        return CPPlaybackConfiguration(
            preferredPresentation: .audio,
            playbackAction: isPlaying ? .pause : .play,
            elapsedTime: CMTime(seconds: progress?.elapsed ?? 0, preferredTimescale: 600),
            duration: CMTime(seconds: progress?.duration ?? 0, preferredTimescale: 600)
        )
    }

    /// Play or pause on every header still open, as the music starts and stops.
    func refreshHeaders() {
        headers.removeAll { $0.header.value == nil }
        let lists = ([listenNow, radio] + (interface?.templates ?? [])).compactMap { $0 as? CPListTemplate }
        for index in headers.indices {
            let (box, playback) = headers[index]
            guard let header = box.value else { continue }
            let wanted = playbackConfiguration(playback)
            guard header.playbackConfiguration?.playbackAction != wanted.playbackAction else { continue }
            // The car draws a header's control as it was when the header was given to the list,
            // so a new one, the same but for its control, takes the old one's place.
            let fresh = CPListTemplateDetailsHeader(
                thumbnail: header.thumbnail,
                title: header.title,
                subtitle: header.subtitle,
                actionButtons: headerButtons(playback, showing: wanted.playbackAction)
            )
            fresh.wantsAdaptiveBackgroundStyle = header.wantsAdaptiveBackgroundStyle
            fresh.playbackConfiguration = wanted
            headers[index].header = WeakHeader(value: fresh)
            for list in lists where list.listHeader === header {
                list.listHeader = fresh
            }
        }
    }

    /// The header's cover once it's drawn, in place of its stand-in.
    func fillThumbnail(_ header: CPListTemplateDetailsHeader, _ load: @escaping @MainActor () async -> UIImage?) {
        Task {
            guard let image = await load() else { return }
            header.thumbnail = CPThumbnailImage(image: image)
        }
    }
}

/// A header held without keeping it: a page gone from the car takes its header with it.
struct WeakHeader {
    weak var value: CPListTemplateDetailsHeader?
}
