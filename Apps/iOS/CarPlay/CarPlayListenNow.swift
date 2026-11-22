import UIKit
import CarPlay
import MusicKit
import TracksCore

/// Listen Now: one thing to play at the top, a tap away, then a few cards and a handful of
/// shelves, so the tab is a short scan of pictures rather than of words, as Music's is. Each
/// shelf is one line of covers, and its name opens the whole of it as a list.
extension CarPlaySceneDelegate {
    /// What Listen Now leads with and the cards under it, worked out from what's on the phone.
    struct ListenNowContent {
        let plan: CarListenNowPlan
        let cards: [CarCard]
    }

    func listenNowCards() -> ListenNowContent {
        let feed = model.playFeed
        let mixes = feed.mixes
        let plan = CarListenNowPlan(
            hasContinue: model.player.waitingSession?.tracks.first != nil,
            hasTracksRadio: hasTracksRadio,
            rightNow: mixes.rightNow?.id,
            otherMixes: (mixes.otherTimes + mixes.mixes).map(\.id),
            hasDiscover: model.musicSource == .appleMusic && !feed.discover.isEmpty
        )
        let side = CPListImageRowItemCardElement.maximumImageSize.height
        let cards = plan.cards.compactMap { card -> CarCard? in
            switch card {
            case .tracksRadio: tracksRadioCard(side: side)
            case .driving: drivingCard(side: side)
            case .discover: discoverCard(side: side)
            case .mix(let id): mixes.mix(id: id).map { self.card(for: $0, side: side) }
            }
        }
        return ListenNowContent(plan: plan, cards: cards)
    }

    // MARK: - The header

