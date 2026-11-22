import UIKit
import CarPlay
import MusicKit
import TracksCore

/// Search, as Music has it in the car: Siri at the top, then the keyboard where the car
/// allows it (many don't while moving), then what you searched for lately, each a tap from its
/// results.
extension CarPlaySceneDelegate {
    /// Where the phone keeps its recent searches, one list for each music source.
    private var recentSearchesKey: String {
        model.musicSource == .yourMusic ? "recentYourMusicSearches" : "recentMusicSearches"
    }

    static let recentSearchLimit = 8

    /// Search's rows, read again as the tab opens: the phone's recent searches change with
    /// nothing to say so.
    func refreshSearch() {
        guard interface != nil else { return }
        searchBuiltFor = model.musicSource
        update(search, searchSections())
    }

    private func searchSections() -> [CPListSection] {
        var sections: [CPListSection] = []
        if allowsKeyboard {
            let row = CPListItem(text: String(localized: "Search"), detailText: nil, image: CarPlayImages.symbol("keyboard", side: CPListItem.maximumImageSize.height, scale: scale), accessoryImage: nil, accessoryType: .disclosureIndicator)
            row.handler = { [weak self] _, completion in
                self?.showKeyboardSearch()
                completion()
            }
            sections.append(CPListSection(items: [row]))
        }
        let recents = RecentSearches.list(UserDefaults.standard.string(forKey: recentSearchesKey) ?? "").prefix(Self.recentSearchLimit)
        if !recents.isEmpty {
            let symbol = CarPlayImages.symbol("magnifyingglass", side: CPListItem.maximumImageSize.height, scale: scale)
            let rows = recents.map { term in
                let row = CPListItem(text: term, detailText: nil, image: symbol, accessoryImage: nil, accessoryType: .disclosureIndicator)
                row.handler = { [weak self] _, completion in
                    self?.open({ await self?.resultsPage(for: term) }, then: completion) ?? completion()
                }
                return row
            }
            sections.append(CPListSection(items: rows, header: String(localized: "Recent Searches"), sectionIndexTitle: nil))
        }
        return sections
    }

    /// The car's keyboard, with results as it's typed.
    private func showKeyboardSearch() {
        guard interface != nil, allowsKeyboard else { return }
        let search = KeyboardSearch(car: self)
        keyboardSearch = search
        let template = CPSearchTemplate()
        template.delegate = search
        push(template)
    }

