import SwiftUI
import TracksCore

extension View {
    /// The top of a tab's first page, the same on every tab: the title on the bar's own row,
    /// leading, as Music's tab titles sit beside its account button, then the page's own
    /// actions, then Your Devices and Settings together in one capsule at the trailing end.
    ///
    /// - Parameters:
    ///   - opensDevicesAtLaunch: for the one page that answers `-TracksDevices YES`.
    ///   - opensSettingsAtLaunch: for the one page that answers `-TracksSettings`.
    ///   - actions: the page's own items, at `.primaryAction`. Give rare ones
    ///     `.visibilityPriority(.low)` so a crowded bar folds them into its overflow first.
    func tabRoot<Actions: ToolbarContent>(
        _ title: LocalizedStringKey,
        opensDevicesAtLaunch: Bool = false,
        opensSettingsAtLaunch: Bool = false,
        @ToolbarContentBuilder actions: () -> Actions
    ) -> some View {
        modifier(TabRoot(
            title: title,
            opensDevicesAtLaunch: opensDevicesAtLaunch,
            opensSettingsAtLaunch: opensSettingsAtLaunch,
            actions: actions()
        ))
    }

    func tabRoot(
        _ title: LocalizedStringKey,
        opensDevicesAtLaunch: Bool = false,
        opensSettingsAtLaunch: Bool = false
    ) -> some View {
        tabRoot(title, opensDevicesAtLaunch: opensDevicesAtLaunch, opensSettingsAtLaunch: opensSettingsAtLaunch) {
            ToolbarSpacer(.fixed, placement: .primaryAction)
        }
    }
}

private struct TabRoot<Actions: ToolbarContent>: ViewModifier {
    let title: LocalizedStringKey
    let opensDevicesAtLaunch: Bool
    let actions: Actions
    @State private var showsSettings: Bool
    @AppStorage(NearbyDevices.storageKey) private var showsDevices = true

    init(title: LocalizedStringKey, opensDevicesAtLaunch: Bool, opensSettingsAtLaunch: Bool, actions: Actions) {
        self.title = title
        self.opensDevicesAtLaunch = opensDevicesAtLaunch
        self.actions = actions
        _showsSettings = State(initialValue: opensSettingsAtLaunch)
    }

    func body(content: Content) -> some View {
        content
            .navigationTitle(title)
            // A large title on a row of its own costs every tab a band of empty glass.
            .toolbarTitleDisplayMode(.inlineLarge)
            // Soft, as Music's tab roots are: the automatic style draws a hard band under the
            // bar once the page scrolls.
            .scrollEdgeEffectStyle(.soft, for: .top)
            .toolbar {
                actions
                ToolbarSpacer(.fixed, placement: .primaryAction)
                // Next to each other with no spacer, so they share one capsule. The page's own
                // actions fold into the overflow before these do.
                ToolbarItem(placement: .primaryAction) {
                    DevicesButton(opensAtLaunch: opensDevicesAtLaunch)
                }
                .hidden(!showsDevices)
                .visibilityPriority(.high)
                ToolbarItem(placement: .primaryAction) {
                    Button("Settings", systemImage: "gearshape") { showsSettings = true }
                }
                .visibilityPriority(.high)
            }
            .sheet(isPresented: $showsSettings) {
                SettingsSheet()
            }
    }
}
