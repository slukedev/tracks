import SwiftUI
import TracksCore

struct HandpickedTile: View {
    var covers: [CoverArt] = []
    @State private var showsPicker = false

    var body: some View {
        Button {
            showsPicker = true
        } label: {
            CategoryCard(title: Handpicked.title, symbol: Handpicked.symbol, color: Handpicked.color, covers: covers)
        }
        .buttonStyle(.categoryCard)
        .accessibilityLabel(Handpicked.title)
        .accessibilityHint("Pick songs to make a station from")
        .sheet(isPresented: $showsPicker) {
            HandpickedPicker()
        }
    }
}