    /// The results for what's typed so far, as rows for the keyboard's list: songs play,
    /// albums and artists open.
    func keyboardResults(for term: String) async -> [KeyboardSearch.Result] {
        let side = CPListItem.maximumImageSize.height
        let scale = scale
        let player = model.player
        var results: [KeyboardSearch.Result] = []
        if model.musicSource == .yourMusic {
            let music = model.yourMusic
            let found = music.index.search(term, limit: 6)
            let tracks = found.tracks.filter(music.isPlayable)
            for (index, track) in tracks.enumerated() {
                let cover = await CarPlayImages.cover(url: music.artworkURL(track.artwork)?.absoluteString, seed: track.album ?? track.title, side: side, scale: scale)
                results.append(.init(item: CPListItem(text: track.title, detailText: track.artist, image: cover), plays: true) {
                    await player.start(.local(tracks, startingAt: index), from: .songs(term))
                } page: { nil })
            }
            for album in found.albums.prefix(4) {
                let cover = await CarPlayImages.cover(url: music.artworkURL(album.artwork)?.absoluteString, seed: album.title, side: side, scale: scale)
                results.append(.init(item: CPListItem(text: album.displayTitle, detailText: album.artist, image: cover, accessoryImage: nil, accessoryType: .disclosureIndicator), plays: false, action: {}) { [weak self] in
                    await self?.localAlbumPage(album)
                })
            }
            return results
        }
        guard musicAccess == .authorized, !model.isDemoLaunch else { return [] }
        var request = MusicCatalogSearchRequest(term: term, types: [Song.self, Album.self, Artist.self])
        request.limit = 6
        guard let response = try? await request.response() else { return [] }
        let songs = PlayPreferences.versions(of: Array(response.songs))
        for (index, song) in songs.enumerated() {
            let cover = await CarPlayImages.cover(song.artwork.map(CoverArt.artwork) ?? .url(nil, seed: song.title), side: side, scale: scale)
            let item = CPListItem(text: song.title, detailText: song.artistName, image: cover)
            item.isExplicitContent = song.contentRating == .explicit
            results.append(.init(item: item, plays: true) {
                await player.start(.songs(songs, startingAt: index), from: .songs(term))
            } page: { nil })
        }
        for album in PlayPreferences.versions(of: Array(response.albums)).prefix(4) {
            let cover = await CarPlayImages.cover(for: FeedItem(album: album), side: side, scale: scale)
            results.append(.init(item: CPListItem(text: album.title, detailText: album.artistName, image: cover, accessoryImage: nil, accessoryType: .disclosureIndicator), plays: false, action: {}) { [weak self] in
                await self?.appleMusicAlbumPage(album)
            })
        }
        for artist in response.artists.prefix(3) {
            let image = if let artwork = artist.artwork {
                await CarPlayImages.circle(await CarPlayImages.cover(.artwork(artwork), side: side, scale: scale))
            } else {
                CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale, round: true)
            }
            results.append(.init(item: CPListItem(text: artist.name, detailText: nil, image: image, accessoryImage: nil, accessoryType: .disclosureIndicator), plays: false, action: {}) { [weak self] in
                await self?.catalogArtistPage(artist)
            })
        }
        return results
    }

    /// A search kept with the phone's recent ones, at the top, as the phone does.
    func rememberSearch(_ term: String) {
        let defaults = UserDefaults.standard
        defaults.set(RecentSearches.adding(term, to: defaults.string(forKey: recentSearchesKey) ?? ""), forKey: recentSearchesKey)
    }

    // MARK: - Results

    /// A search's results, in Music's order: songs, artists, albums, playlists. The search
    /// moves back to the top of the recent ones, as it does on the phone.
    func resultsPage(for term: String) async -> CPListTemplate {
        rememberSearch(term)
        let sections = model.musicSource == .yourMusic ? await yourMusicResults(term) : await appleMusicResults(term)
        let page = CPListTemplate(title: term, sections: sections ?? [])
        page.emptyViewTitleVariants = [
            sections != nil ? String(localized: "No Results")
                : model.yourMusic.network.isOnline ? String(localized: "Couldn’t Search") : String(localized: "No Connection"),
        ]
        return page
    }

    /// Nil when Apple Music couldn't be asked.
    private func appleMusicResults(_ term: String) async -> [CPListSection]? {
        guard musicAccess == .authorized, !model.isDemoLaunch else { return nil }
        var request = MusicCatalogSearchRequest(term: term, types: [Song.self, Artist.self, Album.self, Playlist.self])
        request.limit = 8
        guard let response = try? await request.response() else { return nil }
        let player = model.player
        var sections: [CPListSection] = []

        let songs = PlayPreferences.versions(of: Array(response.songs))
        if !songs.isEmpty {
            let context = PlayContext.songs(term)
            var rows: [CPListItem] = []
            let (side, scale) = (CPListItem.maximumImageSize.height, scale)
            let standIn = CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
            for (index, song) in songs.enumerated() {
                let row = CPListItem(text: song.title, detailText: song.artistName, image: standIn)
                row.isExplicitContent = song.contentRating == .explicit
                mark(row) { [weak self] in self?.isPlayingSong(song.id) ?? false }
                row.handler = { [weak self] _, completion in
                    self?.play({ await player.start(.songs(songs, startingAt: index), from: context) }, completion: completion) ?? completion()
                }
                rows.append(row)
            }
            fillImages(rows) { index in
                let song = songs[index]
                let art = song.artwork.map(CoverArt.artwork) ?? .url(nil, seed: song.albumTitle ?? song.title)
                return await CarPlayImages.cover(art, side: side, scale: scale)
            }
            sections.append(CPListSection(items: rows, header: String(localized: "Songs"), sectionIndexTitle: nil))
        }

        let artists = Array(response.artists.prefix(4))
        if !artists.isEmpty {
            var rows: [CPListItem] = []
            let (side, scale) = (CPListItem.maximumImageSize.height, scale)
            let standIn = CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale, round: true)
            for artist in artists {
                let row = CPListItem(text: artist.name, detailText: nil, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
                row.handler = { [weak self] _, completion in
                    self?.open({ await self?.catalogArtistPage(artist) }, then: completion) ?? completion()
                }
                rows.append(row)
            }
            fillImages(rows) { index in
                guard let artwork = artists[index].artwork else { return nil }
                return await CarPlayImages.circle(await CarPlayImages.cover(.artwork(artwork), side: side, scale: scale))
            }
            sections.append(CPListSection(items: rows, header: String(localized: "Artists"), sectionIndexTitle: nil))
        }

        let albums = PlayPreferences.versions(of: Array(response.albums)).prefix(6)
        if !albums.isEmpty {
            sections.append(CPListSection(items: albumRows(Array(albums)), header: String(localized: "Albums"), sectionIndexTitle: nil))
        }
        let playlists = Array(response.playlists.prefix(4))
        if !playlists.isEmpty {
            sections.append(CPListSection(items: playlistRows(playlists), header: String(localized: "Playlists"), sectionIndexTitle: nil))
        }
        return sections
    }

    private func yourMusicResults(_ term: String) async -> [CPListSection]? {
        let music = model.yourMusic
        let player = model.player
        let found = music.index.search(term, limit: 8)
        var sections: [CPListSection] = []

        let tracks = found.tracks.filter(music.isPlayable)
        if !tracks.isEmpty {
            let context = PlayContext.songs(term)
            var rows: [CPListItem] = []
            let (side, scale) = (CPListItem.maximumImageSize.height, scale)
            let standIn = CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
            for (index, track) in tracks.enumerated() {
                let row = CPListItem(text: track.title, detailText: track.artist, image: standIn)
                mark(row) { [weak self] in self?.isPlayingTrack(track.id) ?? false }
                row.handler = { [weak self] _, completion in
                    self?.play({ await player.start(.local(tracks, startingAt: index), from: context) }, completion: completion) ?? completion()
                }
                rows.append(row)
            }
            fillImages(rows) { index in
                let track = tracks[index]
                return await CarPlayImages.cover(url: music.artworkURL(track.artwork)?.absoluteString, seed: track.album ?? track.title, side: side, scale: scale)
            }
            sections.append(CPListSection(items: rows, header: String(localized: "Songs"), sectionIndexTitle: nil))
        }
        if !found.artists.isEmpty {
            let side = CPListItem.maximumImageSize.height
            var rows: [CPListItem] = []
            let artists = Array(found.artists.prefix(4))
            let standIn = CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale, round: true)
            for artist in artists {
                let row = CPListItem(text: artist.name, detailText: nil, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
                row.handler = { [weak self] _, completion in
                    self?.open({ await self?.localArtistPage(artist) }, then: completion) ?? completion()
                }
                rows.append(row)
            }
            let scale = scale
            fillImages(rows) { index in
                guard let picture = music.artistPicture(for: artists[index]) else { return nil }
                return await CarPlayImages.circle(await CarPlayImages.cover(picture, side: side, scale: scale))
            }
            sections.append(CPListSection(items: rows, header: String(localized: "Artists"), sectionIndexTitle: nil))
        }
        if !found.albums.isEmpty {
            var rows: [CPListItem] = []
            let albums = Array(found.albums.prefix(6))
            let (side, scale) = (CPListItem.maximumImageSize.height, scale)
            let standIn = CarPlayImages.placeholder(symbol: "square.stack", side: side, scale: scale)
            for album in albums {
                let row = CPListItem(text: album.displayTitle, detailText: album.artist, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
                row.handler = { [weak self] _, completion in
                    self?.open({ await self?.localAlbumPage(album) }, then: completion) ?? completion()
                }
                rows.append(row)
            }
            fillImages(rows) { index in
                await CarPlayImages.cover(url: music.artworkURL(albums[index].artwork)?.absoluteString, seed: albums[index].title, side: side, scale: scale)
            }
            sections.append(CPListSection(items: rows, header: String(localized: "Albums"), sectionIndexTitle: nil))
        }
        return sections
    }

    /// An artist from Apple Music: their picture, Play and Shuffle their top songs, the songs
    /// numbered, then their albums.
    func catalogArtistPage(_ artist: Artist) async -> CPListTemplate {
        let player = model.player
        let detailed = (try? await artist.with([.topSongs, .albums])) ?? artist
        let songs = PlayPreferences.versions(of: Array(detailed.topSongs ?? []))
        let albums = PlayPreferences.versions(of: Array(detailed.albums ?? [])).prefix(12)
        let context = PlayContext(kind: .artist, title: artist.name)
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale),
            title: artist.name,
            subtitle: nil,
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(artist.name) ?? false },
                play: { await player.start(.songs(songs), from: context) },
                shuffle: songs.isEmpty ? nil : { await player.start(.songs(songs), from: context, shuffled: true) }
            )
        )
        let scale = scale
        if let artwork = artist.artwork {
            // Square: the car frames a header's picture in a rounded square of its own.
            fillThumbnail(header) { await CarPlayImages.cover(.artwork(artwork), side: side, scale: scale) }
        }
        let songRows = songs.prefix(10).enumerated().map { index, song in
            let row = numberedRow(index + 1, title: song.title, detail: song.albumTitle)
            row.isExplicitContent = song.contentRating == .explicit
            mark(row) { [weak self] in self?.isPlayingSong(song.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.songs(songs, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            return row
        }
        let albumRows = albumRows(Array(albums)) { album in album.releaseDate.map { String(Calendar.current.component(.year, from: $0)) } }
        return detailPage(header, [
            CPListSection(items: songRows, header: String(localized: "Top Songs"), sectionIndexTitle: nil),
            CPListSection(items: albumRows, header: String(localized: "Albums"), sectionIndexTitle: nil),
        ], empty: String(localized: "No Songs"))
    }
}

/// The car's keyboard search: results as the driver types, a moment after they stop, and a
/// tap on one plays it or opens it.
@MainActor
final class KeyboardSearch: NSObject, CPSearchTemplateDelegate {
    struct Result {
        let item: CPListItem
        /// Whether a tap plays it, rather than opening its page.
        let plays: Bool
        let action: () async -> Void
        let page: () async -> CPListTemplate?
    }

    private weak var car: CarPlaySceneDelegate?
    private var term = ""
    private var results: [ObjectIdentifier: Result] = [:]
    private var running: Task<Void, Never>?
    /// The car asks for every letter typed; each one is answered with the newest results.
    private var waiting: [([CPListItem]) -> Void] = []

    init(car: CarPlaySceneDelegate) {
        self.car = car
    }

    func searchTemplate(_ searchTemplate: CPSearchTemplate, updatedSearchText searchText: String, completionHandler: @escaping ([CPListItem]) -> Void) {
        term = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        waiting.append(completionHandler)
        running?.cancel()
        let term = term
        running = Task { [weak self] in
            // Waits out the typing, so a long name isn't searched for a letter at a time.
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled, let self else { return }
            let found = term.isEmpty ? [] : await car?.keyboardResults(for: term) ?? []
            guard !Task.isCancelled else { return }
            results = Dictionary(found.map { (ObjectIdentifier($0.item), $0) }, uniquingKeysWith: { first, _ in first })
            let answer = found.map(\.item)
            let handlers = waiting
            waiting = []
            handlers.forEach { $0(answer) }
        }
    }

    func searchTemplate(_ searchTemplate: CPSearchTemplate, selectedResult item: CPListItem, completionHandler: @escaping () -> Void) {
        guard let car, let result = results[ObjectIdentifier(item)] else { return completionHandler() }
        car.rememberSearch(term)
        if result.plays {
            car.play(result.action, completion: completionHandler)
        } else {
            car.open(result.page, then: completionHandler)
        }
    }

    func searchTemplateSearchButtonPressed(_ searchTemplate: CPSearchTemplate) {
        guard let car, !term.isEmpty else { return }
        let term = term
        car.open({ [weak car] in await car?.resultsPage(for: term) }, then: {})
    }
}