    /// The one thing to play at the top: where you left off, Tracks Radio, or Driving. Made again
    /// only when what it leads with changes; its Play follows the music on its own.
    func updateListenNowHeader(_ lead: CarListenNowPlan.Lead) {
        guard !(model.musicSource == .yourMusic && !model.yourMusic.hasMusic && !model.isDemoLaunch) else {
            setHeader(nil, on: listenNow, key: "none")
            listenNow.emptyViewTitleVariants = [String(localized: "No Music")]
            return
        }
        listenNow.emptyViewTitleVariants = [String(localized: "No Music")]
        let player = model.player
        let side = Self.headerThumbnailSide
        let scale = scale
        switch lead {
        case .continueListening:
            guard let session = player.waitingSession, let song = session.tracks.first else { return }
            let key = "continue.\(song.id)"
            guard headerBuiltFrom[ObjectIdentifier(listenNow)] != key else { return }
            let header = detailsHeader(
                cover: CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale),
                title: song.title,
                subtitle: song.artistName,
                playback: HeaderPlayback(
                    isOn: { false },
                    play: { if player.waitingSession != nil { player.togglePlayPause() } },
                    progress: song.duration.map { (session.time, $0) }
                )
            )
            fillThumbnail(header) { [weak self] in await self?.cover(for: song, side: side) }
            setHeader(header, on: listenNow, key: key)
        case .tracksRadio:
            guard headerBuiltFrom[ObjectIdentifier(listenNow)] != "tracksRadio" else { return }
            let header = detailsHeader(
                cover: CarPlayImages.placeholder(symbol: "dot.radiowaves.left.and.right", side: side, scale: scale),
                title: String(localized: "Tracks Radio"),
                subtitle: nil,
                playback: HeaderPlayback(isOn: { player.isPlayingTracksRadio }, play: { [weak self] in await self?.startOrResumeTracksRadio() })
            )
            fillThumbnail(header) { await CarPlayImages.tracksRadio(side: side, scale: scale) }
            setHeader(header, on: listenNow, key: "tracksRadio")
        case .driving:
            guard headerBuiltFrom[ObjectIdentifier(listenNow)] != "driving" else { return }
            let model = model
            let header = detailsHeader(
                cover: CarPlayImages.placeholder(symbol: Mood.drive.symbol, side: side, scale: scale),
                title: Mood.drive.title,
                subtitle: nil,
                playback: HeaderPlayback(isOn: { [weak self] in self?.isPlaying(Mood.drive.title) ?? false }, play: { await MoodPlayback.start(.drive, model: model) })
            )
            fillThumbnail(header) { await CarPlayImages.mood(.drive, side: side, scale: scale) }
            setHeader(header, on: listenNow, key: "driving")
        }
    }

    func setHeader(_ header: CPListTemplateDetailsHeader?, on template: CPListTemplate, key: String) {
        guard headerBuiltFrom[ObjectIdentifier(template)] != key else { return }
        headerBuiltFrom[ObjectIdentifier(template)] = key
        template.listHeader = header
    }

    // MARK: - Sections

    /// Listen Now as soon as the car connects, from what's on the phone alone: the cards, with
    /// stand-ins for the pictures still on their way. The shelves follow once they're built.
    func listenNowFirstSections(_ content: ListenNowContent, late: CarPlayImages.Late) -> [CPListSection] {
        var sections = accessSection()
        guard !content.cards.isEmpty else { return sections }
        let side = CPListImageRowItemCardElement.maximumImageSize.height
        let standIns = content.cards.map { CarPlayImages.placeholder(symbol: $0.standIn, side: side, scale: scale) }
        sections.append(forYouSection(content.cards, images: standIns, tints: content.cards.map { _ in nil }, late: late))
        return sections
    }

    /// Everything, once the shelves are built. The cards' pictures come from `cardImages`, the
    /// one fetch the first build started, so a slow connection isn't asked for them twice.
    func listenNowSections(_ content: ListenNowContent, cardImages: Task<[Int: UIImage], Never>, late: CarPlayImages.Late) async -> [CPListSection] {
        var sections = accessSection()
        let isAppleMusic = model.musicSource == .appleMusic
        // Side by side: one after another, each waiting on its covers over a slow connection,
        // the tab took ten seconds to appear.
        let downloaded = Slot<CPListSection?>(nil)
        let recent = Slot<CPListSection?>(nil)
        let yourMixes = Slot<CPListSection?>(nil)
        let newReleases = Slot<CPListSection?>(nil)
        let isOffline = isAppleMusic && !model.yourMusic.network.isOnline
        let shelves = [
            Task { if isOffline { downloaded.value = await downloadedShelf() } },
            Task { recent.value = isAppleMusic ? await feedShelf(String(localized: "Recently Played"), model.playFeed.recentlyPlayed) : await recentAlbumsShelf() },
            Task { yourMixes.value = await mixesShelf(leaving: content.plan.cardedMixes) },
            Task { if isAppleMusic { newReleases.value = await feedShelf(String(localized: "New from Your Artists"), model.playFeed.newReleases) } },
        ]
        let found = await withTaskCancellationHandler { await cardImages.value } onCancel: { cardImages.cancel() }
        if !content.cards.isEmpty {
            let side = CPListImageRowItemCardElement.maximumImageSize.height
            let images = content.cards.enumerated().map { index, card in
                found[index] ?? CarPlayImages.placeholder(symbol: card.standIn, side: side, scale: scale)
            }
            var tints: [UIColor?] = []
            for (index, image) in images.enumerated() {
                tints.append(found[index] == nil ? nil : await CarPlayImages.tint(of: image))
            }
            sections.append(forYouSection(content.cards, images: images, tints: tints, late: late))
        }
        await Self.finish(shelves)
        // Offline, what's on the phone comes first: the rest won't play.
        if let downloaded = downloaded.value { sections.append(downloaded) }
        if let recent = recent.value { sections.append(recent) }
        if let yourMixes = yourMixes.value { sections.append(yourMixes) }
        if let newReleases = newReleases.value { sections.append(newReleases) }
        return sections
    }

    /// The cards' pictures, fetched once for the tab's build, side by side. What isn't in
    /// within a second and a half arrives through `late` onto the cards on screen.
    func fetchCardImages(_ cards: [CarCard], late: CarPlayImages.Late) -> Task<[Int: UIImage], Never> {
        Task {
            await CarPlayImages.gather(cards.count, wait: .milliseconds(1500), late: { index, image in
                late.set(index, image)
            }) { index in
                await cards[index].image()
            }
        }
    }

    /// The For You row: the cards with their pictures, a tap from playing, and its name a tap
    /// from the same as a list.
    private func forYouSection(_ cards: [CarCard], images: [UIImage], tints: [UIColor?], late: CarPlayImages.Late) -> CPListSection {
        let feed = model.playFeed
        let player = model.player
        let elements = zip(zip(cards, images), tints).map { pair, tint in
            CPListImageRowItemCardElement(image: pair.1, showsImageFullHeight: false, title: pair.0.title, subtitle: nil, tintColor: tint)
        }
        late.attach(elements)
        let title = String(localized: "For You")
        let row = CPListImageRowItem(text: title, cardElements: elements, allowsMultipleLines: false)
        // What the cards play, so a mix remade under the same name still reloads.
        row.userInfo = Self.contentToken(
            [player.waitingSession?.tracks.first?.id ?? "", "\(hasTracksRadio)"]
                + feed.mixes.all.flatMap { [$0.id] + $0.songs.map(\.songIdentity) }
                + feed.discover.map(\.id.rawValue)
        )
        let rows = zip(cards, images).map { card, image in
            CarRow(image: image, title: card.title, detail: nil, action: card.action)
        }
        row.listImageRowHandler = { [weak self] _, index, completion in
            guard let self, rows.indices.contains(index) else { return completion() }
            play(rows[index].action, completion: completion)
        }
        row.handler = { [weak self] _, completion in
            guard let self else { return completion() }
            openPage(title, rows, completion: completion)
        }
        return CPListSection(items: [row])
    }

    // MARK: - Cards

    private func tracksRadioCard(side: CGFloat) -> CarCard {
        let scale = scale
        return CarCard(title: String(localized: "Tracks Radio"), image: { await CarPlayImages.tracksRadio(side: side, scale: scale) }) { [weak self] in
            await self?.startOrResumeTracksRadio()
        }
    }

    private func drivingCard(side: CGFloat) -> CarCard {
        let (scale, model) = (scale, model)
        return CarCard(title: Mood.drive.title, image: { await CarPlayImages.mood(.drive, side: side, scale: scale) }) {
            await MoodPlayback.start(.drive, model: model)
        }
    }

    private func discoverCard(side: CGFloat) -> CarCard {
        let (scale, player, songs) = (scale, model.player, model.playFeed.discover)
        let title = String(localized: "Discover")
        let art = songs.first?.artwork.map(CoverArt.artwork) ?? .url(nil, seed: title)
        return CarCard(title: title, image: { await CarPlayImages.cover(art, side: side, scale: scale) }) {
            await player.start(.songs(songs), from: PlayContext(kind: .mix, title: title), shuffled: true)
        }
    }

    private func card(for mix: Mix, side: CGFloat) -> CarCard {
        let songs = model.player.songs(in: mix)
        let (player, scale) = (model.player, scale)
        // No symbol in the corner on a card: the car writes the name under the cover.
        return CarCard(title: mix.kind.title, standIn: "music.note.list", image: { await CarPlayImages.mix(mix, side: side, scale: scale, badged: false) }) {
            await player.start(.history(songs), from: PlayContext(kind: .mix, title: mix.kind.title))
        }
    }

    // MARK: - Shelves

    /// Your mixes not already on a card, as covers. With your own music, only the ones enough
    /// of whose songs you have.
    private func mixesShelf(leaving carded: Set<String>) async -> CPListSection? {
        let player = model.player
        let isAppleMusic = model.musicSource == .appleMusic
        let yourMusic = model.yourMusic
        let mixes = model.playFeed.mixes.mixes.filter { mix in
            guard !carded.contains(mix.id) else { return false }
            guard !isAppleMusic else { return true }
            return mix.songs.lazy.filter { yourMusic.track(for: HistorySong($0)) != nil }.count >= 5
        }
        let scale = scale
        let tiles = mixes.map { mix in
            let songs = player.songs(in: mix)
            return ShelfTile(title: mix.kind.title, standIn: "music.note.list", cover: { side in
                await CarPlayImages.mix(mix, side: side, scale: scale, badged: false)
            }) {
                await player.start(.history(songs), from: PlayContext(kind: .mix, title: mix.kind.title))
            }
        }
        return await gridShelf(String(localized: "Your Mixes"), tiles, token: mixes.flatMap { [$0.id] + $0.songs.map(\.songIdentity) })
    }

    /// Albums, playlists or stations from Apple Music's feed, a tap from playing.
    private func feedShelf(_ title: String, _ items: [FeedItem]) async -> CPListSection? {
        let player = model.player
        let scale = scale
        let tiles = items.map { item in
            ShelfTile(title: item.title, standIn: "music.note", cover: { side in
                await CarPlayImages.cover(for: item, side: side, scale: scale)
            }) {
                await Self.play(item, player: player)
            }
        }
        return await gridShelf(title, tiles, token: items.map(\.id))
    }

    /// With your own music, the albums you played lately, newest first.
    private func recentAlbumsShelf() async -> CPListSection? {
        let music = model.yourMusic
        let player = model.player
        var seen = Set<String>()
        var albums: [LocalAlbum] = []
        for capture in model.library.history.captures.suffix(400).reversed() where albums.count < CPListTemplate.maximumItemCount {
            let song = HistorySong(songID: capture.songID, title: capture.title, artistName: capture.artistName, albumTitle: capture.albumTitle)
            guard let track = music.track(for: song), seen.insert(track.albumKey).inserted,
                  let album = music.index.album(id: track.albumKey) else { continue }
            albums.append(album)
        }
        let tiles = albums.map { album in
            let tracks = album.tracks.filter(music.isPlayable)
            return ShelfTile(title: album.displayTitle, standIn: "music.note", cover: { [weak self] side in
                await self?.localCover(album, side: side) ?? CarPlayImages.placeholder(symbol: "music.note", side: side, scale: 2)
            }) {
                await player.start(.local(tracks), from: PlayContext(kind: .album, title: album.title))
            }
        }
        return await gridShelf(String(localized: "Recently Played"), tiles, token: albums.map(\.id))
    }

    /// Offline, what's downloaded, as covers: the only music that will play.
    private func downloadedShelf() async -> CPListSection? {
        guard musicAccess == .authorized, !model.isDemoLaunch else { return nil }
        var request = MusicLibraryRequest<Album>()
        request.includeOnlyDownloadedContent = true
        request.sort(by: \.lastPlayedDate, ascending: false)
        request.limit = CPListTemplate.maximumItemCount
        let albums = (try? await request.response().items).map(Array.init) ?? []
        return await feedShelf(String(localized: "Downloaded"), albums.map(FeedItem.init(album:)))
    }

    /// One line of covers with their names under them, each a tap from playing, and the name of
    /// the row a tap from all of them as a list. Past one line the car keeps the room and draws
    /// nothing, so the rest are on the list.
    private func gridShelf(_ title: String, _ all: [ShelfTile], token: [String]) async -> CPListSection? {
        let tiles = Array(all.prefix(shelfLength))
        guard tiles.count >= 3 else { return nil }
        let (side, scale) = (CPListImageRowItemImageGridElement.maximumImageSize.height, scale)
        let late = CarPlayImages.Late()
        let images = await CarPlayImages.batch(tiles.count, standIn: { index in
            CarPlayImages.placeholder(symbol: tiles[index].standIn, side: side, scale: scale)
        }, late: { index, image in
            late.set(index, image)
        }) { index in
            await tiles[index].cover(side)
        }
        let elements = zip(tiles, images).map { tile, image in
            CPListImageRowItemImageGridElement(image: image, imageShape: .roundedRectangle, title: tile.title, accessorySymbolName: nil)
        }
        late.attach(elements)
        let row = CPListImageRowItem(text: title, imageGridElements: elements, allowsMultipleLines: false)
        row.userInfo = Self.contentToken(token)
        row.listImageRowHandler = { [weak self] _, index, completion in
            guard let self, tiles.indices.contains(index) else { return completion() }
            play(tiles[index].action, completion: completion)
        }
        let listed = Array(all.prefix(CPListTemplate.maximumItemCount))
        let rowSide = CPListItem.maximumImageSize.height
        row.handler = { [weak self] _, completion in
            let rows = listed.map { tile in
                CarRow(title: tile.title, detail: nil, cover: { await tile.cover(rowSide) }, action: tile.action)
            }
            self?.openPage(title, rows, completion: completion) ?? completion()
        }
        return CPListSection(items: [row])
    }

    /// Waits for shelves being built side by side, and stops them if the tab's build is
    /// cancelled, as when the car disconnects or the build runs out of time.
    private static func finish(_ builds: [Task<Void, Never>]) async {
        await withTaskCancellationHandler {
            for build in builds { await build.value }
        } onCancel: {
            for build in builds { build.cancel() }
        }
    }

    // MARK: - Pages

    /// A shelf's page: everything on it as rows, a tap from playing. It opens at once, and
    /// covers not yet drawn fill in as it's looked at.
    func openPage(_ title: String, _ rows: [CarRow], completion: @escaping () -> Void) {
        let standIn = CarPlayImages.placeholder(symbol: "music.note", side: CPListItem.maximumImageSize.height, scale: scale)
        let items = rows.map { row in
            let item = CPListItem(text: row.title, detailText: row.detail, image: row.image ?? standIn)
            mark(item) { [weak self] in row.isPlaying?() ?? self?.isPlaying(row.title) ?? false }
            item.handler = { [weak self] _, completion in
                self?.play(row.action, completion: completion) ?? completion()
            }
            return item
        }
        let page = CPListTemplate(title: title, sections: Self.capped([CPListSection(items: items)]))
        push(page, completion: completion)
        let covers = rows.map(\.cover)
        fillImages(items) { index in
            guard covers.indices.contains(index), let cover = covers[index] else { return nil }
            return await cover()
        }
    }

    // MARK: - Feed items

    /// Plays a station, album or playlist from the feed or the library, whole.
    static func play(_ item: FeedItem, player: PlayerModel) async {
        switch item.content {
        case .station, .demoStation:
            if let (request, context) = item.stationRequest {
                await player.start(request, from: context)
            }
        case .album(let album):
            await player.start(.album(album), from: PlayContext(kind: .album, title: album.title))
        case .playlist(let playlist):
            await player.start(.playlist(playlist), from: PlayContext(kind: .playlist, title: playlist.name))
        }
    }
}

