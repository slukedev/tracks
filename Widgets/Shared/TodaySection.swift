import SwiftUI

/// Today's listening, newest first.
struct TodaySection: View {
    let captures: [WidgetCapture]
    let rowStyle: WidgetSongRow.Style

    var body: some View {
        VStack(alignment: .leading, spacing: rowStyle.spacing) {
            // Not "Today on Radio": Tracks keeps every song now, not just radio.
            WidgetSectionHeader(title: "Today", systemImage: "music.note.list")
            WidgetSongList(songs: captures, style: rowStyle)
        }
    }
}
