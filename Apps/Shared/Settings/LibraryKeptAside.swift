import SwiftUI
import TracksCore

extension LibraryKeptAside {
    /// Nil while the library is open, which is every run but a broken one.
    @MainActor
    init?(model: AppModel) {
        #if DEBUG
        // `-TracksLibraryKeptAside YES` (or `relaunch`) shows the state on sample data.
        if let demo = UserDefaults.standard.string(forKey: "TracksLibraryKeptAside") {
            self.init(opensOnRelaunch: demo == "relaunch")
            return
        }
        #endif
        guard model.store?.isLibraryUnavailable == true else { return nil }
        self.init(opensOnRelaunch: model.capture?.libraryOpensOnRelaunch == true)
    }

    /// The line and the action as one status line, the action in orange.
    var status: Text {
        guard let action else { return Text(line) }
        return Text("\(line) \(Text(action).foregroundStyle(.ink(.orange)))")
    }
}

/// The library's state as a row of its own, at the top of iPhone Settings, the Mac's General
/// pane and History, as Settings puts something that needs you above everything else.
struct LibraryKeptAsideRow: View {
    var keptAside: LibraryKeptAside

    #if os(iOS)
    @Environment(\.dynamicTypeSize) private var typeSize
    #endif

    var body: some View {
        Section {
            #if os(macOS)
            SettingsDetailRow(
                title: Text(LibraryKeptAside.title),
                detail: keptAside.status,
                systemImage: "tray.full.fill",
                tint: .orange
            )
            #else
            // The tile above the words at the accessibility sizes, where beside them it would
            // leave a column of a word or two.
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: SettingsSpacing.standard))
                : AnyLayout(HStackLayout(spacing: SettingsSpacing.row))
            layout {
                SettingsIconTile(systemImage: "tray.full.fill", color: .orange)
                VStack(alignment: .leading, spacing: SettingsSpacing.tight) {
                    Text(LibraryKeptAside.title)
                    keptAside.status
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, SettingsSpacing.tight)
            .accessibilityElement(children: .combine)
            #endif
        }
    }
}

#if DEBUG
#Preview("Kept Aside") {
    Form {
        LibraryKeptAsideRow(keptAside: LibraryKeptAside(opensOnRelaunch: false))
    }
    .formStyle(.grouped)
}

#Preview("Kept Aside, Reopen") {
    Form {
        LibraryKeptAsideRow(keptAside: LibraryKeptAside(opensOnRelaunch: true))
    }
    .formStyle(.grouped)
}

#Preview("Kept Aside, AX5") {
    Form {
        LibraryKeptAsideRow(keptAside: LibraryKeptAside(opensOnRelaunch: true))
    }
    .formStyle(.grouped)
    .dynamicTypeSize(.accessibility5)
}
#endif
