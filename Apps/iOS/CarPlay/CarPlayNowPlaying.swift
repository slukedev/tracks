import UIKit
import CarPlay
import MusicKit
import TracksCore

/// Now Playing in the car: CarPlay's own screen, with four of Tracks’ buttons under the
/// controls, always in the same places (see `CarNowPlayingButton`), and Up Next as covers that
/// keep up with the queue. The song's name opens its album, as it does in Music.
extension CarPlaySceneDelegate {
    func configureNowPlaying() {
        let nowPlaying = CPNowPlayingTemplate.shared
        nowPlaying.add(self)
        nowPlaying.isUpNextButtonEnabled = true
        nowPlaying.upNextTitle = String(localized: "Up Next")
        updateNowPlayingButtons()

        // The buttons show what they'd change, so they follow the song, its star, its plays and
        // the settings, and Up Next follows the queue while it's open.
        let player = model.player
        nowPlayingWatch = Task { [weak self] in
            let changes = Observations {
                NowPlayingKey(
                    song: player.current?.id,
                    upNext: player.upNext.map(\.id),
                    isAutoplaying: player.isAutoplaying,
                    autoplay: PlayPreferences.autoplay,
                    context: player.context?.title,
                    kind: player.context.map { "\($0.kind)" },
                    isPlaying: player.isPlaying,
                    isLoading: player.status == .loading,
                    isFavorite: player.current?.song.map(player.isFavorite),
                    isLive: player.isLive,
                    askedForMore: player.current.map { player.moreLikeThis.contains($0.songIdentity) } ?? false,
                    sharePlay: CarPlaySceneDelegate.sharePlayRowState
                )
            }
            var lastSong: String?
            var last: NowPlayingKey?
            for await key in changes {
                guard let self else { return }
                if key.song != lastSong {
                    lastSong = key.song
                    // Asked once a song, so the star shows whether it's already a favorite.
                    if let song = player.current?.song, Self.canFavorite(song) { player.lookUpFavorite(song) }
                }
                updateNowPlayingButtons()
                refreshHeaders()
                refreshRadioIfNeeded()
                if key.song != last?.song || key.context != last?.context || key.kind != last?.kind {
                    refreshPlayingMarks()
                }
                // Up Next only for what it shows: not for play and pause, or the star.
                if queue != nil, last.map({ !key.showsSameQueue(as: $0) }) ?? true { refreshQueue() }
                last = key
            }
        }
    }

    private struct NowPlayingKey: Equatable {
        let song: String?
        let upNext: [String]
        let isAutoplaying: Bool
        let autoplay: Bool
        let context: String?
        let kind: String?
        let isPlaying: Bool
        /// For Tracks Radio's row in Radio, which counts a song still loading as playing.
        let isLoading: Bool
        let isFavorite: Bool?
        let isLive: Bool
        let askedForMore: Bool
        /// Up Next's SharePlay row, and which of its songs passengers added.
        let sharePlay: String

        /// Whether Up Next would read the same for both.
        func showsSameQueue(as other: NowPlayingKey) -> Bool {
            song == other.song && upNext == other.upNext && isAutoplaying == other.isAutoplaying
                && autoplay == other.autoplay && context == other.context && kind == other.kind
                && isLive == other.isLive && sharePlay == other.sharePlay
        }
    }

    /// Whether the star can be given: only a song from Apple Music's catalog has a rating to
    /// change. Library songs carry an id Apple Music's ratings don't take.
    static func canFavorite(_ song: Song) -> Bool {
        !song.id.rawValue.hasPrefix("i.")
    }

    func updateNowPlayingButtons() {
        // The template is shared: a car that has gone, or is going as another connects, mustn't
        // set buttons for the one that's there.
        guard isActiveCar else { return }
        let player = model.player
        let current = player.current
        let playing: CarNowPlayingButton.Playing = if current == nil {
            .nothing
        } else if player.isLive {
            .live(canAskForMore: player.canAskForMoreLikeThis, askedForMore: current.map { player.moreLikeThis.contains($0.songIdentity) } ?? false)
        } else if player.context?.isStation == true {
            .station
        } else {
            .queue
        }
        let song = current?.song
        let canFavorite = song.map(Self.canFavorite) ?? false
        let row = CarNowPlayingButton.row(
            for: playing,
            autoplay: PlayPreferences.autoplay,
            canFavorite: canFavorite,
            isFavorite: song.map(player.isFavorite) ?? false
        )
        let albumEnabled = current.map(hasAlbum) ?? false
        // Sent to the car only when one would look or work differently: each update redraws
        // the whole row of buttons there.
        var hasher = Hasher()
        hasher.combine(current?.id)
        hasher.combine("\(row)")
        hasher.combine(albumEnabled)
        let signature = hasher.finalize()
        guard signature != shownButtons else { return }
        shownButtons = signature
        CPNowPlayingTemplate.shared.updateNowPlayingButtons(row.map { nowPlayingButton($0, song: song) })
        CPNowPlayingTemplate.shared.isAlbumArtistButtonEnabled = albumEnabled
    }

