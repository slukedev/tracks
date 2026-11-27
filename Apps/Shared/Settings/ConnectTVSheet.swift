import SwiftUI
import TracksCore

/// Connecting a TV to one of your Trackside servers: the code it shows, typed here or opened
/// from its QR code, what it is, then Approve or Deny. The TV collects its key on its own.
struct ConnectTVSheet: View {
    /// A TV's QR code, opened in Tracks: its server and code, looked up at once.
    var link: TracksideLinkRequest?
    @Environment(YourMusic.self) private var music
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var code = ""
    @State private var serverID: String?
    @State private var phase = Phase.entering
    @State private var problem: String?
    @State private var connections = 0
    @State private var work: Task<Void, Never>?
    @FocusState private var isCodeFocused: Bool

    enum Phase: Equatable {
        case entering
        case lookingUp
        case confirming(TracksideLinkInfo)
        /// Approve or Deny was pressed, and the server hasn't answered yet.
        case deciding(TracksideLinkInfo, approving: Bool)
        case connected(name: String)
        case denied(name: String)
        /// The link is for a server this device isn't signed in to.
        case unknownServer
    }

    private var servers: [SubsonicServer] { music.servers.tracksideServers }

    private var isBusy: Bool {
        switch phase {
        case .lookingUp, .deciding: true
        default: false
        }
    }

    private var isFinished: Bool {
        switch phase {
        case .connected, .denied, .unknownServer: true
        default: false
        }
    }

