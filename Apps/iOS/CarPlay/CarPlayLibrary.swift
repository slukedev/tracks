import UIKit
import CarPlay
import MusicKit
import TracksCore

/// Library: where your music is kept, a place at a time, as Music's Library is in the car.
/// Playlists, Artists, Albums, Songs and Downloaded open their lists, and what you added lately
/// sits under them as covers. An album, playlist or artist opens as a page with its cover,
/// Play and Shuffle at the top and its songs below, rather than playing at a touch.
extension CarPlaySceneDelegate {
    // MARK: - Apple Music

    /// Playlists, Artists, Albums, Songs and Downloaded, which need nothing fetched to show.
    func appleMusicPlaces() -> CPListSection? {
        guard musicAccess == .authorized, !model.isDemoLaunch else { return nil }
        return CPListSection(items: [
            place(String(localized: "Playlists"), symbol: "music.note.list") { [weak self] in await self?.appleMusicPlaylistsPage() },
            place(String(localized: "Artists"), symbol: "music.microphone") { [weak self] in await self?.appleMusicArtistsPage() },
            place(String(localized: "Albums"), symbol: "square.stack") { [weak self] in await self?.appleMusicAlbumsPage() },
            place(String(localized: "Songs"), symbol: "music.note") { [weak self] in await self?.appleMusicSongsPage() },
            place(String(localized: "Downloaded"), symbol: "arrow.down.circle") { [weak self] in await self?.appleMusicDownloadedPage() },
        ])
    }

    func appleMusicLibrarySections() async -> [CPListSection] {
        guard let places = appleMusicPlaces() else {
            library.emptyViewTitleVariants = [String(localized: "No Music")]
            return []
        }
        var sections = [places]

        var request = MusicLibraryRequest<Album>()
        request.sort(by: \.libraryAddedDate, ascending: false)
        request.limit = 12
        if let albums = try? await request.response().items, !albums.isEmpty {
            let items = Array(FeedItem.versions(albums.map(FeedItem.init(album:))).prefix(shelfLength))
            let (side, scale) = (CPListImageRowItemImageGridElement.maximumImageSize.height, scale)
            let late = CarPlayImages.Late()
            let images = await CarPlayImages.batch(items.count, standIn: { _ in
                CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
            }, late: { index, image in
                late.set(index, image)
            }) { index in
                await CarPlayImages.cover(for: items[index], side: side, scale: scale)
            }
            let tiles = zip(items, images).map { item, image in
                CPListImageRowItemImageGridElement(image: image, imageShape: .roundedRectangle, title: item.title, accessorySymbolName: nil)
            }
            late.attach(tiles)
            // Named by the section: a row's name carries a chevron, with no page to open here.
            let row = CPListImageRowItem(text: nil, imageGridElements: tiles, allowsMultipleLines: false)
            row.listImageRowHandler = { [weak self] _, index, completion in
                guard items.indices.contains(index), case .album(let album) = items[index].content else { return completion() }
                self?.open({ await self?.appleMusicAlbumPage(album) }, then: completion) ?? completion()
            }
            sections.append(CPListSection(items: [row], header: String(localized: "Recently Added"), sectionIndexTitle: nil))
        }
        return sections
    }

    private func appleMusicPlaylistsPage() async -> CPListTemplate {
        var request = MusicLibraryRequest<Playlist>()
        request.sort(by: \.lastPlayedDate, ascending: false)
        request.limit = CPListTemplate.maximumItemCount
        let playlists = (try? await request.response().items).map(Array.init) ?? []
        let rows = playlistRows(playlists)
        return page(String(localized: "Playlists"), [CPListSection(items: rows)], empty: String(localized: "No Playlists"))
    }

