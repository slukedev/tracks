import SwiftUI
import TracksCore

struct ArtistDetailView: View {
    let artistID: String
    @Environment(AppModel.self) private var model
    @SeenRevision private var revision
    @State private var profile: ArtistProfile?
    /// The id whose profile was last worked out from a loaded library, so "No Plays" waits
    /// for the answer instead of showing while the first one is still on its way.
    @State private var loadedID: String?
    @State private var showsAllSongs = false
    @State private var showsAllFeatures = false
    /// Their music you don't own yet on your own server, where it can fetch.
    @State private var gets: GetMore.ArtistGets?

    var body: some View {
        ScrollView {
            if let profile {
                content(profile)
                    .padding(.bottom, Design.Margin.section)
            } else if loadedID == artistID {
                ContentUnavailableView("No Plays", systemImage: "music.microphone")
            } else {
                DetailPlaceholder(isCircle: true)
            }
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .groupedBackground()
        .detailTitle(profile?.artist.name ?? "")
        .task(id: "\(artistID)|\(revision)") {
            // New listening on the page already shown: a play bumps the revision, and its
            // lookups again a moment later, so let the burst settle and work it out once.
            if profile?.artist.id == artistID {
                do { try await Task.sleep(for: LiveUpdate.settle) } catch { return }
            }
            let (id, history, isLoaded) = (artistID, model.library.history, model.library.isLoaded)
            let next = await OffMainActor.run { StatsCalculator.artistProfile(id: id, history: history) }
            guard !Task.isCancelled else { return }
            // A lookup that changed nothing on this page doesn't redraw it, and new listening
            // changes the figures in place rather than setting the page moving.
            if next != profile {
                profile = next
            }
            if isLoaded { loadedID = id }
        }
    }

    private func content(_ profile: ArtistProfile) -> some View {
        VStack(spacing: Design.Card.sectionSpacing) {
            DetailHeader(artworkURL: profile.artist.artworkURL, seed: profile.artist.name, isCircle: true) {
                Text(profile.artist.name)
                    .font(.pageTitle)
                DetailFacts(parts: [profile.genre, profile.releaseYears.map(Format.years)])
                    .padding(.top, Design.Spacing.xxSmall)
            } actions: {
                EmptyView()
            }

            VStack(alignment: .leading, spacing: Design.Card.sectionSpacing) {
                StatStrip(items: [
                    .init(title: "Plays", value: profile.artist.count.formatted()),
                    .init(title: "Listening", value: Format.listening(profile.artist.listeningSeconds)),
                    .init(
                        title: "Rank",
                        value: "#\(profile.allTimeRank.formatted())",
                        detail: Text("\(profile.share.formatted(.percent.precision(.fractionLength(0)))) of your listening")
                    ),
                    .init(title: "First Heard", value: Format.shortDate(profile.artist.firstHeard)),
                ])

                if profile.timeline.count(where: { $0.count > 0 }) > 1 {
                    PlaysOverTimeCard(timeline: profile.timeline, unit: profile.timelineUnit)
                }

                if !profile.songs.isEmpty {
                    SummarySection("Top Songs") {
                        TopSongsCard(songs: profile.songs, limit: showsAllSongs ? profile.songs.count : 5)
                        ShowAllButton(isShowingAll: $showsAllSongs, count: profile.songs.count, limit: 5)
                    }
                }

                // Their own songs first, then the ones they guest on, as Music's "Appears On".
                if !profile.features.isEmpty {
                    SummarySection("Featured On") {
                        TopSongsCard(songs: profile.features, limit: showsAllFeatures ? profile.features.count : 5)
                        ShowAllButton(isShowingAll: $showsAllFeatures, count: profile.features.count, limit: 5)
                    }
                }

                if !profile.collaborators.isEmpty {
                    SummarySection("Collaborators") {
                        TopArtistsShelf(artists: profile.collaborators, artworkSize: 80, countsSongs: true)
                    }
                }

                if !profile.albums.isEmpty {
                    SummarySection("Albums") {
                        AlbumGrid(albums: profile.albums)
                    }
                }

                ArtistGetCard(artist: profile.artist.name, gets: gets)

                if profile.artist.count >= 10 {
                    Card { ListeningClockCard(hourly: profile.hourly) }
                }
            }
            .padding(.horizontal, Design.Margin.page)
            .frame(maxWidth: 760)
        }
        .frame(maxWidth: .infinity)
        .loadsGets(forArtist: profile.artist.name, into: $gets)
    }
}

/// Albums as a wrapping grid of covers.
struct AlbumGrid: View {
    let albums: [AlbumTally]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120, maximum: 180), spacing: Design.Shelf.spacing, alignment: .top)], spacing: Design.Spacing.large) {
            ForEach(albums) { album in
                NavigationLink(value: Route.album(album.id)) {
                    VStack(alignment: .leading, spacing: Design.Shelf.captionGap) {
                        // Sized by the column: the cover fills the square the grid offers.
                        ArtworkView(url: album.artworkURL, seed: album.title, size: nil)
                            .aspectRatio(1, contentMode: .fit)
                        Text(album.title)
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                        Text("^[\(album.count) play](inflect: true)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
            }
        }
    }
}
