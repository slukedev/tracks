import SwiftUI
import TracksCore

extension View {
    func pickerBar() -> some View {
        modifier(PickerBar())
    }
}

private struct PickerBar: ViewModifier {
    @Environment(HandpickedSelection.self) private var selection
    @Environment(HandpickedPreview.self) private var preview
    @Namespace private var glass
    @State private var showsPicked = false

    func body(content: Content) -> some View {
        #if os(macOS)
        VStack(spacing: 0) {
            content
            bar
        }
        #else
        content
            .safeAreaBar(edge: .bottom, spacing: 0) { bar }
            .navigationDestination(isPresented: $showsPicked) { PickedList() }
        #endif
    }

    private var bar: some View {
        let hasPicks = !selection.picks.isEmpty
        return GlassEffectContainer(spacing: Design.Control.spacing) {
            HStack(spacing: Design.Control.spacing) {
                #if os(macOS)
                Button("Cancel", role: .cancel) { selection.cancel() }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .keyboardShortcut(.cancelAction)
                #endif
                if hasPicks {
                    PickedTray { showsPicked = true }
                        .glassEffect(.regular, in: .capsule)
                        .glassEffectID("tray", in: glass)
                        #if os(macOS)
                        .popover(isPresented: $showsPicked, arrowEdge: .top) {
                            PickedList()
                                .environment(selection)
                                .environment(preview)
                        }
                        #endif
                }
                #if os(macOS)
                if !hasPicks {
                    Spacer(minLength: 0)
                }
                StartButton()
                    .glassEffectID("start", in: glass)
                #endif
            }
        }
        .padding(.horizontal, Self.margin)
        .padding(.top, Design.Spacing.small)
        .padding(.bottom, Self.bottom)
        .animation(Design.Motion.panel, value: hasPicks)
    }

    private static var margin: CGFloat {
        #if os(macOS)
        Design.Spacing.large
        #else
        Design.Margin.page
        #endif
    }

    private static var bottom: CGFloat {
        #if os(macOS)
        Design.Spacing.large
        #else
        Design.Spacing.xSmall
        #endif
    }
}

#if os(macOS)
private struct StartButton: View {
    @Environment(HandpickedSelection.self) private var selection

    var body: some View {
        Button {
            selection.start()
        } label: {
            Group {
                if selection.isStarting {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityLabel("Starting Station")
                } else {
                    Label("Start Station", systemImage: "play.fill")
                }
            }
            .frame(minWidth: 112)
        }
        .buttonStyle(.glassProminent)
        .controlSize(.large)
        .disabled(selection.picks.isEmpty || selection.isStarting)
        .keyboardShortcut(.return, modifiers: .command)
        .help("Start Station (\u{2318}\u{21A9})")
    }
}
#endif

struct PickedTray: View {
    let openList: () -> Void
    @Environment(HandpickedSelection.self) private var selection

    var body: some View {
        let picks = selection.picks
        HStack(spacing: 8) {
            Button(action: openList) {
                HStack(spacing: 4) {
                    Text("^[\(picks.count) Song](inflect: true)")
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .contentTransition(.numericText(value: Double(picks.count)))
                    Image(systemName: Self.chevron)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                }
                .fixedSize()
                .padding(.leading, 16)
                .frame(maxHeight: .infinity)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("^[\(picks.count) Song](inflect: true) Picked")
            .accessibilityHint("Shows them in order, to move or remove")
            #if os(macOS)
            .help("Show Picked Songs")
            #endif

            ScrollView(.horizontal) {
                HStack(spacing: 6) {
                    ForEach(picks) { pick in
                        TrayCover(pick: pick)
                            .transition(.scale(scale: 0.5).combined(with: .opacity))
                    }
                }
                .padding(.vertical, 6)
                .padding(.trailing, 6)
            }
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.leading, for: .alignment)
            .defaultScrollAnchor(.trailing, for: .sizeChanges)
            .clipShape(.capsule)
        }
        .frame(height: Self.height)
        .animation(PlayMotion.row, value: picks.map(\.identity))
    }

