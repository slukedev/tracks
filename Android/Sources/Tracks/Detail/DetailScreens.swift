import SwiftUI
import TracksStats

/// An artist: rank and share, plays over time, their songs and albums.
struct ArtistScreen: View {
    let artistID: String
    @Environment(AppModel.self) var model
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @State var profile: ArtistProfile?
    @State var isLoaded = false

    var body: some View {
        DetailScroll(isLoaded: isLoaded, found: profile != nil) {
            if let profile {
                DetailHeader(
                    title: profile.artist.name,
                    subtitle: "#\(profile.allTimeRank) of all time · \(Format.percent(profile.share)) of your listening"
                ) {
                    Monogram(name: profile.artist.name, size: 128)
                }
                FactsCard(facts: [
                    ("Plays", Format.count(profile.artist.count)),
                    ("Listening", Format.listening(profile.artist.listeningSeconds)),
                    ("Songs", Format.count(profile.artist.songCount)),
                    ("First heard", Format.shortDate(profile.artist.firstHeard)),
                ])
                let queue = profile.songs.compactMap { QueueSong($0, serverID: servers.server?.id) }
                if !queue.isEmpty {
                    PlayButtons(
                        play: { player.play(queue, from: "\(profile.artist.name): your top songs") },
                        shuffle: { player.play(queue, from: "\(profile.artist.name): your top songs", shuffled: true) }
                    )
                }
                PlaysOverTimeCard(timeline: profile.timeline, unit: profile.timelineUnit)
                if !profile.songs.isEmpty {
                    SectionHeader(title: "Top songs")
                    RankedSongs(songs: Array(profile.songs.prefix(10)))
                }
                if !profile.albums.isEmpty {
                    SectionHeader(title: "Albums")
                    VStack(spacing: 0) {
                        ForEach(profile.albums.prefix(6)) { album in
                            NavigationLink(value: Route.album(album.id)) {
                                ListRow(headline: album.title, supporting: Format.plays(album.count)) {
                                    Artwork(url: album.artworkURL, size: 56, placeholder: .album)
                                } trailing: { Chevron() }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 8)
                    .materialBackground(.surfaceContainerLow, cornerRadius: Corner.medium)
                }
            }
        }
        .navigationTitle(profile?.artist.name ?? "")
        .task(id: "\(artistID)|\(model.revision)") {
            let (id, history) = (artistID, model.history)
            profile = await Task.detached { StatsCalculator.artistProfile(id: id, history: history) }.value
            isLoaded = true
        }
    }
}

/// A song: its play count and rank, when it played, and every time it did.
struct SongScreen: View {
    let songID: String
    @Environment(AppModel.self) var model
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @State var profile: SongProfile?
    @State var isLoaded = false

    var body: some View {
        DetailScroll(isLoaded: isLoaded, found: profile != nil) {
            if let profile {
                DetailHeader(
                    title: profile.song.title,
                    subtitle: [profile.song.artistName, profile.song.albumTitle].compactMap { $0 }.joined(separator: " · ")
                ) {
                    Artwork(url: profile.song.artworkURL, size: 200, corner: Corner.extraLarge)
                }
                if let song = QueueSong(profile.song, serverID: servers.server?.id) {
                    Button {
                        player.play([song], from: song.title)
                    } label: {
                        HStack(spacing: 8) {
                            SymbolImage(.play, size: 20, filled: true, role: .onPrimary)
                            Text("Play")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                NavigationLink(value: Route.artist(profile.artistID)) {
                    ListRow(headline: profile.song.artistName, supporting: "Artist") {
                        Monogram(name: profile.song.artistName, size: 40)
                    } trailing: { Chevron() }
                        .materialBackground(.surfaceContainerLow, cornerRadius: Corner.medium)
                }
                .buttonStyle(.plain)
                FactsCard(facts: [
                    ("Plays", Format.count(profile.song.count)),
                    ("Rank", "#\(profile.allTimeRank)"),
                    ("Listening", Format.listening(profile.song.listeningSeconds)),
                    ("First heard", Format.shortDate(profile.song.firstHeard)),
                ])
                PlaysOverTimeCard(timeline: profile.timeline, unit: profile.timelineUnit)
                SectionHeader(title: "Every play")
                VStack(spacing: 0) {
                    ForEach(Array(profile.plays.prefix(50).enumerated()), id: \.offset) { _, date in
                        ListRow(headline: Format.day(date), supporting: Format.time(date)) {
                            SymbolImage(.history, role: .onSurfaceVariant)
                        }
                    }
                }
                .padding(.vertical, 8)
                .materialBackground(.surfaceContainerLow, cornerRadius: Corner.medium)
            }
        }
        .navigationTitle(profile?.song.title ?? "")
        .task(id: "\(songID)|\(model.revision)") {
            let (id, history) = (songID, model.history)
            profile = await Task.detached { StatsCalculator.songProfile(id: id, history: history) }.value
            isLoaded = true
        }
    }
}

/// An album: its songs by plays, and when you played it.
struct AlbumScreen: View {
    let albumID: String
    @Environment(AppModel.self) var model
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @State var profile: AlbumProfile?
    @State var isLoaded = false

    var body: some View {
        DetailScroll(isLoaded: isLoaded, found: profile != nil) {
            if let profile {
                DetailHeader(
                    title: profile.album.title,
                    subtitle: "\(profile.album.artistName) · #\(profile.allTimeRank) of all time"
                ) {
                    Artwork(url: profile.album.artworkURL, size: 200, corner: Corner.extraLarge, placeholder: .album)
                }
                FactsCard(facts: [
                    ("Plays", Format.count(profile.album.count)),
                    ("Listening", Format.listening(profile.album.listeningSeconds)),
                    ("Songs", Format.count(profile.album.songCount)),
                    ("First heard", Format.shortDate(profile.firstHeard)),
                ])
                let queue = profile.songs.compactMap { QueueSong($0, serverID: servers.server?.id) }
                if !queue.isEmpty {
                    PlayButtons(
                        play: { player.play(queue, from: profile.album.title) },
                        shuffle: { player.play(queue, from: profile.album.title, shuffled: true) }
                    )
                }
                PlaysOverTimeCard(timeline: profile.timeline, unit: profile.timelineUnit)
                SectionHeader(title: "Songs")
                RankedSongs(songs: profile.songs)
            }
        }
        .navigationTitle(profile?.album.title ?? "")
        .task(id: "\(albumID)|\(model.revision)") {
            let (id, history) = (albumID, model.history)
            profile = await Task.detached { StatsCalculator.albumProfile(id: id, history: history) }.value
            isLoaded = true
        }
    }
}

// MARK: - Parts

/// A detail page's scrolling column, with loading and not-found states.
struct DetailScroll<Content: View>: View {
    let isLoaded: Bool
    let found: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.cards) {
                if !isLoaded {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 96)
                } else if !found {
                    EmptyState(symbol: .search, title: "Not in your history", message: "It may have been removed.")
                } else {
                    content()
                }
            }
            .padding(.horizontal, Space.screen)
            .padding(.bottom, 24)
        }
    }
}

/// Picture, name in `headlineMedium` and a line about it, centred.
struct DetailHeader<Picture: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let picture: () -> Picture

