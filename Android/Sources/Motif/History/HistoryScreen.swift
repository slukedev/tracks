import SwiftUI
import MotifStats

/// Every song Motif kept, newest first, a section a day.
struct HistoryScreen: View {
    @Environment(AppModel.self) var model
    @State var days: [HistoryDay] = []
    /// Plays shown so far. History can run to tens of thousands, so it grows as you scroll.
    @State var limit = 400
    @State var pendingDelete: Play?

    var body: some View {
        List {
            ForEach(days) { day in
                Section {
                    ForEach(day.plays) { play in
                        NavigationLink(value: Route.song(play.songKey)) {
                            HistoryRow(play: play)
                        }
                    }
                    .onDelete { offsets in
                        pendingDelete = offsets.first.map { day.plays[$0] }
                    }
                } header: {
                    Text(day.title)
                        .materialType(.titleSmall)
                        .materialForeground(.primary)
                }
            }
            if model.log.plays.count > limit {
                Button("Show earlier plays") { limit += 400 }
                    .frame(maxWidth: .infinity)
            }
        }
        .listStyle(.plain)
        .navigationTitle("History")
        .overlay {
            if model.isEmpty {
                EmptyState(symbol: .history, title: "Nothing kept yet", message: "Songs you play appear here, newest first.")
            }
        }
        .task(id: "\(model.revision)|\(limit)") {
            days = HistoryDay.group(model.newestFirst.prefix(limit))
        }
        .confirmationDialog(
            "Remove this play?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let play = pendingDelete { model.delete(play) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("It won't count toward your statistics any more.")
        }
    }
}

/// One day's plays, newest first.
struct HistoryDay: Identifiable {
    let day: Date
    let title: String
    let plays: [Play]
    var id: String { "\(day.timeIntervalSince1970)" }

    static func group(_ plays: some Sequence<Play>, calendar: Calendar = .current) -> [HistoryDay] {
        var result: [HistoryDay] = []
        var current: (day: Date, plays: [Play])?
        for play in plays {
            let day = calendar.startOfDay(for: play.playedAt)
            if current?.day == day {
                current?.plays.append(play)
            } else {
                if let current { result.append(HistoryDay(day: current.day, title: Format.day(current.day), plays: current.plays)) }
                current = (day, [play])
            }
        }
        if let current { result.append(HistoryDay(day: current.day, title: Format.day(current.day), plays: current.plays)) }
        return result
    }
}

/// A play: cover, song, artist and the app it played in, and the time.
struct HistoryRow: View {
    let play: Play

    var body: some View {
        ListRow(headline: play.title, supporting: supporting) {
            Artwork(url: play.artworkURL, size: 56)
        } trailing: {
            Text(Format.time(play.playedAt))
                .materialType(.labelMedium)
                .materialForeground(.onSurfaceVariant)
        }
    }

    private var supporting: String {
        [play.artistName, play.kind == .lastFM ? "Last.fm" : play.appName]
            .compactMap { $0 }
            .joined(separator: " · ")
    }
}
