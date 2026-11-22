import SwiftUI
import TracksCore

struct SongDetailView: View {
    let songID: String
    @Environment(AppModel.self) private var model
    @SeenRevision private var revision
    @Environment(PlaybackController.self) private var playback
    @Environment(\.playSongs) private var playSongs
    @Environment(\.openURL) private var openURL
    @Environment(\.openPlayRoute) private var openPlayRoute
    @State private var profile: SongProfile?
    /// The id whose profile was last worked out from a loaded library, so "No Plays" waits
    /// for the answer instead of showing while the first one is still on its way.
    @State private var loadedID: String?
    @State private var showsAllPlays = false

    var body: some View {
        ScrollView {
            if let profile {
                content(profile)
                    .padding(.bottom, Design.Margin.section)
            } else if loadedID == songID {
                ContentUnavailableView("No Plays", systemImage: "music.note")
            } else {
                DetailPlaceholder()
            }
        }
        .scrollEdgeEffectStyle(.soft, for: .top)
        .groupedBackground()
        .detailTitle(profile?.song.title ?? "")
        .task(id: "\(songID)|\(revision)") {
            // New listening on the page already shown: a play bumps the revision, and its
            // lookups again a moment later, so let the burst settle and work it out once.
            if profile?.song.id == songID {
                do { try await Task.sleep(for: LiveUpdate.settle) } catch { return }
            }
            let (id, history, isLoaded) = (songID, model.library.history, model.library.isLoaded)
            let next = await OffMainActor.run { StatsCalculator.songProfile(id: id, history: history) }
            guard !Task.isCancelled else { return }
            // A lookup that changed nothing on this page doesn't redraw it, and new listening
            // changes the figures in place rather than setting the page moving.
            if next != profile {
                profile = next
            }
            if isLoaded { loadedID = id }
        }
    }

    private func content(_ profile: SongProfile) -> some View {
        let song = profile.song
        return VStack(spacing: Design.Card.sectionSpacing) {
            DetailHeader(artworkURL: song.artworkURL, seed: song.albumTitle ?? song.title) {
                // Each artist links where they're named: a feature only the title names
                // is linked there, so no one is written twice.
                CreditedText(text: song.title, credits: titleOnlyCredits(song))
                    .font(.collectionTitle)
                if openPlayRoute.canPush {
                    CreditedText(text: song.artistName, credits: song.credits)
                        .font(.title3)
                        .foregroundStyle(.tint)
                } else {
                    NavigationLink(value: Route.artist(profile.artistID)) {
                        Text(song.artistName)
                            .font(.title3)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.tint)
                }
                if let album = song.albumTitle {
                    Text(album)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                DetailFacts(parts: [profile.metadata?.genre, profile.metadata?.releaseYear.map(Format.year)])
                    .padding(.top, Design.Spacing.xxSmall)
            } actions: {
                actions(for: song)
            }

            VStack(alignment: .leading, spacing: Design.Card.sectionSpacing) {
                StatStrip(items: [
                    .init(title: "Plays", value: song.count.formatted()),
                    .init(title: "Rank", value: "#\(profile.allTimeRank.formatted())"),
                    .init(title: "First Heard", value: Format.shortDate(song.firstHeard)),
                    .init(title: "Last Heard", value: Format.shortDate(song.lastHeard)),
                ])

                if profile.timeline.count(where: { $0.count > 0 }) > 1 {
                    PlaysOverTimeCard(timeline: profile.timeline, unit: profile.timelineUnit)
                }

                if HowYouHeardItCard.isWorthShowing(sources: profile.sources, stations: profile.stations) {
                    HowYouHeardItCard(sources: profile.sources, stations: profile.stations)
                }

                SummarySection("Plays") {
                    PlaysCard(plays: showsAllPlays ? profile.plays : Array(profile.plays.prefix(8)))
                    ShowAllButton(isShowingAll: $showsAllPlays, count: profile.plays.count, limit: 8)
                }
            }
            .padding(.horizontal, Design.Margin.page)
            .frame(maxWidth: 760)
        }
        .frame(maxWidth: .infinity)
    }

    /// The artists the title names that the artist line doesn't, like "Song (feat. B)" by A.
    private func titleOnlyCredits(_ song: SongTally) -> [ArtistCredit] {
        let named = Set(CreditedText.prepared(song.artistName, credits: song.credits).links.map(\.credit.identity))
        return song.credits.filter { !named.contains($0.identity) }
    }

    @ViewBuilder
    private func actions(for song: SongTally) -> some View {
        if !song.songID.isEmpty, !model.isShowingSampleData {
            GlassEffectContainer(spacing: Design.Control.spacing) {
                HStack(spacing: Design.Control.spacing) {
                    PlayButton {
                        let item = PlaybackItem(songID: song.songID, title: song.title, artistName: song.artistName)
                        if let playSongs {
                            playSongs([item], title: song.title)
                        } else {
                            Task { await playback.play(item) }
                        }
                    }
                    GlassCircleButton(title: "Show in Music", systemImage: "arrow.up.forward.app") {
                        if let url = URL(string: "https://music.apple.com/song/\(song.songID)") {
                            openURL(url)
                        }
                    }
                }
            }
        }
    }
}

/// Every time a song was played, newest first, in one panel.
struct PlaysCard: View {
    let plays: [Date]

    var body: some View {
        Card(padding: 0) {
            // Lazy: a favourite can have thousands of plays once they're all shown. One view per
            // play, divider included, so the stack tells rows apart by id alone.
            LazyVStack(spacing: 0) {
                ForEach(plays.enumerated(), id: \.offset) { index, date in
                    VStack(spacing: 0) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(Format.day(date))
                            Spacer(minLength: Design.Spacing.small)
                            Text(date, format: .dateTime.hour().minute())
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        .font(.subheadline)
                        .padding(.horizontal, Design.Card.padding)
                        .padding(.vertical, Design.Spacing.medium)
                        if index < plays.count - 1 {
                            Divider().padding(.leading, Design.Card.padding)
                        }
                    }
                }
            }
        }
    }
}

#if DEBUG
#Preview("Plays") {
    ScrollView {
        PlaysCard(plays: (0..<5).map { Date.now.addingTimeInterval(Double($0) * -86_400) })
            .padding(Design.Margin.page)
    }
}
#endif
