import SwiftUI
import TracksCore

/// A click opens the sheet to pick songs for a station, as making a station from songs is a
/// short task, not a place.
struct HandpickedTile: View {
    @State private var showsPicker = false

    var body: some View {
        Button {
            showsPicker = true
        } label: {
            CategoryChip(title: Handpicked.title, symbol: Handpicked.symbol, color: Handpicked.color)
        }
        .buttonStyle(.categoryChip)
        .accessibilityLabel(Handpicked.title)
        .accessibilityHint("Pick songs to make a station from")
        .sheet(isPresented: $showsPicker) {
            HandpickedPicker()
        }
    }
}
