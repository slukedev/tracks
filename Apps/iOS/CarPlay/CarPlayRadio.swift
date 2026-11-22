import UIKit
import CarPlay
import MusicKit
import TracksCore

/// Radio: everything that plays on its own once started, each a tap from playing. Tracks Radio
/// leads the tab in its own colours, every mood and your genres sit under it as tiles, Driving
/// first, and Apple's live stations follow.
extension CarPlaySceneDelegate {
    /// Radio's rows. `local` leaves out what has to be worked out or fetched (your genres and
    /// Apple's stations), for the tab's first build as the car connects.
    func radioSections(local: Bool = false) async -> [CPListSection] {
        let feed = model.playFeed
        let player = model.player
        let model = self.model
        var sections = accessSection()

        radioRowState = radioState.rawValue

        // Every mood, as the phone's tiles. Drawn, never fetched, so the tab is never empty.
        let moods = [Mood.drive] + Mood.allCases.filter { $0 != .drive }
        let side = CPListImageRowItemImageGridElement.maximumImageSize.height
        var moodTiles: [CPListImageRowItemImageGridElement] = []
        for mood in moods {
            let image = await CarPlayImages.mood(mood, side: side, scale: scale)
            moodTiles.append(CPListImageRowItemImageGridElement(image: image, imageShape: .roundedRectangle, title: mood.title, accessorySymbolName: nil))
        }
        // On as many lines as they need: CarPlay draws every one, past its usual eight. Named
        // by the section, not the row: a row's name carries a chevron, and every mood is
        // already here, so there's no page for it to open.
        let moodRow = CPListImageRowItem(text: nil, imageGridElements: moodTiles, allowsMultipleLines: true)
        moodRow.listImageRowHandler = { [weak self] _, index, completion in
            guard moods.indices.contains(index) else { return completion() }
            let mood = moods[index]
            self?.play({ await MoodPlayback.start(mood, model: model) }, completion: completion) ?? completion()
        }
        sections.append(CPListSection(items: [moodRow], header: String(localized: "Moods"), sectionIndexTitle: nil))

        guard !local else { return sections }
        if let genres = await genreSection() {
            sections.append(genres)
        }

        // Apple's stations only play from Apple Music. The cover's LIVE tag says what they are.
        if model.musicSource == .appleMusic, !feed.liveStations.isEmpty {
            let stations = Array(feed.liveStations.prefix(6))
            let rowSide = CPListItem.maximumImageSize.height
            var items: [CPListItem] = []
            let late = CarPlayImages.Late()
            let covers = await CarPlayImages.batch(stations.count, standIn: { _ in
                CarPlayImages.placeholder(symbol: "dot.radiowaves.left.and.right", side: rowSide, scale: self.scale)
            }, late: { index, image in
                late.set(index, stations[index].isLive ? CarPlayImages.live(image) : image)
            }) { [scale] index in
                await CarPlayImages.cover(for: stations[index], side: rowSide, scale: scale)
            }
            for (station, cover) in zip(stations, covers) {
                let item = CPListItem(text: station.title, detailText: nil, image: station.isLive ? CarPlayImages.live(cover) : cover)
                mark(item) { [weak self] in self?.isPlaying(station.title) ?? false }
                item.handler = { [weak self] _, completion in
                    self?.play({ await Self.play(station, player: player) }, completion: completion) ?? completion()
                }
                items.append(item)
            }
            late.attach(items)
            sections.append(CPListSection(items: items, header: String(localized: "Live Radio"), sectionIndexTitle: nil))
        }
        return sections
    }

    /// Redraws Radio when Tracks Radio's row would say something else: it's started, paused,
    /// or become available.
    func refreshRadioIfNeeded() {
        guard radioRowState != radioState.rawValue else { return }
        refreshRadioList()
    }

    private enum RadioState: String {
        case off, idle, playing, paused
    }

    private var radioState: RadioState {
        guard hasTracksRadio else { return .off }
        // By what's playing, not its name: a playlist called Tracks Radio isn't it.
        guard model.player.isPlayingTracksRadio else { return .idle }
        // A song still coming from your server is playing, not paused.
        return model.player.isPlaying || model.player.status == .loading ? .playing : .paused
    }

    /// Tracks Radio at the top of the tab, in its own colours, with Play that follows it.
    /// None when there isn't enough listening for it yet, or it's off.
    func updateRadioHeader() {
        guard radioState != .off else {
            setHeader(nil, on: radio, key: "off")
            return
        }
        guard headerBuiltFrom[ObjectIdentifier(radio)] != "tracksRadio" else { return }
        let (side, scale, player) = (Self.headerThumbnailSide, scale, model.player)
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "dot.radiowaves.left.and.right", side: side, scale: scale),
            title: String(localized: "Tracks Radio"),
            subtitle: nil,
            adaptiveBackground: true,
            playback: HeaderPlayback(isOn: { player.isPlayingTracksRadio }, play: { [weak self] in await self?.startOrResumeTracksRadio() })
        )
        fillThumbnail(header) { await CarPlayImages.tracksRadio(side: side, scale: scale) }
        setHeader(header, on: radio, key: "tracksRadio")
    }

    /// Tracks Radio from a card or a suggestion: picked up where it paused if it's what's on,
    /// otherwise started. Never started over while it plays.
    func startOrResumeTracksRadio() async {
        let player = model.player
        if player.isPlayingTracksRadio {
            if !player.isPlaying { player.togglePlayPause() }
        } else {
            await player.startTracksRadio()
        }
    }
}
