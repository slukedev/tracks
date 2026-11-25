import SwiftUI
import TracksCore

/// The History tab: every play, or the charts made from them, one switch apart.
struct HistoryTab: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            switch model.historyMode {
            case .plays: HistoryScreen()
            case .charts: ChartsScreen()
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("View", selection: $model.historyMode) {
                    ForEach(HistoryMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
        }
    }
}
