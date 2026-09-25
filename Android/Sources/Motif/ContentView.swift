import SwiftUI
import MotifStats

struct ContentView: View {
    @State var summary: StatsSummary?

    var body: some View {
        NavigationStack {
            List {
                if let summary {
                    Text("\(summary.captureCount) plays this month")
                    ForEach(summary.topArtists) { artist in
                        Text(artist.name)
                    }
                }
            }
            .navigationTitle("Summary")
        }
        .task {
            let log = PlayLog(plays: Play.samples())
            summary = StatsCalculator.summary(range: .month, history: log.history(), sessions: [])
        }
    }
}
