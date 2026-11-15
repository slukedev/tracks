import SwiftUI
import TracksCore

extension View {
    /// The picks so far along the bottom, once there are any, opening them in order.
    func pickedTray() -> some View {
        modifier(PickedTrayInset())
    }
}

private struct PickedTrayInset: ViewModifier {
    @Environment(HandpickedSelection.self) private var selection
    @Environment(HandpickedPreview.self) private var preview
    @State private var showsPicked = false

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !selection.picks.isEmpty {
                    PickedTray { showsPicked = true }
                        #if os(macOS)
                        .popover(isPresented: $showsPicked, arrowEdge: .top) {
                            PickedList()
                                .environment(selection)
                                .environment(preview)
                        }
                        #endif
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(PlayMotion.panel, value: selection.picks.isEmpty)
            #if os(iOS)
            .navigationDestination(isPresented: $showsPicked) { PickedList() }
            #endif
    }
}

/// The songs picked so far, along the bottom of the sheet as Photos shows what's chosen: each
/// cover takes its song back out, and the count opens them in order, to move and remove.
struct PickedTray: View {
    let openList: () -> Void
    @Environment(HandpickedSelection.self) private var selection

    var body: some View {
        let picks = selection.picks
        HStack(spacing: 12) {
            Button(action: openList) {
                HStack(spacing: 4) {
                    Text("^[\(picks.count) Song](inflect: true)")
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .contentTransition(.numericText(value: Double(picks.count)))
                    Image(systemName: Self.chevron)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                }
                .fixedSize()
                .padding(.leading, 6)
                .frame(minHeight: 44)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("^[\(picks.count) Song](inflect: true) Picked")
            .accessibilityHint("Shows them in order, to move or remove")
            #if os(macOS)
            .help("Show Picked Songs")
            #endif

            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(picks) { pick in
                        TrayCover(pick: pick)
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                .padding(.vertical, 6)
                .padding(.trailing, 6)
            }
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.trailing)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .clipShape(.rect(cornerRadius: 26, style: .continuous))
        .glassEffect(.regular, in: .rect(cornerRadius: 26, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .animation(PlayMotion.row, value: picks.map(\.identity))
    }

    private static var chevron: String {
        #if os(macOS)
        "chevron.up"
        #else
        "chevron.right"
        #endif
    }
}

/// A picked song's cover in the tray, with ⓧ to take it back out.
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
                            .font(.system(size: 16))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .black.opacity(0.6))
                            .offset(x: 5, y: -5)
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
        34
        #else
        40
        #endif
    }
}

/// The picks in the order they'll play, to drag into another order and to remove: the first
/// opens the station.
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
            .frame(width: 340, height: min(CGFloat(selection.picks.count) * 46 + 64, 420))
        #else
        List { rows }
        #endif
    }

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