    /// Playlists as rows, their covers filling in once the page is open.
    func playlistRows(_ playlists: [Playlist]) -> [CPListItem] {
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.note.list", side: side, scale: scale)
        var rows: [CPListItem] = []
        for playlist in playlists {
            let item = FeedItem(playlist: playlist)
            let row = CPListItem(text: playlist.name, detailText: item.subtitle, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
            mark(row) { [weak self] in self?.isPlaying(playlist.name) ?? false }
            row.handler = { [weak self] _, completion in
                self?.open({ await self?.appleMusicPlaylistPage(playlist) }, then: completion) ?? completion()
            }
            rows.append(row)
        }
        let scale = scale
        fillImages(rows) { index in
            await CarPlayImages.cover(for: FeedItem(playlist: playlists[index]), side: side, scale: scale)
        }
        return rows
    }

    private func appleMusicAlbumsPage() async -> CPListTemplate {
        var request = MusicLibraryRequest<Album>()
        request.sort(by: \.libraryAddedDate, ascending: false)
        request.limit = CPListTemplate.maximumItemCount
        let albums = (try? await request.response().items).map(Array.init) ?? []
        return page(String(localized: "Albums"), [CPListSection(items: albumRows(albums))], empty: String(localized: "No Albums"))
    }

    /// Albums as rows, their covers filling in once the page is open.
    func albumRows(_ albums: [Album], detail: (Album) -> String? = { $0.artistName }) -> [CPListItem] {
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "square.stack", side: side, scale: scale)
        var rows: [CPListItem] = []
        for album in albums {
            let row = CPListItem(text: album.title, detailText: detail(album), image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
            row.isExplicitContent = album.contentRating == .explicit
            mark(row) { [weak self] in self?.isPlaying(album.title) ?? false }
            row.handler = { [weak self] _, completion in
                self?.open({ await self?.appleMusicAlbumPage(album) }, then: completion) ?? completion()
            }
            rows.append(row)
        }
        let scale = scale
        fillImages(rows) { index in
            await CarPlayImages.cover(for: FeedItem(album: albums[index]), side: side, scale: scale)
        }
        return rows
    }

    /// Artists A to Z, indexed by letter, each in a circle as Music draws them: the picture
    /// Apple Music has, filled in once the page is open, and a quiet stand-in until then.
    private func appleMusicArtistsPage() async -> CPListTemplate {
        var request = MusicLibraryRequest<Artist>()
        request.sort(by: \.name, ascending: true)
        request.limit = CPListTemplate.maximumItemCount
        let artists = (try? await request.response().items).map(Array.init) ?? []
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale, round: true)
        let rows = artists.map { artist in
            let row = CPListItem(text: artist.name, detailText: nil, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
            mark(row) { [weak self] in self?.isPlaying(artist.name) ?? false }
            row.handler = { [weak self] _, completion in
                self?.open({ await self?.appleMusicArtistPage(artist) }, then: completion) ?? completion()
            }
            return row
        }
        let (scale, feed) = (scale, model.playFeed)
        fillImages(rows) { index in
            await CarPlayImages.artistPicture(named: artists[index].name, side: side, scale: scale, feed: feed)
        }
        return page(String(localized: "Artists"), byLetter(rows, names: artists.map(\.name)), empty: String(localized: "No Artists"))
    }

    /// An artist in your library: their picture, Play and Shuffle their songs, then their
    /// albums, newest first.
    private func appleMusicArtistPage(_ artist: Artist) async -> CPListTemplate {
        let player = model.player
        var albumRequest = MusicLibraryRequest<Album>()
        albumRequest.filter(matching: \.artists, contains: artist)
        albumRequest.sort(by: \.releaseDate, ascending: false)
        let albums = (try? await albumRequest.response().items).map(Array.init) ?? []
        var songRequest = MusicLibraryRequest<Song>()
        songRequest.filter(matching: \.artists, contains: artist)
        let songs = (try? await songRequest.response().items).map(Array.init) ?? []

        let context = PlayContext(kind: .artist, title: artist.name)
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale),
            title: artist.name,
            subtitle: Self.count(albums: albums.count, songs: songs.count),
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(artist.name) ?? false },
                play: { await player.start(.songs(songs), from: context) },
                shuffle: songs.isEmpty ? nil : { await player.start(.songs(songs), from: context, shuffled: true) }
            )
        )
        let (scale, feed) = (scale, model.playFeed)
        // Square: the car frames a header's picture in a rounded square of its own.
        fillThumbnail(header) { await CarPlayImages.artistPicture(named: artist.name, side: side, scale: scale, feed: feed, round: false) }
        let rows = albumRows(Array(albums.prefix(CPListTemplate.maximumItemCount))) { album in album.releaseDate.map { String(Calendar.current.component(.year, from: $0)) } }
        return detailPage(header, [CPListSection(items: rows, header: String(localized: "Albums"), sectionIndexTitle: nil)], empty: String(localized: "No Albums"))
    }

    /// Every song, A to Z, under a row that shuffles the lot: in the car, Songs is mostly
    /// for Shuffle.
    private func appleMusicSongsPage() async -> CPListTemplate {
        let player = model.player
        var request = MusicLibraryRequest<Song>()
        request.sort(by: \.title, ascending: true)
        request.limit = max(1, CPListTemplate.maximumItemCount - 1)
        let songs = (try? await request.response().items).map(Array.init) ?? []
        guard !songs.isEmpty else {
            return page(String(localized: "Songs"), [], empty: String(localized: "No Songs"))
        }
        let shuffle = shuffleAllRow {
            // From the whole library, not just the page: a few hundred, picked at random.
            var all = MusicLibraryRequest<Song>()
            all.limit = 5000
            let every = (try? await all.response().items).map(Array.init) ?? songs
            let picked = Array(every.shuffled().prefix(300))
            await player.start(.songs(picked), from: .songs(String(localized: "Songs")))
        }
        let context = PlayContext.songs(String(localized: "Songs"))
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
        let rows = songs.enumerated().map { index, song in
            let row = CPListItem(text: song.title, detailText: song.artistName, image: standIn)
            row.isExplicitContent = song.contentRating == .explicit
            mark(row) { [weak self] in self?.isPlayingSong(song.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.songs(songs, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            return row
        }
        let scale = scale
        fillImages(rows) { index in
            let song = songs[index]
            let art = song.artwork.map(CoverArt.artwork) ?? .url(nil, seed: song.albumTitle ?? song.title)
            return await CarPlayImages.cover(art, side: side, scale: scale, album: song.albumTitle.map { ($0, song.artistName) })
        }
        return page(String(localized: "Songs"), [CPListSection(items: [shuffle] + rows)], empty: String(localized: "No Songs"))
    }

    /// What's on the iPhone, to play with no signal: downloaded playlists, then albums.
    private func appleMusicDownloadedPage() async -> CPListTemplate {
        var playlistRequest = MusicLibraryRequest<Playlist>()
        playlistRequest.includeOnlyDownloadedContent = true
        playlistRequest.sort(by: \.lastPlayedDate, ascending: false)
        playlistRequest.limit = 25
        let playlists = (try? await playlistRequest.response().items).map(Array.init) ?? []
        var albumRequest = MusicLibraryRequest<Album>()
        albumRequest.includeOnlyDownloadedContent = true
        albumRequest.sort(by: \.libraryAddedDate, ascending: false)
        albumRequest.limit = max(1, CPListTemplate.maximumItemCount - playlists.count)
        let albums = (try? await albumRequest.response().items).map(Array.init) ?? []

        var sections: [CPListSection] = []
        if !playlists.isEmpty {
            sections.append(CPListSection(items: playlistRows(playlists), header: String(localized: "Playlists"), sectionIndexTitle: nil))
        }
        if !albums.isEmpty {
            sections.append(CPListSection(items: albumRows(albums), header: String(localized: "Albums"), sectionIndexTitle: nil))
        }
        return page(String(localized: "Downloaded"), sections, empty: String(localized: "No Downloads"))
    }

    func appleMusicAlbumPage(_ album: Album) async -> CPListTemplate {
        let player = model.player
        let detailed = (try? await album.with([.tracks])) ?? album
        let songs: [Song] = (detailed.tracks ?? []).compactMap { track in
            if case .song(let song) = track { return song }
            return nil
        }
        let context = PlayContext(kind: .album, title: album.title)
        let year = album.releaseDate.map { String(Calendar.current.component(.year, from: $0)) }
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale),
            title: album.title,
            subtitle: Self.line([album.artistName, year]),
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(album.title) ?? false },
                play: { await player.start(.album(album), from: context) },
                shuffle: songs.isEmpty ? nil : { await player.start(.album(album), from: context, shuffled: true) }
            )
        )
        let scale = scale
        fillThumbnail(header) { await CarPlayImages.cover(for: FeedItem(album: album), side: side, scale: scale) }
        let numbers = Self.trackNumbers(songs.map(\.trackNumber))
        let rows = songs.prefix(CPListTemplate.maximumItemCount).enumerated().map { index, song in
            // The track's number where a cover would be, as Music lists an album.
            let row = numberedRow(numbers[index], title: song.title, detail: song.artistName == album.artistName ? Self.length(song.duration) : song.artistName)
            row.isExplicitContent = song.contentRating == .explicit
            mark(row) { [weak self] in self?.isPlayingSong(song.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.songs(songs, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            return row
        }
        return detailPage(header, [CPListSection(items: rows)], empty: String(localized: "No Songs"))
    }

    func appleMusicPlaylistPage(_ playlist: Playlist) async -> CPListTemplate {
        let player = model.player
        let detailed = (try? await playlist.with([.tracks])) ?? playlist
        let songs: [Song] = (detailed.tracks ?? []).compactMap { track in
            if case .song(let song) = track { return song }
            return nil
        }
        let context = PlayContext(kind: .playlist, title: playlist.name)
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.note.list", side: side, scale: scale),
            title: playlist.name,
            subtitle: Self.songCount(songs.count),
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(playlist.name) ?? false },
                play: { await player.start(.playlist(playlist), from: context) },
                shuffle: songs.isEmpty ? nil : { await player.start(.playlist(playlist), from: context, shuffled: true) }
            )
        )
        let scale = scale
        fillThumbnail(header) { await CarPlayImages.cover(for: FeedItem(playlist: playlist), side: side, scale: scale) }
        let rows = songs.prefix(CPListTemplate.maximumItemCount).enumerated().map { index, song in
            let row = numberedRow(index + 1, title: song.title, detail: song.artistName)
            row.isExplicitContent = song.contentRating == .explicit
            mark(row) { [weak self] in self?.isPlayingSong(song.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.songs(songs, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            return row
        }
        return detailPage(header, [CPListSection(items: rows)], empty: String(localized: "No Songs"))
    }

    // MARK: - Your music

    /// Your library's places, which need nothing fetched to show.
    func yourMusicPlaces() -> CPListSection? {
        let music = model.yourMusic
        guard !music.index.isEmpty else { return nil }
        var places: [CPListItem] = []
        if !music.playlists.all.isEmpty {
            places.append(place(String(localized: "Playlists"), symbol: "music.note.list") { [weak self] in await self?.localPlaylistsPage() })
        }
        places.append(place(String(localized: "Artists"), symbol: "music.microphone") { [weak self] in await self?.localArtistsPage() })
        places.append(place(String(localized: "Albums"), symbol: "square.stack") { [weak self] in await self?.localAlbumsPage(music.index.albums, title: String(localized: "Albums")) })
        places.append(place(String(localized: "Songs"), symbol: "music.note") { [weak self] in await self?.localSongsPage(music.index.tracks, title: String(localized: "Songs")) })
        // Only with a server to download from: otherwise every song is already on the iPhone.
        if !music.downloads.items.isEmpty {
            places.append(place(String(localized: "Downloaded"), symbol: "arrow.down.circle") { [weak self] in
                await self?.localSongsPage(music.downloads.tracks.map(music.current), title: String(localized: "Downloaded"))
            })
        }
        return CPListSection(items: places)
    }

    func yourMusicLibrarySections() async -> [CPListSection] {
        let music = model.yourMusic
        guard let places = yourMusicPlaces() else {
            library.emptyViewTitleVariants = [String(localized: "No Music")]
            return []
        }
        var sections = [places]

        let recent = Array(music.index.recentlyAdded.prefix(shelfLength))
        if !recent.isEmpty {
            let (side, scale) = (CPListImageRowItemImageGridElement.maximumImageSize.height, scale)
            let late = CarPlayImages.Late()
            let standIn = CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
            let images = await CarPlayImages.batch(recent.count, standIn: { _ in standIn }, late: { index, image in
                late.set(index, image)
            }) { [weak self] index in
                await self?.localCover(recent[index], side: side) ?? standIn
            }
            let tiles = zip(recent, images).map { album, image in
                CPListImageRowItemImageGridElement(image: image, imageShape: .roundedRectangle, title: album.displayTitle, accessorySymbolName: nil)
            }
            late.attach(tiles)
            // Named by the section: a row's name carries a chevron, with no page to open here.
            let row = CPListImageRowItem(text: nil, imageGridElements: tiles, allowsMultipleLines: false)
            row.listImageRowHandler = { [weak self] _, index, completion in
                guard recent.indices.contains(index) else { return completion() }
                self?.open({ await self?.localAlbumPage(recent[index]) }, then: completion) ?? completion()
            }
            sections.append(CPListSection(items: [row], header: String(localized: "Recently Added"), sectionIndexTitle: nil))
        }
        return sections
    }

    private func localPlaylistsPage() async -> CPListTemplate {
        let music = model.yourMusic
        let facts = model.playFeed.facts
        var rows: [CPListItem] = []
        var firsts: [LocalTrack?] = []
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.note.list", side: side, scale: scale)
        let playlists = Array(music.playlists.recent.prefix(CPListTemplate.maximumItemCount))
        for playlist in playlists {
            let songs = music.songs(in: playlist, facts: facts)
            firsts.append(songs.first { $0.artwork != nil } ?? songs.first)
            let count = String(AttributedString(localized: "^[\(songs.count) song](inflect: true)").characters)
            let row = CPListItem(text: playlist.name, detailText: count, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
            mark(row) { [weak self] in self?.isPlaying(playlist.name) ?? false }
            row.handler = { [weak self] _, completion in
                self?.open({ await self?.localPlaylistPage(playlist) }, then: completion) ?? completion()
            }
            rows.append(row)
        }
        let scale = scale
        fillImages(rows) { index in
            let first = firsts[index]
            return await CarPlayImages.cover(url: music.artworkURL(first?.artwork)?.absoluteString, seed: first?.album ?? playlists[index].name, side: side, scale: scale)
        }
        return page(String(localized: "Playlists"), [CPListSection(items: rows)], empty: String(localized: "No Playlists"))
    }

    private func localPlaylistPage(_ playlist: TracksPlaylist) async -> CPListTemplate {
        let music = model.yourMusic
        let player = model.player
        let songs = music.songs(in: playlist, facts: model.playFeed.facts)
        let playable = songs.filter(music.isPlayable)
        let context = PlayContext(kind: .playlist, title: playlist.name)
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.note.list", side: side, scale: scale),
            title: playlist.name,
            subtitle: Self.songCount(songs.count),
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(playlist.name) ?? false },
                play: { await player.start(.local(playable), from: context) },
                shuffle: playable.isEmpty ? nil : { await player.start(.local(playable), from: context, shuffled: true) }
            )
        )
        let scale = scale
        let first = playable.first { $0.artwork != nil }
        fillThumbnail(header) {
            guard let first else { return nil }
            return await CarPlayImages.cover(url: music.artworkURL(first.artwork)?.absoluteString, seed: first.album ?? playlist.name, side: side, scale: scale)
        }
        let rows = playable.prefix(CPListTemplate.maximumItemCount).enumerated().map { index, track in
            let row = numberedRow(index + 1, title: track.title, detail: track.artist)
            mark(row) { [weak self] in self?.isPlayingTrack(track.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.local(playable, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            return row
        }
        return detailPage(header, [CPListSection(items: rows)], empty: String(localized: "No Songs"))
    }

    private func localAlbumsPage(_ albums: [LocalAlbum], title: String) async -> CPListTemplate {
        let listed = Array(albums.prefix(CPListTemplate.maximumItemCount))
        let rows = localAlbumRows(listed) { $0.artist }
        return page(title, [CPListSection(items: rows)], empty: String(localized: "No Albums"))
    }

    /// Your albums as rows, their covers filling in once the page is open.
    private func localAlbumRows(_ albums: [LocalAlbum], detail: (LocalAlbum) -> String?) -> [CPListItem] {
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "square.stack", side: side, scale: scale)
        var rows: [CPListItem] = []
        for album in albums {
            let row = CPListItem(text: album.displayTitle, detailText: detail(album), image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
            mark(row) { [weak self] in self?.isPlaying(album.title) ?? false }
            row.handler = { [weak self] _, completion in
                self?.open({ await self?.localAlbumPage(album) }, then: completion) ?? completion()
            }
            rows.append(row)
        }
        fillImages(rows) { [weak self] index in
            await self?.localCover(albums[index], side: side)
        }
        return rows
    }

    private func localArtistsPage() async -> CPListTemplate {
        let music = model.yourMusic
        let artists = Array(music.index.artists.prefix(CPListTemplate.maximumItemCount))
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale, round: true)
        let rows = artists.map { artist in
            let row = CPListItem(text: artist.name, detailText: nil, image: standIn, accessoryImage: nil, accessoryType: .disclosureIndicator)
            mark(row) { [weak self] in self?.isPlaying(artist.name) ?? false }
            row.handler = { [weak self] _, completion in
                self?.open({ await self?.localArtistPage(artist) }, then: completion) ?? completion()
            }
            return row
        }
        // Their server's picture, or one of their covers, round.
        let scale = scale
        fillImages(rows) { index in
            guard let picture = music.artistPicture(for: artists[index]) else { return nil }
            return await CarPlayImages.circle(await CarPlayImages.cover(picture, side: side, scale: scale))
        }
        return page(String(localized: "Artists"), byLetter(rows, names: artists.map(\.name)), empty: String(localized: "No Artists"))
    }

    /// Songs A to Z under a row that shuffles them all.
    private func localSongsPage(_ tracks: [LocalTrack], title: String) async -> CPListTemplate {
        let music = model.yourMusic
        let player = model.player
        let playable = await Self.sortedByTitle(tracks.filter(music.isPlayable))
        guard !playable.isEmpty else {
            return page(title, [], empty: String(localized: "No Songs to Play"))
        }
        let context = PlayContext.songs(title)
        let shuffle = shuffleAllRow {
            await player.start(.local(Array(playable.shuffled().prefix(500))), from: context)
        }
        let listed = Array(playable.prefix(max(1, CPListTemplate.maximumItemCount - 1)))
        let rows = localSongRows(listed, context: context)
        return page(title, [CPListSection(items: [shuffle] + rows)], empty: String(localized: "No Songs"))
    }

    /// Songs with their covers, which fill in once the page is open.
    /// A whole library's songs sort off the main actor.
    @concurrent
    private nonisolated static func sortedByTitle(_ tracks: [LocalTrack]) async -> [LocalTrack] {
        tracks.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    private func localSongRows(_ tracks: [LocalTrack], context: PlayContext) -> [CPListItem] {
        let player = model.player
        let music = model.yourMusic
        let side = CPListItem.maximumImageSize.height
        let standIn = CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale)
        var rows: [CPListItem] = []
        let listed = Array(tracks.prefix(CPListTemplate.maximumItemCount))
        for (index, track) in listed.enumerated() {
            let row = CPListItem(text: track.title, detailText: track.artist, image: standIn)
            mark(row) { [weak self] in self?.isPlayingTrack(track.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.local(tracks, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            rows.append(row)
        }
        let scale = scale
        fillImages(rows) { index in
            let track = listed[index]
            return await CarPlayImages.cover(url: music.artworkURL(track.artwork)?.absoluteString, seed: track.album ?? track.title, side: side, scale: scale)
        }
        return rows
    }

    /// An artist's page: their picture, Play and Shuffle at the top, then their albums.
    func localArtistPage(_ artist: LocalArtist) async -> CPListTemplate {
        let music = model.yourMusic
        let player = model.player
        let tracks = artist.tracks.filter(music.isPlayable)
        let context = PlayContext(kind: .artist, title: artist.name)
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.microphone", side: side, scale: scale),
            title: artist.name,
            subtitle: Self.count(albums: artist.albums.count, songs: tracks.count),
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(artist.name) ?? false },
                play: { await player.start(.local(tracks), from: context) },
                shuffle: tracks.isEmpty ? nil : { await player.start(.local(tracks), from: context, shuffled: true) }
            )
        )
        let scale = scale
        // Square: the car frames a header's picture in a rounded square of its own.
        fillThumbnail(header) {
            guard let picture = music.artistPicture(for: artist) else { return nil }
            return await CarPlayImages.cover(picture, side: side, scale: scale)
        }
        let rows = localAlbumRows(Array(artist.albums.prefix(CPListTemplate.maximumItemCount))) { $0.year.map(String.init) }
        return detailPage(header, [CPListSection(items: rows, header: String(localized: "Albums"), sectionIndexTitle: nil)], empty: String(localized: "No Albums"))
    }

    func localAlbumPage(_ album: LocalAlbum) async -> CPListTemplate {
        let music = model.yourMusic
        let player = model.player
        let tracks = album.tracks.filter(music.isPlayable)
        let context = PlayContext(kind: .album, title: album.title)
        let side = Self.headerThumbnailSide
        let header = detailsHeader(
            cover: CarPlayImages.placeholder(symbol: "music.note", side: side, scale: scale),
            title: album.displayTitle,
            subtitle: Self.line([album.artist, album.year.map(String.init)]),
            playback: HeaderPlayback(
                isOn: { [weak self] in self?.isPlaying(album.title) ?? false },
                play: { await player.start(.local(tracks), from: context) },
                shuffle: tracks.isEmpty ? nil : { await player.start(.local(tracks), from: context, shuffled: true) }
            )
        )
        fillThumbnail(header) { [weak self] in await self?.localCover(album, side: side) }
        let numbers = Self.trackNumbers(tracks.map(\.trackNumber))
        let rows = tracks.prefix(CPListTemplate.maximumItemCount).enumerated().map { index, track in
            let row = numberedRow(numbers[index], title: track.title, detail: track.artist == album.artist ? Self.length(track.duration) : track.artist)
            mark(row) { [weak self] in self?.isPlayingTrack(track.id) ?? false }
            row.handler = { [weak self] _, completion in
                self?.play({ await player.start(.local(tracks, startingAt: index), from: context) }, completion: completion) ?? completion()
            }
            return row
        }
        return detailPage(header, [CPListSection(items: rows)], empty: String(localized: "No Songs"))
    }

    func localCover(_ album: LocalAlbum, side: CGFloat) async -> UIImage {
        await CarPlayImages.cover(url: model.yourMusic.artworkURL(album.artwork)?.absoluteString, seed: album.title, side: side, scale: scale)
    }

    // MARK: - Building blocks

    /// A row that opens a place in the library: its symbol, its name, and a chevron.
    private func place(_ title: String, symbol: String, open make: @escaping () async -> CPListTemplate?) -> CPListItem {
        let image = CarPlayImages.symbol(symbol, side: CPListItem.maximumImageSize.height, scale: scale)
        let row = CPListItem(text: title, detailText: nil, image: image, accessoryImage: nil, accessoryType: .disclosureIndicator)
        row.handler = { [weak self] _, completion in
            self?.open(make, then: completion) ?? completion()
        }
        return row
    }

    /// Pictures for a long list, drawn once it's open, from the top, a few at a time, so the
    /// page opens at once and fills in as you look at it. Rows keep their stand-in where
    /// there's no picture.
    ///
    /// Stops once the page has gone (popped, another tab, or the car disconnected), or if it
    /// never shows up.
    func fillImages(_ rows: [CPListItem], _ load: @escaping @MainActor (Int) async -> UIImage?) {
        guard let first = rows.first else { return }
        let started = Date.now
        Task { [weak self] in
            var wasShown = false
            for start in stride(from: 0, to: rows.count, by: 6) {
                guard let self else { return }
                if isShown(first) {
                    wasShown = true
                } else if wasShown || Date.now.timeIntervalSince(started) > 15 {
                    return
                }
                // Six side by side: each waits on its picture, not on the others. One that's
                // slow arrives on its own, without holding up the rest.
                let count = min(6, rows.count - start)
                let found = await CarPlayImages.gather(count, wait: .seconds(4), late: { index, image in
                    rows[start + index].setImage(image)
                }) { index in
                    await load(start + index)
                }
                for (index, image) in found {
                    rows[start + index].setImage(image)
                }
            }
        }
    }

    /// Whether a row is on a page that's open in the car.
    private func isShown(_ row: CPListItem) -> Bool {
        interface?.templates.contains { template in
            (template as? CPListTemplate)?.sections.contains { section in
                section.items.contains { $0 === row }
            } ?? false
        } ?? false
    }

    /// Shuffle All, at the top of a list of songs.
    private func shuffleAllRow(_ action: @escaping () async -> Void) -> CPListItem {
        actionRow(String(localized: "Shuffle All"), symbol: "shuffle", action: action)
    }

    /// A list page, with what to say when it has nothing in it.
    private func page(_ title: String, _ sections: [CPListSection], empty: String) -> CPListTemplate {
        let page = CPListTemplate(title: title, sections: Self.capped(sections))
        page.emptyViewTitleVariants = [empty]
        return page
    }

    /// An album, playlist or artist: its header on top and its rows under it. The header says
    /// the name, so the bar above doesn't again.
    func detailPage(_ header: CPListTemplateDetailsHeader, _ sections: [CPListSection], empty: String) -> CPListTemplate {
        let page = CPListTemplate(title: nil, sections: Self.capped(sections.filter { !$0.items.isEmpty }), assistantCellConfiguration: nil)
        page.listHeader = header
        page.emptyViewTitleVariants = [empty]
        return page
    }

    /// A song with its number where a cover would be.
    func numberedRow(_ number: Int, title: String, detail: String?) -> CPListItem {
        CPListItem(text: title, detailText: detail, image: CarPlayImages.trackNumber(number, side: CPListItem.maximumImageSize.height, scale: scale))
    }

    /// Rows under their first letter, with the letter in the car's index, within the number of
    /// sections the car allows.
    private func byLetter(_ rows: [CPListItem], names: [String]) -> [CPListSection] {
        LetterSection.sections(for: names, maximumSections: Int(CPListTemplate.maximumSectionCount)).map { section in
            CPListSection(items: section.indices.map { rows[$0] }, header: section.letter, sectionIndexTitle: section.letter)
        }
    }

    /// A row that plays something, with its symbol where a cover would be.
    func actionRow(_ title: String, symbol: String, action: @escaping () async -> Void) -> CPListItem {
        let row = CPListItem(text: title, detailText: nil, image: CarPlayImages.symbol(symbol, side: CPListItem.maximumImageSize.height, scale: scale))
        row.handler = { [weak self] _, completion in
            self?.play(action, completion: completion) ?? completion()
        }
        return row
    }

    /// An album's own track numbers when they tell its songs apart, or their places in it.
    /// A compilation's discs, or tags filled in by hand, can repeat a number.
    static func trackNumbers(_ numbers: [Int?]) -> [Int] {
        let known = numbers.compactMap(\.self)
        guard known.count == numbers.count, Set(known).count == known.count else { return Array(1...max(1, numbers.count)) }
        return known
    }

    /// "Artist · 2024": what's known, in a line.
    static func line(_ parts: [String?]) -> String? {
        let known = parts.compactMap { $0?.isEmpty == false ? $0 : nil }
        return known.isEmpty ? nil : known.joined(separator: " · ")
    }

    static func songCount(_ songs: Int) -> String {
        String(AttributedString(localized: "^[\(songs) song](inflect: true)").characters)
    }

    /// "4 albums · 38 songs".
    static func count(albums: Int, songs: Int) -> String {
        [
            albums > 0 ? String(AttributedString(localized: "^[\(albums) album](inflect: true)").characters) : nil,
            String(AttributedString(localized: "^[\(songs) song](inflect: true)").characters),
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// A song's length, as "3:41".
    static func length(_ seconds: TimeInterval?) -> String? {
        guard let seconds, seconds > 0 else { return nil }
        return Duration.seconds(seconds).formatted(.time(pattern: .minuteSecond))
    }
}