    private func nowPlayingButton(_ button: CarNowPlayingButton, song: Song?) -> CPNowPlayingButton {
        let player = model.player
        let made: CPNowPlayingButton = switch button {
        case .shuffle:
            CPNowPlayingShuffleButton { _ in player.toggleShuffle() }
        case .moreLikeThis:
            imageButton("hand.thumbsup") { player.playMoreLikeThis() }
        case .autoplay:
            imageButton("infinity") { [weak self] in
                player.setAutoplay(!PlayPreferences.autoplay)
                // A setting, which nothing observed says has changed.
                self?.updateNowPlayingButtons()
            }
        case .favorite(_, let isSelected):
            imageButton(isSelected ? "star.fill" : "star") {
                guard let song else { return }
                player.setFavorite(song, !isSelected)
            }
        case .notForMe:
            imageButton("hand.thumbsdown") {
                guard let current = player.current else { return }
                player.setSuggestLess(current.songIdentity, true)
                player.skipToNext()
            }
        }
        made.isEnabled = button.isEnabled
        made.isSelected = button.isSelected
        return made
    }

    private func imageButton(_ symbol: String, action: @escaping () -> Void) -> CPNowPlayingImageButton {
        CPNowPlayingImageButton(image: UIImage(systemName: symbol) ?? UIImage()) { _ in action() }
    }

    /// Whether the song on has an album to open: one from Apple Music, off a station, or one
    /// of yours.
    private func hasAlbum(_ track: PlayerTrack) -> Bool {
        if let local = track.local { return model.yourMusic.index.album(id: local.albumKey) != nil }
        return track.song != nil && track.albumTitle != nil && model.player.context?.isStation != true
    }

    func nowPlayingTemplateUpNextButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {
        guard interface != nil else { return }
        // Already open, or on its way (a second tap before it came up): left as it is.
        if let queue, mover?.isOnTrail(ObjectIdentifier(queue)) == true { return }
        guard mover?.isMoving == false else { return }
        let template = CPListTemplate(title: String(localized: "Up Next"), sections: [])
        queue = template
        isQueueShown = false
        shownQueue = nil
        refreshQueue()
        // Counted as open only once it's on screen: until then a refresh from the watch
        // mustn't take it for closed and stop keeping it current. Deep in a tab, the trail
        // starts again from the tab, under Now Playing.
        push(template) { [weak self] in
            guard let self, queue === template else { return }
            isQueueShown = mover?.isOnTrail(ObjectIdentifier(template)) == true
        }
    }

    /// The song's name opens its album, as Music's does.
    func nowPlayingTemplateAlbumArtistButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {
        guard interface != nil, let track = model.player.current else { return }
        open({ [weak self] in
            guard let self else { return nil }
            if let local = track.local, let album = model.yourMusic.index.album(id: local.albumKey) {
                return await localAlbumPage(album)
            } else if let song = track.song, let album = await SongLinks.album(of: song) {
                return await appleMusicAlbumPage(album)
            }
            return nil
        }, then: {})
    }

    /// Up Next with covers: what's coming, then SharePlay, where passengers add to it. The list
    /// is drawn at once and the covers fill in, so it keeps up with the queue.
    func refreshQueue() {
        guard let queue else { return }
        // Gone from the screen: stop keeping it current.
        if isQueueShown, let interface, !interface.templates.contains(where: { $0 === queue }) {
            self.queue = nil
            isQueueShown = false
            return
        }
        let player = model.player
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
        var covered: [(row: CPListItem, track: PlayerTrack)] = []
        var sections: [CPListSection] = []
        var rows: [CPListItem] = []
        // Room for SharePlay's row.
        let limit = max(0, Int(CPListTemplate.maximumItemCount) - 1)
        // Spotify's songs carry its mark, as its terms ask.
        let spotifyMark = player.playingSource == .spotify ? UIImage(named: "SpotifyIconMono") : nil
        for track in player.upNext.prefix(min(40, limit)) {
            let row = CPListItem(text: track.title, detailText: track.artistName, image: standIn, accessoryImage: spotifyMark, accessoryType: .none)
            row.isExplicitContent = track.isExplicit
            row.handler = { [weak self] _, completion in
                // By the song, not its place: the queue may have moved on since the list opened.
                if let index = player.upNext.firstIndex(where: { $0.id == track.id }) {
                    player.jump(toUpNext: index)
                }
                completion()
                self?.popScreen()
            }
            covered.append((row, track))
            rows.append(row)
        }
        if rows.isEmpty {
            let picksNext = player.isAutoplaying || (PlayPreferences.autoplay && player.current != nil && !player.isLive && player.context?.isStation != true)
            let row = CPListItem(text: picksNext ? String(localized: "Autoplay Is On") : String(localized: "Nothing Queued"), detailText: nil)
            row.isEnabled = false
            rows.append(row)
        }
        sections.append(CPListSection(items: rows))
        sections.append(CPListSection(items: [sharePlayRow()]))
        let capped = Self.capped(sections)
        let signature = Self.signature(of: capped)
        guard signature != shownQueue else { return }
        shownQueue = signature
        queue.updateSections(capped)
        let tracks = covered.map(\.track)
        fillImages(covered.map(\.row)) { [weak self] index in
            await self?.cover(for: tracks[index], side: side)
        }
    }

    func cover(for track: PlayerTrack, side: CGFloat) async -> UIImage {
        if let local = track.local {
            return await CarPlayImages.cover(url: model.yourMusic.artworkURL(local.artwork)?.absoluteString, seed: track.albumTitle ?? track.title, side: side, scale: scale)
        }
        return await CarPlayImages.cover(track.cover, side: side, scale: scale, album: track.albumTitle.map { ($0, track.artistName) })
    }
}
