import SwiftUI
import TracksCore

/// One album's listening: how often you played it, which of its songs, and when.
///
/// Laid out like the artist and song pages, so the three read as one family.
struct AlbumDetailView: View {
    let albumID: String
    @Environment(AppModel.self) private var model
    @SeenRevision private var revision
    @Environment(PlaybackController.self) private var playback
    @Environment(\.playSongs) private var playSongs
    @State private var profile: AlbumProfile?
    /// The id whose profile was last worked out from a loaded library, so "No Plays" waits
    /// for the answer instead of showing while the first one is still on its way.
    @State private var loadedID: String?
    @State private var showsAllSongs = false

    var body: some View {
        ScrollView {
            if let profile {
                content(profile)
                    .padding(.bottom, Design.Margin.section)
            } else if loadedID == albumID {
                ContentUnavailableView("No Plays", systemImage: "square.stack")
            } else {
                DetailPlaceholder()
            }
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .groupedBackground()
        .detailTitle(profile?.album.title ?? "")
        .task(id: "\(albumID)|\(revision)") {
            // New listening on the page already shown: a play bumps the revision, and its
            // lookups again a moment later, so let the burst settle and work it out once.
            if profile?.album.id == albumID {
                do { try await Task.sleep(for: LiveUpdate.settle) } catch { return }
            }
            let (id, history, isLoaded) = (albumID, model.library.history, model.library.isLoaded)
            let next = await OffMainActor.run { StatsCalculator.albumProfile(id: id, history: history) }
            guard !Task.isCancelled else { return }
            // A lookup that changed nothing on this page doesn't redraw it, and new listening
            // changes the figures in place rather than setting the page moving.
            if next != profile {
                profile = next
            }
            if isLoaded { loadedID = id }
        }
    }

    private func content(_ profile: AlbumProfile) -> some View {
        let album = profile.album
        return VStack(spacing: Design.Card.sectionSpacing) {
            DetailHeader(artworkURL: album.artworkURL, seed: album.title) {
                Text(album.title)
                    .font(.collectionTitle)
                NavigationLink(value: Route.artist(profile.artistID)) {
                    Text(album.artistName)
                        .font(.title3)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
                DetailFacts(parts: [profile.genre, profile.releaseYear.map(Format.year)])
                    .padding(.top, Design.Spacing.xxSmall)
            } actions: {
                playButton(for: profile)
            }

            VStack(alignment: .leading, spacing: Design.Card.sectionSpacing) {
                StatStrip(items: [
                    .init(title: "Plays", value: album.count.formatted()),
                    .init(title: "Listening", value: Format.listening(album.listeningSeconds)),
                    .init(
                        title: "Rank",
                        value: "#\(profile.allTimeRank.formatted())",
                        detail: Text("\(profile.share.formatted(.percent.precision(.fractionLength(0)))) of your listening")
                    ),
                    .init(title: "First Heard", value: Format.shortDate(profile.firstHeard)),
                ])

                if profile.timeline.count(where: { $0.count > 0 }) > 1 {
                    PlaysOverTimeCard(timeline: profile.timeline, unit: profile.timelineUnit)
                }

                SummarySection("Songs") {
                    TopSongsCard(songs: profile.songs, limit: showsAllSongs ? profile.songs.count : 5)
                    ShowAllButton(isShowingAll: $showsAllSongs, count: profile.songs.count, limit: 5)
                }
            }
            .padding(.horizontal, Design.Margin.page)
            .frame(maxWidth: 760)
        }
        .frame(maxWidth: .infinity)
    }

    /// Plays the album's songs in the order you play them most. Hidden when none of them
    /// were ever identified in the catalog, since there'd be nothing to hand the player.
    @ViewBuilder
    private func playButton(for profile: AlbumProfile) -> some View {
        let playable = profile.songs.filter { !$0.songID.isEmpty }
        if !playable.isEmpty, !model.isShowingSampleData {
            PlayButton {
                let items = playable.map {
                    PlaybackItem(songID: $0.songID, title: $0.title, artistName: $0.artistName)
                }
                if let playSongs {
                    playSongs(items, title: profile.album.title)
                } else {
                    Task { await playback.play(items) }
                }
            }
        }
    }
}
