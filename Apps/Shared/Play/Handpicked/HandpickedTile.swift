import SwiftUI
import TracksCore

/// Handpicked on Find Your Mood, first: its own field and symbol. A click opens the sheet to
/// pick songs for a station, as making a station from songs is a short task, not a place.
struct HandpickedTile: View {
    let height: CGFloat
    @State private var showsPicker = false

    var body: some View {
        Button {
            showsPicker = true
        } label: {
            ZStack(alignment: .bottomLeading) {
                HueField(color: Handpicked.color)
                Image(systemName: Handpicked.symbol)
                    .font(.system(size: height * 0.62, weight: .bold))
                    .foregroundStyle(.white.opacity(0.22))
                    .rotationEffect(.degrees(-12))
                    .offset(x: height * 0.16, y: height * 0.12)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .accessibilityHidden(true)
                Text(Handpicked.title)
                    .font(Self.signature)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 6)
            }
            .frame(width: height * 1.75, height: height)
            .clipShape(.rect(cornerRadius: 16, style: .continuous))
            .contentShape(.rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(Handpicked.title)
        .accessibilityHint("Pick songs to make a station from")
        .sheet(isPresented: $showsPicker) {
            HandpickedPicker()
        }
    }

    /// Signed rather than set: the one mood on the row you make yourself.
    #if os(macOS)
    private static let signature = Font.custom("SnellRoundhand-Bold", fixedSize: 30)
    #else
    private static let signature = Font.custom("SnellRoundhand-Bold", fixedSize: 31)
    #endif
}
