import SwiftUI
import TracksCore

/// Top songs, artists and albums, one list at a time: History's Charts.
struct ChartsScreen: View {
    @AppStorage(ChartKind.storageKey) private var kind: ChartKind = .songs

    var body: some View {
        TopChartView(kind: kind, kindSelection: $kind)
            .tabRoot("History")
    }
}
