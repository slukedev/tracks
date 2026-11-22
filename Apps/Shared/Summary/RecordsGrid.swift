import SwiftUI
import TracksCore

/// Bests inside the range, in one panel like the figures under the listening. A record that
/// wasn't set (one artist all month, nothing played twice in a day) leaves no gap.
struct RecordsGrid: View {
    let records: ListeningRecords
    var columns = 3

    var body: some View {
        Card {
            FigureGrid(figures: figures, columns: columns)
        }
    }

    private var figures: [GlanceFigure] {
        var figures: [GlanceFigure] = []
        if let day = records.biggestDay {
            figures.append(GlanceFigure(
                title: "Biggest Day",
                value: Text(Format.listening(day.seconds)),
                detail: Text("\(Format.compactDay(day.day)) · ^[\(day.songCount) song](inflect: true)")
            ))
        }
        if let session = records.longestSession {
            figures.append(GlanceFigure(
                title: "Longest Session",
                value: Text(Format.listening(session.seconds)),
                detail: Text("\(Format.compactDay(session.start)) · ^[\(session.songCount) song](inflect: true)")
            ))
        }
        if let repeated = records.mostRepeated {
            figures.append(GlanceFigure(
                title: "On Repeat",
                value: Text("^[\(repeated.count) play](inflect: true)"),
                number: repeated.count,
                detail: Text("\(repeated.title), \(Format.compactDay(repeated.day))")
            ))
        }
        if let day = records.mostArtistsDay {
            figures.append(GlanceFigure(
                title: "Most Artists",
                value: Text("^[\(day.artistCount) artist](inflect: true)"),
                number: day.artistCount,
                detail: Text(Format.compactDay(day.day))
            ))
        }
        if let latest = records.latestListen {
            figures.append(GlanceFigure(
                title: "Latest Song",
                value: Text(latest, format: .dateTime.hour().minute()),
                detail: Text(Format.compactDay(latest))
            ))
        }
        if let earliest = records.earliestListen {
            figures.append(GlanceFigure(
                title: "Earliest Song",
                value: Text(earliest, format: .dateTime.hour().minute()),
                detail: Text(Format.compactDay(earliest))
            ))
        }
        return figures
    }
}

#if DEBUG
#Preview("Records") {
    ScrollView {
        RecordsGrid(records: StatsSummary.preview().records, columns: 2)
            .padding(Design.Margin.page)
    }
}
#endif