    var body: some View {
        sheet
            .onAppear(perform: start)
            .onDisappear { work?.cancel() }
            .onChange(of: code) { _, typed in self.typed(typed) }
            .onChange(of: music.servers.linkedDevices) { _, linked in noteLinked(linked) }
            .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: phase)
            .sensoryFeedback(.success, trigger: connections)
    }

    @ViewBuilder
    private var sheet: some View {
        #if os(macOS)
        VStack(alignment: .leading, spacing: 0) {
            Text("Connect a TV")
                .font(.title2.bold())
                .padding([.horizontal, .top], 20)
            content
                .settingsPane()
            HStack(spacing: SettingsSpacing.standard) {
                if phase == .lookingUp {
                    ProgressView()
                        .controlSize(.small)
                    Text("Looking Up…")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                macButtons
            }
            .padding([.horizontal, .bottom], 20)
        }
        #else
        NavigationStack {
            content
                .navigationTitle("Connect a TV")
                .toolbarTitleDisplayMode(.inline)
                .toolbar { toolbar }
        }
        .interactiveDismissDisabled(isBusy)
        #endif
    }

    // MARK: - Content

    private var content: some View {
        Form {
            switch phase {
            case .entering, .lookingUp:
                codeSection
            case .confirming(let info), .deciding(let info, _):
                deviceSection(info)
                if !info.nearby {
                    awaySection
                }
                #if os(iOS)
                decisionSection(info)
                #endif
            case .connected(let name):
                outcome(systemImage: "checkmark.circle.fill", tint: .green, title: TracksideLinkWords.connected(name))
            case .denied(let name):
                outcome(systemImage: "xmark.circle.fill", tint: .secondary, title: deniedTitle(name))
            case .unknownServer:
                outcome(systemImage: "questionmark.circle.fill", tint: .secondary, title: String(localized: "Not Signed In"), detail: unknownServerDetail)
            }
        }
        .formStyle(.grouped)
        .disabled(isBusy)
    }

    private var codeSection: some View {
        Section {
            if link == nil, servers.count > 1 {
                Picker("Server", selection: $serverID) {
                    ForEach(servers) { server in
                        Text(server.name).tag(Optional(server.id))
                    }
                }
            }
            TextField("Code", text: $code, prompt: Text(verbatim: "XXXX-XXXX"))
                .font(.title3.monospaced())
                .focused($isCodeFocused)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.characters)
                .keyboardType(.asciiCapable)
                .submitLabel(.continue)
                #endif
                .onSubmit(lookUp)
                .accessibilityLabel(Text("Code"))
        } footer: {
            if let problem {
                SettingsTone.failure.apply(to: Text(problem))
            } else {
                Text("Enter the code your TV shows.")
            }
        }
    }

    private func deviceSection(_ info: TracksideLinkInfo) -> some View {
        Section {
            SettingsDetailRow(
                title: Text(info.device.name.isEmpty ? String(localized: "TV") : info.device.name),
                detail: Text(TracksideLinkWords.kind(of: info.device)),
                systemImage: "tv",
                tint: .blue
            ) {
                #if os(macOS)
                if case .deciding = phase {
                    ProgressView()
                        .controlSize(.small)
                }
                #endif
            }
        } header: {
            if let name = serverID.flatMap({ music.servers.server($0)?.name }), servers.count > 1 {
                Text("Connect to \(name)")
            }
        } footer: {
            if let problem {
                SettingsTone.failure.apply(to: Text(problem))
            }
        }
    }

    private var awaySection: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
                    Text("Not on Your Home Network")
                    Text("Approve only if you are setting it up yourself.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.ink(.orange))
            }
            .accessibilityElement(children: .combine)
        }
    }

    #if os(iOS)
    /// Within reach of a thumb, Approve first, as a confirmation sheet puts it.
    private func decisionSection(_ info: TracksideLinkInfo) -> some View {
        Section {
            VStack(spacing: SettingsSpacing.standard) {
                Button { decide(info, approve: true) } label: {
                    busyLabel("Approve", isBusy: phase == .deciding(info, approving: true))
                }
                .buttonStyle(.borderedProminent)
                Button { decide(info, approve: false) } label: {
                    busyLabel("Deny", isBusy: phase == .deciding(info, approving: false))
                }
                .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
    }

    /// The label keeps its width while the spinner stands in, so the button doesn't jump.
    private func busyLabel(_ title: LocalizedStringKey, isBusy: Bool) -> some View {
        Text(title)
            .opacity(isBusy ? 0 : 1)
            .overlay {
                if isBusy { ProgressView() }
            }
            .frame(maxWidth: .infinity)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if isFinished {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done", systemImage: "checkmark", role: .confirm) { dismiss() }
            }
        } else {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel", systemImage: "xmark", role: .cancel) { dismiss() }
            }
            if phase == .entering || phase == .lookingUp {
                ToolbarItem(placement: .confirmationAction) {
                    if phase == .lookingUp {
                        ProgressView()
                    } else {
                        Button("Continue", systemImage: "checkmark", role: .confirm, action: lookUp)
                            .disabled(!TracksideLinkCode.isComplete(code))
                    }
                }
            }
        }
    }
    #endif

    #if os(macOS)
    @ViewBuilder
    private var macButtons: some View {
        switch phase {
        case .entering, .lookingUp:
            Button("Cancel", role: .cancel) { dismiss() }
                .keyboardShortcut(.cancelAction)
            Button("Continue", action: lookUp)
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!TracksideLinkCode.isComplete(code) || isBusy)
        case .confirming(let info), .deciding(let info, _):
            Button("Cancel", role: .cancel) { dismiss() }
                .keyboardShortcut(.cancelAction)
            Button("Deny") { decide(info, approve: false) }
                .disabled(isBusy)
            Button("Approve") { decide(info, approve: true) }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(isBusy)
        case .connected, .denied, .unknownServer:
            Button("Done") { dismiss() }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
        }
    }
    #endif

    /// The end of the flow: a symbol and one line, as a finished setup step reads.
    private func outcome(systemImage: String, tint: Color, title: String, detail: String? = nil) -> some View {
        Section {
            VStack(spacing: SettingsSpacing.row) {
                Image(systemName: systemImage)
                    .font(.system(size: 48))
                    .foregroundStyle(tint)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                if let detail {
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, SettingsSpacing.row)
            .accessibilityElement(children: .combine)
            #if os(iOS)
            .listRowBackground(Color.clear)
            #endif
        }
    }

    // MARK: - Words

    private func deniedTitle(_ name: String) -> String {
        name.isEmpty ? String(localized: "The TV wasn’t connected") : String(localized: "\(name) wasn’t connected")
    }

    private var unknownServerDetail: String {
        #if os(macOS)
        String(localized: "This TV belongs to a server this Mac isn’t signed in to.")
        #else
        String(localized: "This TV belongs to a server this iPhone isn’t signed in to.")
        #endif
    }

    // MARK: - Actions

    private func start() {
        guard work == nil else { return }
        if let link {
            guard let match = servers.first(where: { link.isFor(serverUUID: $0.serverUUID) }) else {
                phase = .unknownServer
                return
            }
            serverID = match.id
            code = link.code
            lookUp()
        } else {
            serverID = serverID ?? servers.first { music.servers.status[$0.id] == .online }?.id ?? servers.first?.id
            isCodeFocused = true
        }
    }

    private func typed(_ typed: String) {
        let shown = TracksideLinkCode.formatted(typed)
        guard shown == typed else {
            code = shown
            return
        }
        guard phase == .entering else { return }
        problem = nil
        if TracksideLinkCode.isComplete(shown) { lookUp() }
    }

    private func lookUp() {
        guard phase == .entering, TracksideLinkCode.isComplete(code) else { return }
        guard let client = serverID.flatMap(music.servers.tracksideClient(for:)) else {
            problem = TracksideLinkWords.problem(TracksideError.unproven)
            return
        }
        let code = code
        problem = nil
        phase = .lookingUp
        work = Task {
            do {
                let info = try await Self.ask(client, .lookup, code: code)
                guard !Task.isCancelled else { return }
                switch info.state {
                case .pending, .unknown:
                    phase = .confirming(info)
                case .approved, .denied:
                    fail(TracksideError.conflict(reason: "link_used"))
                }
            } catch {
                guard !Task.isCancelled else { return }
                fail(error)
            }
        }
    }

    private func decide(_ info: TracksideLinkInfo, approve: Bool) {
        guard phase == .confirming(info), let client = serverID.flatMap(music.servers.tracksideClient(for:)) else { return }
        let code = code
        problem = nil
        phase = .deciding(info, approving: approve)
        work = Task {
            do {
                let answer = try await Self.ask(client, approve ? .approve : .deny, code: code)
                guard !Task.isCancelled else { return }
                let name = answer.device.name.isEmpty ? info.device.name : answer.device.name
                if approve {
                    connected(name)
                } else {
                    phase = .denied(name: name)
                }
            } catch {
                guard !Task.isCancelled else { return }
                if error is URLError {
                    // Nothing was decided: the same TV can still be approved.
                    phase = .confirming(info)
                    problem = TracksideLinkWords.problem(error)
                } else {
                    fail(error)
                }
            }
        }
    }

    /// Back to the code, saying why, so a new one from the TV can be typed in its place.
    private func fail(_ error: any Error) {
        phase = .entering
        problem = TracksideLinkWords.problem(error)
        isCodeFocused = true
    }

    private func connected(_ name: String) {
        guard !isFinished else { return }
        phase = .connected(name: name)
        connections += 1
    }

    /// The TV collected its key before the approval's answer arrived.
    private func noteLinked(_ linked: [MusicServers.LinkedDevice]) {
        guard case .deciding(let info, approving: true) = phase, let last = linked.last, last.serverID == serverID else { return }
        connected(last.device.name.isEmpty ? info.device.name : last.device.name)
    }

    @concurrent
    nonisolated private static func ask(_ client: TracksideClient, _ step: LinkStep, code: String) async throws -> TracksideLinkInfo {
        switch step {
        case .lookup: try await client.lookupLink(code: code)
        case .approve: try await client.approveLink(code: code)
        case .deny: try await client.denyLink(code: code)
        }
    }
}