    var body: some View {
        VStack(spacing: 12) {
            picture()
            Text(title)
                .materialType(.headlineMedium)
                .materialForeground(.onSurface)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(subtitle)
                .materialType(.bodyMedium)
                .materialForeground(.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }
}

/// Up to four facts across one card, with dividers between them.
struct FactsCard: View {
    let facts: [(String, String)]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(facts.enumerated()), id: \.offset) { index, fact in
                if index > 0 {
                    Color.clear.frame(width: 1, height: 36).materialBackground(.outlineVariant)
                }
                VStack(spacing: 2) {
                    Text(fact.1)
                        .materialType(.titleMedium)
                        .materialForeground(.onSurface)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(fact.0)
                        .materialType(.labelMedium)
                        .materialForeground(.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity)
                .combinedAccessibility()
            }
        }
        .padding(.vertical, Space.screen)
        .padding(.horizontal, 8)
        .materialBackground(.surfaceContainerLow, cornerRadius: Corner.medium)
    }
}

/// Plays per week or month since it was first heard.
struct PlaysOverTimeCard: View {
    let timeline: [TimeBucket]
    let unit: Calendar.Component

    var body: some View {
        MaterialCard(role: .surfaceContainerLow) {
            HStack(spacing: 8) {
                SymbolImage(.charts, size: 20, role: .primary)
                Text("Plays over time")
                    .materialType(.titleSmall)
                    .materialForeground(.primary)
            }
            BarChart(bars: bars, height: 140)
                .padding(.top, 16)
        }
    }

    private var bars: [Bar] {
        let stride = max(1, timeline.count / 6)
        return timeline.enumerated().map { index, bucket in
            let label: String? = index % stride == 0
                ? (unit == .month
                    ? String(bucket.start.formatted(.dateTime.month(.narrow)))
                    : bucket.start.formatted(.dateTime.month(.abbreviated).day()))
                : nil
            return Bar(id: "\(bucket.start.timeIntervalSince1970)", value: Double(bucket.count), label: label)
        }
    }
}

/// Songs by plays, numbered, in one container.
struct RankedSongs: View {
    let songs: [SongTally]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                NavigationLink(value: Route.song(song.id)) {
                    ListRow(headline: song.title, supporting: Format.plays(song.count)) {
                        HStack(spacing: 12) {
                            Text("\(index + 1)")
                                .materialType(.titleMedium)
                                .materialForeground(.onSurfaceVariant)
                                .frame(width: 24)
                            Artwork(url: song.artworkURL, size: 48)
                        }
                    } trailing: { Chevron() }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 8)
        .materialBackground(.surfaceContainerLow, cornerRadius: Corner.medium)
    }
}