/// A row on a shelf's page: its picture (or how to draw it), its name, its line, and what a
/// tap plays.
struct CarRow {
    var image: UIImage?
    var cover: (@MainActor () async -> UIImage)?
    /// Whether it's what's playing, when that isn't the row's name.
    var isPlaying: (@MainActor () -> Bool)?
    let title: String
    let detail: String?
    let action: () async -> Void

    init(image: UIImage, title: String, detail: String?, action: @escaping () async -> Void) {
        self.image = image
        self.title = title
        self.detail = detail
        self.action = action
    }

    init(title: String, detail: String?, cover: @escaping @MainActor () async -> UIImage, action: @escaping () async -> Void) {
        self.cover = cover
        self.title = title
        self.detail = detail
        self.action = action
    }
}

/// A card on For You: its name, its stand-in's symbol, how to draw its picture, and what a
/// tap plays.
struct CarCard {
    let title: String
    var standIn = "music.note"
    let image: @MainActor () async -> UIImage
    let action: () async -> Void
}

/// A cover on a shelf: its name, the stand-in's symbol, how to draw it at a size, and what a
/// tap plays.
struct ShelfTile {
    let title: String
    let standIn: String
    let cover: @MainActor (CGFloat) async -> UIImage
    let action: () async -> Void
}

/// A shelf built side by side with the others, kept here until they're put in order. Not
/// `async let`: its child tasks run off the main actor, and CarPlay's sections can't leave it.
@MainActor
private final class Slot<Value> {
    var value: Value

    init(_ value: Value) {
        self.value = value
    }
}