private nonisolated enum LinkStep: Sendable {
    case lookup, approve, deny
}

// MARK: - Links from a TV's QR code

/// A `tracks://link` link opened in Tracks, from the QR code a TV shows, waiting to be
/// approved.
@MainActor
@Observable
final class TracksideLinkApprovals {
    static let shared = TracksideLinkApprovals()

    struct Pending: Identifiable {
        let id = UUID()
        let request: TracksideLinkRequest
    }

    var pending: Pending?

    /// Takes a TV's link. False for any other link.
    @discardableResult
    func open(_ url: URL) -> Bool {
        guard let request = TracksideLinkRequest(url: url) else { return false }
        pending = Pending(request: request)
        return true
    }
}

extension View {
    /// Offers to approve the TV a `tracks://link` link names, wherever the app is. The link
    /// itself is taken by ``TracksideLinkApprovals/open(_:)`` where the app follows its links.
    func presentsLinkApprovals(music: YourMusic) -> some View {
        modifier(LinkApprovalPresenter(music: music))
    }
}

private struct LinkApprovalPresenter: ViewModifier {
    let music: YourMusic
    private var approvals = TracksideLinkApprovals.shared

    init(music: YourMusic) {
        self.music = music
    }

    func body(content: Content) -> some View {
        @Bindable var approvals = approvals
        content
            .sheet(item: $approvals.pending) { pending in
                ConnectTVSheet(link: pending.request)
                    .environment(music)
            }
    }
}
