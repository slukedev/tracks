#if os(iOS)
import SwiftUI

/// Your Devices on iPhone: a sheet that opens halfway, as AirPlay's does, and pulls up for
/// the rest and for its settings.
struct DevicesSheet: View {
    let close: () -> Void

    var body: some View {
        NavigationStack {
            DevicesPanel(close: close)
                .navigationTitle("Your Devices")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close", systemImage: "xmark", role: .close, action: close)
                    }
                }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
#endif

#if os(iOS) && DEBUG
#Preview("Your Devices") {
    PlayPreviewHost { Color.clear.sheet(isPresented: .constant(true)) { DevicesSheet {} } }
}
#endif
