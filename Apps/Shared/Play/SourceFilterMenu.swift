import SwiftUI
import TracksCore

extension View {
    /// The source filter under Play's and Library's title (the Mac's toolbar): All Sources,
    /// each source, Manage Sources…. Only once there are two sources to choose between, and
    /// never touches what's queued. Plus, offline, the one word at the foot of the page that
    /// offers what still plays.
    func sourceFilter() -> some View {
        modifier(SourceFilterModifier())
    }
}

private struct SourceFilterModifier: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Counts choices made here, for a tap of feedback on each; never the filter itself,
    /// which a Focus or Shortcuts can change too.
    @State private var choices = 0
    @State private var managesSources = false
    #if os(macOS)
    @AppStorage(MacSettingsPane.storageKey) private var settingsPane = MacSettingsPane.general
    @Environment(\.openSettings) private var openSettings
    #endif

    func body(content: Content) -> some View {
        let entries = model.sourceEntries.filter(\.canPlay)
        let selection = model.effectiveSourceSelection
        Group {
            if entries.count > 1 {
                filtered(content, entries: entries, selection: selection)
            } else {
                content
            }
        }
        // A bar, so the page's scroll edge softens under it as under the tab bar.
        .safeAreaBar(edge: .bottom) {
            if isOfflineWithDownloads {
                OfflineCapsule(play: playDownloads)
                    .padding(.bottom, Design.Spacing.small)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(Design.Motion.reduced(Design.Motion.panel, reduceMotion: reduceMotion), value: isOfflineWithDownloads)
        .sensoryFeedback(Design.Haptic.choice, trigger: choices)
        #if os(iOS)
        .sheet(isPresented: $managesSources) {
            NavigationStack {
                SourcesPage()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done", role: .confirm) { managesSources = false }
                        }
                    }
            }
        }
        #endif
    }

    @ViewBuilder
    private func filtered(_ content: Content, entries: [SourceEntry], selection: SourceSelection) -> some View {
        let name = Self.name(of: selection, in: entries)
        content
            #if os(macOS)
            .toolbar {
                ToolbarItem(placement: .navigation) {
                    Menu { menu(entries) } label: { MacSourceLabel(name: name) }
                        .menuStyle(.button)
                        .buttonStyle(.plain)
                        .menuIndicator(.hidden)
                        .fixedSize()
                        // Where a window's title starts.
                        .padding(.leading, 16)
                        .accessibilityLabel("Source")
                        .accessibilityValue(name)
                }
                .sharedBackgroundVisibility(.hidden)
            }
            #else
            .navigationSubtitle(name)
            .toolbarTitleMenu { menu(entries) }
            // At the top of the page the title is large, and the system gives a large title no
            // menu: the filter under it opens the same one.
            .toolbar {
                ToolbarItem(placement: .largeSubtitle) {
                    Menu { menu(entries) } label: { LargeSourceLabel(name: name) }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityLabel("Source")
                        .accessibilityValue(name)
                }
            }
            #endif
    }

    @ViewBuilder
    private func menu(_ entries: [SourceEntry]) -> some View {
        let servers = entries.filter(\.id.isServer)
        let collapsesServers = entries.count > 6 && servers.count > 1
        Picker("Source", selection: binding) {
            Text("All Sources").tag(SourceSelection.all)
            Section {
                ForEach(collapsesServers ? entries.filter { !servers.contains($0) } : entries) { entry in
                    option(entry)
                }
            }
        }
        .pickerStyle(.inline)
        if collapsesServers {
            Menu("Servers") {
                Picker("Servers", selection: binding) {
                    ForEach(servers) { option($0) }
                }
                .pickerStyle(.inline)
            }
        }
        Divider()
        Button("Manage Sources…", action: manageSources)
    }

    private func option(_ entry: SourceEntry) -> some View {
        Label {
            Text(verbatim: entry.name)
        } icon: {
            SourceGlyph(source: entry.id)
        }
        .tag(SourceSelection.only(entry.id))
    }

    private var binding: Binding<SourceSelection> {
        Binding(get: { model.effectiveSourceSelection }) { selection in
            guard selection != model.effectiveSourceSelection else { return }
            choices += 1
            withAnimation(Design.Motion.reduced(Design.Motion.fade, reduceMotion: reduceMotion)) {
                model.chooseSources(selection)
            }
        }
    }

    private func manageSources() {
        #if os(macOS)
        settingsPane = .sources
        MenuBarFooter.bringSettingsForward(openSettings: openSettings)
        #else
        managesSources = true
        #endif
    }

    static func name(of selection: SourceSelection, in entries: [SourceEntry]) -> String {
        switch selection {
        case .all: String(localized: "All Sources")
        case .only(let source): entries.first { $0.id == source }?.name ?? String(localized: "All Sources")
        }
    }

    /// Offline, with something still to play here.
    private var isOfflineWithDownloads: Bool {
        !music.network.isOnline && !music.isDemo && !(music.fileTracks.isEmpty && music.tracks(for: .downloaded).isEmpty)
    }

    /// Everything that plays with no connection, shuffled.
    private func playDownloads() {
        let tracks = music.tracks(for: .downloaded).shuffled()
        guard !tracks.isEmpty else { return }
        choices += 1
        model.switchSource(to: .yourMusic)
        Task { await player.start(.local(tracks), from: .songs(String(localized: "Downloads"))) }
    }
}

#if os(iOS)
/// The filter under the large title, as its subtitle is drawn, with the chevron that says it's
/// a menu. Tall enough to tap, though the words are small.
private struct LargeSourceLabel: View {
    let name: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(verbatim: name)
                .lineLimit(1)
                .truncationMode(.tail)
            Image(systemName: "chevron.down")
                .font(.caption2.weight(.bold))
                .imageScale(.small)
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .frame(minHeight: Design.Control.minimumTarget)
        .contentShape(.rect)
        .contentTransition(.opacity)
    }
}
#endif

/// The one word at the foot of Play while offline: what still plays.
private struct OfflineCapsule: View {
    let play: () -> Void

    var body: some View {
        Button(action: play) {
            HStack(spacing: 10) {
                Image(systemName: "arrow.down.circle")
                    .foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 1) {
                    Text("You're offline")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("Play Downloads")
                        .font(.subheadline.weight(.semibold))
                }
            }
            .padding(.horizontal, Design.Spacing.large)
            .padding(.vertical, Design.Spacing.small)
            .frame(minHeight: Design.Control.minimumTarget)
            .glassEffect(.regular.interactive(), in: .capsule)
        }
        .buttonStyle(.plain)
    }
}

#if os(macOS)
/// The filter in the Mac's title bar, where a window's title sits, with a chevron saying it
/// opens a menu.
private struct MacSourceLabel: View {
    let name: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 5) {
            Text(verbatim: name)
                .font(.title3.weight(.semibold))
                .foregroundStyle(colorScheme == .dark ? Color.white : Color.black)
                .lineLimit(1)
            Image(systemName: "chevron.down")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(colorScheme == .dark ? Color.white.opacity(0.7) : Color.black.opacity(0.6))
        }
        // Opaque, not the bar's vibrant label colours, which let the page's glow tint the words.
        .contentShape(.rect)
    }
}
#endif