    private static var height: CGFloat {
        #if os(macOS)
        44
        #else
        50
        #endif
    }

    private static var chevron: String {
        #if os(macOS)
        "chevron.up"
        #else
        "chevron.right"
        #endif
    }
}

private struct TrayCover: View {
    let pick: Handpick
    @Environment(HandpickedSelection.self) private var selection
    #if os(macOS)
    @State private var isHovering = false
    #endif

    var body: some View {
        Button {
            withAnimation(PlayMotion.row) { selection.remove(pick.identity) }
        } label: {
            CoverImage(cover: pick.cover, size: Self.size)
                .overlay(alignment: .topTrailing) {
                    if showsRemove {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .black.opacity(0.6))
                            .offset(x: 3, y: -3)
                            .transition(.opacity)
                    }
                }
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        #if os(macOS)
        .onHover { hovering in
            withAnimation(PlayMotion.hover) { isHovering = hovering }
        }
        .help(Text("Remove \u{201C}\(pick.title)\u{201D}"))
        #endif
        .accessibilityLabel(Text("Remove \u{201C}\(pick.title)\u{201D}"))
    }

    private var showsRemove: Bool {
        #if os(macOS)
        isHovering
        #else
        true
        #endif
    }

    private static var size: CGFloat {
        #if os(macOS)
        32
        #else
        38
        #endif
    }
}

struct PickedList: View {
    @Environment(HandpickedSelection.self) private var selection
    @Environment(\.dismiss) private var dismiss
    #if os(macOS)
    @State private var focus: Set<String> = []
    #endif

    var body: some View {
        list
            .navigationTitle("Picked")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .environment(\.editMode, .constant(.active))
            #endif
            .onChange(of: selection.picks.isEmpty) { _, isEmpty in
                if isEmpty { dismiss() }
            }
    }

    @ViewBuilder
    private var list: some View {
        #if os(macOS)
        List(selection: $focus) { rows }
            .onDeleteCommand {
                withAnimation(PlayMotion.row) {
                    for identity in focus { selection.remove(identity) }
                }
                focus = []
            }
            .frame(width: 360, height: 440)
            .scrollContentBackground(.hidden)
        #else
        List { rows }
        #endif
    }

    @ViewBuilder
    private var rows: some View {
        Section {
            ForEach(selection.picks) { pick in
                PickedRow(pick: pick)
                    .tag(pick.identity)
            }
            .onMove { source, destination in
                withAnimation(PlayMotion.row) { selection.move(fromOffsets: source, toOffset: destination) }
            }
            .onDelete { offsets in
                let identities = offsets.map { selection.picks[$0].identity }
                withAnimation(PlayMotion.row) {
                    for identity in identities { selection.remove(identity) }
                }
            }
        } footer: {
            Text("The station opens with the first song.")
        }
        let suggestions = selection.suggestions.filter { !selection.contains($0.identity) }
        if !suggestions.isEmpty {
            Section(PickSection.suggestions.title) {
                ForEach(suggestions) { pick in
                    PickRow(pick: pick, isCompact: true)
                }
            }
        }
    }
}

private struct PickedRow: View {
    let pick: Handpick
    #if os(macOS)
    @State private var isHovering = false
    #endif

    var body: some View {
        HStack(spacing: 10) {
            #if os(macOS)
            PreviewCover(pick: pick, size: 34, showsControl: isHovering)
            #else
            PreviewCover(pick: pick, size: 44, showsControl: true)
            #endif
            VStack(alignment: .leading, spacing: 2) {
                Text(pick.title)
                    .lineLimit(1)
                Text(pick.artistName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        #if os(macOS)
        .onHover { hovering in
            withAnimation(PlayMotion.hover) { isHovering = hovering }
        }
        #endif
    }
}
