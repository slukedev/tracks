import SwiftUI
import TracksCore

/// Finds covers that no longer load and fetches them again.
///
/// Covers go missing for two reasons: a `musicKit://` address MusicKit hands out for radio,
/// which nothing can load, and an Apple address that has since been taken down. Neither heals
/// on its own, because the backfill only fills rows that have no cover at all.
struct ArtworkRepairSection: View {
    @Environment(AppModel.self) private var model
    @State private var isRepairing = false
    @State private var report: ArtworkRepairReport?

    var body: some View {
        // Sample data has no covers of its own to mend.
        if !model.isShowingSampleData, model.capture != nil {
            Section {
                #if os(macOS)
                if let result {
                    SettingsDetailRow(title: Text("Repair Artwork"), detail: result) { control }
                } else {
                    LabeledContent("Repair Artwork") { control }
                }
                #else
                Button(action: repair) {
                    // An `HStack` rather than `LabeledContent`, which would style the row as a
                    // read-out and lose the button's tint. The spinner sits where a disclosure
                    // would, so the title doesn't move when it appears.
                    HStack {
                        Text(isRepairing ? "Repairing Artwork…" : "Repair Artwork")
                        Spacer()
                        if isRepairing {
                            ProgressView()
                        }
                    }
                }
                .disabled(isRepairing)
                #endif
            } header: {
                Text("Artwork")
            } footer: {
                #if os(iOS)
                result
                #endif
            }
        }
    }

    #if os(macOS)
    @ViewBuilder
    private var control: some View {
        if isRepairing {
            // Where the button was, at a fixed width, so the row doesn't reflow.
            ProgressView()
                .controlSize(.small)
                .frame(width: 70)
        } else {
            Button("Repair", action: repair)
                .help("Find covers that no longer load and fetch them again")
        }
    }
    #endif

    /// What the last repair found, once one has run here.
    private var result: Text? {
        guard let report else { return nil }
        guard !report.foundNothingWrong else { return Text("Every cover loads.") }
        return Text("Replaced ^[\(report.restored) cover](inflect: true).")
    }

    private func repair() {
        // The capture service, not the environment: the Mac's Settings window is its own
        // scene and only ever gets the model.
        guard let capture = model.capture else { return }
        isRepairing = true
        report = nil
        Task {
            let result = await capture.repairArtwork()
            // Artists Apple Music had no picture for are back in the queue now.
            model.library.refreshArtistArtwork()
            report = result
            isRepairing = false
        }
    }
}
