import SwiftUI
import Network
import TracksCore
#if os(iOS)
import AVFoundation
import VisionKit
#endif

/// Your servers, in Settings: each with whether it's reachable and how much is on it. On
/// iPhone each row pushes its page; a Mac Settings pane has nowhere to push, so each row
/// carries its actions in a menu and a context menu instead.
struct ServersSection: View {
    @Environment(YourMusic.self) private var music
    /// `-TracksConnectServer YES` opens the form at once, for screenshots.
    @State private var addsServer = Self.opensForm

    private static var opensForm: Bool {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "TracksConnectServer")
        #else
        false
        #endif
    }

    var body: some View {
        Section {
            ForEach(music.servers.servers) { server in
                #if os(macOS)
                MacServerRow(server: server)
                #else
                NavigationLink {
                    ServerDetailPage(serverID: server.id)
                } label: {
                    ServerRow(server: server)
                }
                #endif
            }
            #if os(macOS)
            HStack {
                Spacer()
                Button("Connect a Server…") { addsServer = true }
            }
            #else
            Button("Connect a Server…", systemImage: "plus") { addsServer = true }
            #endif
        } header: {
            Text("Servers")
        } footer: {
            Text("Play and download from your own music server: Trackside, Navidrome, Gonic, Airsonic, Ampache and any other that speaks the Subsonic API.")
        }
        .sheet(isPresented: $addsServer) {
            ServerForm()
        }
    }
}

/// Shown only once a server or Lidarr is set up: without one, Download would only ever be
/// Add to Library.
struct DownloadChoiceSection: View {
    @Environment(YourMusic.self) private var music
    @Environment(Lidarr.self) private var lidarr: Lidarr?
    @AppStorage(DownloadChoice.isOnKey) private var isOn = false
    @AppStorage(DownloadChoice.defaultKey) private var preferred = DownloadDefault.ask

    var body: some View {
        if !music.servers.servers.isEmpty || lidarr?.isSetUp == true {
            section
        }
    }

    private var section: some View {
        Section {
            Toggle("Download to Apple Music or Your Music", isOn: $isOn.animation())
            if isOn {
                Picker("Always Use", selection: $preferred) {
                    ForEach(DownloadDefault.allCases, id: \.self) { choice in
                        Text(DownloadWords.name(of: choice)).tag(choice)
                    }
                }
            }
        } header: {
            Text("Download")
        } footer: {
            Text("Albums and songs get one Download button that adds them to your Apple Music library or gets them on your server.")
        }
    }
}

/// A server with its status, as a row.
struct ServerRow: View {
    let server: SubsonicServer
    @Environment(YourMusic.self) private var music

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "server.rack")
                #if os(macOS)
                .font(.title2)
                #else
                .font(.title3)
                #endif
                .foregroundStyle(.tint)
                .frame(width: 30)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(server.name)
                Text(statusLine)
                    #if os(macOS)
                    .font(.caption)
                    #else
                    .font(.subheadline)
                    #endif
                    .foregroundStyle(problem ? AnyShapeStyle(.ink(.orange)) : AnyShapeStyle(.secondary))
                    .lineLimit(1)
                    .contentTransition(.opacity)
            }
        }
    }

    /// Out of reach or refusing the password: something the person may want to fix.
    private var problem: Bool {
        switch music.servers.status[server.id] {
        case .offline, .wrongPassword, .failed: true
        default: false
        }
    }

    private var statusLine: String {
        if music.servers.syncing.contains(server.id) { return String(localized: "Syncing…") }
        let count = music.servers.catalogs[server.id]?.count ?? 0
        let songs = String(AttributedString(localized: "^[\(count) song](inflect: true)").characters)
        switch music.servers.status[server.id] {
        case .online:
            // Connected and empty reads differently from out of reach.
            guard count > 0 else { return String(localized: "Connected · No songs yet") }
            #if os(macOS)
            return "\(songs) · \(server.url.host() ?? server.url.absoluteString)"
            #else
            return songs
            #endif
        case .connecting: return String(localized: "Connecting…")
        case .offline: return String(localized: "Can’t be reached · \(songs)")
        case .wrongPassword: return server.isTrackside ? String(localized: "Needs pairing again") : String(localized: "Password needed")
        case .failed(let reason): return reason
        case nil: return songs
        }
    }
}

/// What removing a server takes with it, counted, for its confirmation.
private struct ServerRemoval {
    let title: String
    let message: String
    let downloadIDs: Set<String>

    @MainActor
    init(_ server: SubsonicServer, music: YourMusic) {
        downloadIDs = Set(music.downloads.items.values.filter { $0.track.serverID == server.id }.map(\.track.id))
        let songs = music.servers.catalogs[server.id]?.count ?? 0
        title = String(localized: "Remove \u{201C}\(server.name)\u{201D}?")
        let songWords = String(AttributedString(localized: "^[\(songs) song](inflect: true)").characters)
        let downloadWords = String(AttributedString(localized: "^[\(downloadIDs.count) download](inflect: true)").characters)
        message = downloadIDs.isEmpty
            ? String(localized: "Its \(songWords) leave Tracks. Your plays of them stay in your history.")
            : String(localized: "Its \(songWords) and \(downloadWords) leave Tracks. Your plays of them stay in your history.")
    }

    @MainActor
    func perform(_ serverID: String, music: YourMusic) {
        music.downloads.remove(downloadIDs)
        music.servers.remove(serverID)
    }
}

#if os(macOS)
/// A server in the Mac pane: its status, and its actions in a ••• menu and a context menu.
private struct MacServerRow: View {
    let server: SubsonicServer
    @Environment(YourMusic.self) private var music
    @State private var editsServer = false
    @State private var confirmsRemove = false

    var body: some View {
        let removal = ServerRemoval(server, music: music)
        VStack(alignment: .leading, spacing: SettingsSpacing.standard) {
            HStack(spacing: SettingsSpacing.row) {
                ServerRow(server: server)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Menu {
                    actions
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("More")
                .accessibilityLabel(Text("More for \(server.name)"))
            }
            if music.servers.movedToTrackside.contains(server.id) {
                MatchSongsRow(serverID: server.id)
                    .padding(.leading, 42)
            }
        }
        .padding(.vertical, 2)
        .contextMenu { actions }
        .sheet(isPresented: $editsServer) {
            ServerForm(editing: server)
        }
        .confirmationDialog(removal.title, isPresented: $confirmsRemove, titleVisibility: .visible) {
            Button("Remove Server", role: .destructive) { removal.perform(server.id, music: music) }
        } message: {
            Text(removal.message)
        }
    }

    @ViewBuilder
    private var actions: some View {
        let isSyncing = music.servers.syncing.contains(server.id)
        Button("Check Connection", systemImage: "antenna.radiowaves.left.and.right") {
            Task { await music.servers.connect(server.id) }
        }
        Button(isSyncing ? "Syncing…" : "Sync Now", systemImage: "arrow.triangle.2.circlepath") {
            Task { await music.servers.sync(server.id) }
        }
        .disabled(isSyncing)
        if server.isTrackside {
            Button("Pair Again…", systemImage: "key") { editsServer = true }
        } else {
            Button("Change Password or Address…", systemImage: "key") { editsServer = true }
        }
        Divider()
        Toggle("Tell Server About Plays", isOn: Binding(
            get: { music.servers.reportsPlays(to: server.id) },
            set: { music.servers.setReportsPlays($0, to: server.id) }
        ))
        Divider()
        Button("Remove Server…", systemImage: "trash", role: .destructive) { confirmsRemove = true }
    }
}
#endif

/// One server: sync it, change its password or pair it again, or remove it.
struct ServerDetailPage: View {
    let serverID: String
    @Environment(YourMusic.self) private var music
    @Environment(\.dismiss) private var dismiss
    @State private var editsServer = false
    @State private var confirmsRemove = false

    var body: some View {
        if let server = music.servers.server(serverID) {
            let removal = ServerRemoval(server, music: music)
            let isSyncing = music.servers.syncing.contains(serverID)
            Form {
                Section {
                    ServerRow(server: server)
                    LabeledContent("Address", value: server.url.absoluteString)
                    if server.isTrackside {
                        if let device = server.deviceName {
                            LabeledContent("Paired As", value: device)
                        }
                    } else {
                        LabeledContent("Username", value: server.username)
                    }
                }
                if music.servers.movedToTrackside.contains(serverID) {
                    Section {
                        MatchSongsRow(serverID: serverID)
                    } footer: {
                        Text("Songs you downloaded or put in playlists from the server this replaced are matched to the same songs here. Downloaded files stay as they are.")
                    }
                }
                Section {
                    Button {
                        Task { await music.servers.connect(serverID) }
                    } label: {
                        Label("Check Connection", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    Button {
                        Task { await music.servers.sync(serverID) }
                    } label: {
                        HStack {
                            Label(isSyncing ? "Syncing…" : "Sync Now", systemImage: "arrow.triangle.2.circlepath")
                            Spacer()
                            if isSyncing { ProgressView() }
                        }
                    }
                    .disabled(isSyncing)
                    if server.isTrackside {
                        Button("Pair Again…", systemImage: "key") { editsServer = true }
                    } else {
                        Button("Change Password or Address…", systemImage: "key") { editsServer = true }
                    }
                } footer: {
                    if server.isTrackside {
                        Text("Tracks keeps up with changes on your server as they happen, so Tracks Radio, your mixes and search reach all of it. The songs themselves stream as you play them, unless you download them.")
                    } else {
                        Text("Tracks syncs the list of songs on your server each day, so Tracks Radio, your mixes and search reach all of it. The songs themselves stream as you play them, unless you download them.")
                    }
                }
                Section {
                    Toggle("Tell Server About Plays", isOn: Binding(
                        get: { music.servers.reportsPlays(to: serverID) },
                        set: { music.servers.setReportsPlays($0, to: serverID) }
                    ))
                } footer: {
                    if server.isTrackside {
                        Text("Keeps your server’s play counts right. Songs played while it can’t be reached are sent once it can.")
                    } else {
                        Text("Keeps your server’s play counts right. If your server sends your plays on to Last.fm or ListenBrainz and Tracks scrobbles to Last.fm too, turn one of them off so songs aren’t counted twice.")
                    }
                }
                Section {
                    Button("Remove Server…", role: .destructive) { confirmsRemove = true }
                        .confirmationDialog(removal.title, isPresented: $confirmsRemove, titleVisibility: .visible) {
                            Button("Remove Server", role: .destructive) {
                                removal.perform(serverID, music: music)
                                dismiss()
                            }
                        } message: {
                            Text(removal.message)
                        }
                }
            }
            .navigationTitle(server.name)
            .toolbarTitleDisplayMode(.inline)
            .sheet(isPresented: $editsServer) {
                ServerForm(editing: server)
            }
        }
    }
}

/// The one-time step after an entry moves to Trackside: its downloads and playlist songs,
/// which name the old server's songs, are pointed at the same songs on the new one.
private struct MatchSongsRow: View {
    let serverID: String
    @Environment(YourMusic.self) private var music
    @State private var isMatching = false
    @State private var outcome: MatchOutcome?

    var body: some View {
        let toMatch = music.songsToMatch(on: serverID).count
        let isReady = music.servers.catalogs[serverID]?.isEmpty == false && !music.servers.syncing.contains(serverID)
        Group {
            #if os(macOS)
            SettingsDetailRow(
                title: Text("Match Downloads and Playlists"),
                detail: isReady
                    ? Text("^[\(toMatch) song](inflect: true) from the server this replaced. Downloaded files stay as they are.")
                    : Text("Once this server’s songs are in, the ones from the server it replaced can be matched to them.")
            ) {
                if isMatching {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button("Match") { match() }
                        .disabled(!isReady)
                }
            }
            #else
            Button {
                match()
            } label: {
                HStack {
                    Label("Match Downloads and Playlists", systemImage: "arrow.triangle.merge")
                    Spacer()
                    if isMatching {
                        ProgressView()
                    } else if isReady {
                        Text("^[\(toMatch) song](inflect: true)")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(!isReady || isMatching)
            #endif
        }
        .task(id: "\(isReady).\(toMatch)") {
            // Nothing kept from the old server: there's nothing to offer.
            if isReady, toMatch == 0, !isMatching { music.servers.finishedMatching(serverID) }
        }
        .alert(outcome?.title ?? "", isPresented: Binding(get: { outcome != nil }, set: { if !$0 { outcome = nil } })) {
            Button("OK", role: .cancel) { outcome = nil }
        } message: {
            if let message = outcome?.message { Text(message) }
        }
    }

    private func match() {
        guard !isMatching else { return }
        isMatching = true
        let name = music.servers.server(serverID)?.name ?? ""
        Task {
            let result = await music.matchMovedSongs(on: serverID)
            isMatching = false
            outcome = MatchOutcome(result, serverName: name)
        }
    }
}

/// What matching did, for its alert: how many were matched, and which weren't found.
private struct MatchOutcome {
    let title: String
    let message: String?

    init(_ result: ServerSongMatching.Result, serverName: String) {
        title = String(AttributedString(localized: "Matched ^[\(result.matched.count) Song](inflect: true)").characters)
        guard !result.unmatched.isEmpty else {
            message = nil
            return
        }
        let names = result.unmatched.prefix(8).map { "\u{201C}\($0.title)\u{201D}" }.formatted(.list(type: .and))
        let count = result.unmatched.count
        if count == 1 {
            message = String(localized: "\(names) isn’t on \(serverName). It stays as it was.")
        } else if count > 8 {
            message = String(localized: "\(count) songs aren’t on \(serverName), including \(names). They stay as they were.")
        } else {
            message = String(localized: "\(names) aren’t on \(serverName). They stay as they were.")
        }
    }
}

// MARK: - Connecting

/// Connecting a server, or changing one, checked before it's kept: its address, then a
/// username and password for a Subsonic server, or for Trackside a code from its dashboard or
/// a password, which pair this device with it. On iPhone a pairing code can be scanned too,
/// and Trackside servers on the same network are offered as they're found. On iPhone a sheet
/// with its actions in the bar; on the Mac a sheet with its title in the content and its
/// actions at the foot.
struct ServerForm: View {
    var editing: SubsonicServer?
    /// A pairing code scanned or opened as a link: it's used at once.
    var pairing: PairingPayload?
    @Environment(YourMusic.self) private var music
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var code = ""
    @State private var method = SignInMethod.code
    @State private var detected: Detected?
    @State private var isConnecting = false
    @State private var problem: String?
    @State private var nearby = TracksideBrowser()
    @State private var scansCode = false
    /// The code this device shows while it waits to be approved in the dashboard.
    @State private var approval: TracksidePairRequest?
    @State private var waitingForApproval: Task<TracksidePairResult?, any Error>?
    @FocusState private var focus: Field?

    private enum Field { case address, username, password, code, name }

    enum SignInMethod: Hashable {
        /// The code the dashboard shows, typed here.
        case code
        /// A code shown here, approved in the dashboard: for a Mac without a camera, or when
        /// the dashboard is open on another device.
        case approval
        case password
    }

    /// What answers at the address typed, once it's been asked.
    enum Detected: Equatable {
        case trackside(TracksideCapabilities, address: URL)
        case subsonic(address: URL)

        var url: URL {
            switch self {
            case .trackside(_, let address), .subsonic(let address): address
            }
        }
    }

    var body: some View {
        #if os(macOS)
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: SettingsSpacing.tight) {
                Text(title)
                    .font(.title2.bold())
                Text(editing == nil
                    ? "Trackside, Navidrome, Gonic, Airsonic, Ampache, LMS or any other server that speaks the Subsonic API."
                    : "Tracks checks the server answers before it keeps the change.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding([.horizontal, .top], 20)

            form
                .disabled(isConnecting)
                .settingsPane()

            HStack(spacing: SettingsSpacing.standard) {
                if isConnecting {
                    ProgressView()
                        .controlSize(.small)
                    Text(isTrackside ? "Pairing…" : "Connecting…")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(confirmTitle, action: connect)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canConnect || isConnecting)
            }
            // The form ends in its own inset, so the bar needs none above it.
            .padding([.horizontal, .bottom], 20)
        }
        .onAppear(perform: fillIn)
        .task { await pairFromPayload() }
        .task(id: address) { await detect() }
        .onAppear { nearby.start() }
        .onDisappear(perform: stop)
        #else
        NavigationStack {
            form
                .navigationTitle(title)
                .toolbarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", systemImage: "xmark") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if isConnecting {
                            ProgressView()
                        } else {
                            Button(confirmTitle, systemImage: "checkmark", action: connect)
                                .disabled(!canConnect)
                        }
                    }
                }
                .disabled(isConnecting)
                .onAppear(perform: fillIn)
                .task { await pairFromPayload() }
                .task(id: address) { await detect() }
                .onAppear { nearby.start() }
                .onDisappear(perform: stop)
                .sheet(isPresented: $scansCode) {
                    PairingScannerSheet { payload in
                        scansCode = false
                        Task { await pair(with: payload) }
                    }
                }
        }
        .interactiveDismissDisabled(isConnecting)
        #endif
    }

    private var title: LocalizedStringKey {
        if let editing { return "Edit \u{201C}\(editing.name)\u{201D}" }
        return "Connect a Server"
    }

    private var confirmTitle: LocalizedStringKey {
        if isTrackside { return "Pair" }
        return editing == nil ? "Connect" : "Save"
    }

    /// Trackside answered at this address, or it's the address of the Trackside entry being
    /// changed and hasn't been asked yet, as when it's out of reach for now.
    private var isTrackside: Bool {
        let url = SubsonicServer.address(from: address)
        if case .trackside = detected, detected?.url == url { return true }
        if let editing, editing.isTrackside, editing.url == url, detected?.url != url { return true }
        return false
    }

    private var capabilities: TracksideCapabilities? {
        if case .trackside(let capabilities, let url) = detected, url == SubsonicServer.address(from: address) { return capabilities }
        return nil
    }

    /// Whether this server lets you sign in with a password from the app, as most do.
    private var allowsPassword: Bool {
        capabilities?.auth?.passwordLogin != false
    }

    private var allowsApproval: Bool {
        capabilities?.auth?.pairRequests != false
    }

    /// The way in actually used: one the server doesn't offer falls back to a typed code.
    private var signIn: SignInMethod {
        switch method {
        case .password where !allowsPassword, .approval where !allowsApproval: .code
        default: method
        }
    }

    private var form: some View {
        Form {
            #if os(iOS)
            if editing == nil || editing?.isTrackside == true {
                Section {
                    Button("Scan Pairing Code", systemImage: "qrcode.viewfinder") { scansCode = true }
                } footer: {
                    Text("In your Trackside dashboard, open My Account and choose Show Code under Pair a Device.")
                }
            }
            #endif
            if !nearby.found.isEmpty {
                Section("On Your Network") {
                    ForEach(nearby.found) { server in
                        Button {
                            choose(server)
                        } label: {
                            HStack {
                                Label {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(server.name)
                                            .foregroundStyle(.primary)
                                        Text(verbatim: server.url.host() ?? server.url.absoluteString)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                } icon: {
                                    Image(systemName: "server.rack")
                                }
                                Spacer()
                                if SubsonicServer.address(from: address) == server.url {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.tint)
                                        .accessibilityLabel("Chosen")
                                }
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            Section {
                TextField("Address", text: $address, prompt: Text(verbatim: "music.example.com"))
                    .textContentType(.URL)
                    .literalEntry(isAddress: true)
                    .focused($focus, equals: .address)
                    #if os(iOS)
                    .submitLabel(.next)
                    .onSubmit {
                        guard isTrackside else { focus = .username; return }
                        switch signIn {
                        case .code: focus = .code
                        case .password: focus = .username
                        case .approval: focus = nil
                        }
                    }
                    #endif
                if isTrackside {
                    tracksideSignIn
                } else {
                    passwordFields
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if let problem {
                        Label(problem, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.ink(.red))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if isInsecure {
                        Label("This address isn’t encrypted. Use https if your server has it, especially away from home.", systemImage: "lock.open")
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text(privacyLine)
                }
                .animation(SettingsMotion.fade, value: problem)
                .animation(SettingsMotion.fade, value: isInsecure)
                .animation(SettingsMotion.fade, value: isTrackside)
            }
            Section {
                TextField("Name", text: $name, prompt: Text(defaultName))
                    .titleEntry()
                    .focused($focus, equals: .name)
            } footer: {
                #if os(iOS)
                Text("Works with Trackside, Navidrome, Gonic, Airsonic, Ampache, LMS and other servers that speak the Subsonic API.")
                #endif
            }
        }
        .animation(SettingsMotion.row, value: isTrackside)
        .animation(SettingsMotion.row, value: nearby.found)
    }

    @ViewBuilder
    private var tracksideSignIn: some View {
        if allowsPassword || allowsApproval {
            Picker("Sign In With", selection: $method) {
                Text("Code").tag(SignInMethod.code)
                if allowsApproval { Text("Approval").tag(SignInMethod.approval) }
                if allowsPassword { Text("Password").tag(SignInMethod.password) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
        if signIn == .approval {
            approvalRow
        } else if signIn == .code {
            TextField("Code", text: $code, prompt: Text(verbatim: "Q7RM-4X2K"))
                .autocorrectionDisabled()
                .monospaced()
                .focused($focus, equals: .code)
                #if os(iOS)
                .textInputAutocapitalization(.characters)
                .submitLabel(.go)
                .onSubmit(connect)
                #endif
        } else {
            passwordFields
        }
    }

    /// The code to approve, once Pair has asked for one.
    @ViewBuilder
    private var approvalRow: some View {
        if let approval {
            LabeledContent("Code") {
                Text(verbatim: approval.code)
                    .font(.title2.monospacedDigit().weight(.semibold))
                    .textSelection(.enabled)
            }
            .accessibilityValue(approval.code.filter(\.isNumber).map(String.init).joined(separator: " "))
            Text("In your Trackside dashboard, open My Account and enter this code under Pair a Device. It expires at \(approval.expiresAt, style: .time).")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            Text("Choose Pair to show a code here, then approve it in your Trackside dashboard.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var passwordFields: some View {
        TextField("Username", text: $username, prompt: Text("Required"))
            .textContentType(.username)
            .literalEntry()
            .focused($focus, equals: .username)
            #if os(iOS)
            .submitLabel(.next)
            .onSubmit { focus = .password }
            #endif
        SecureField("Password", text: $password, prompt: Text("Required"))
            .textContentType(.password)
            .focused($focus, equals: .password)
            #if os(iOS)
            .submitLabel(.go)
            .onSubmit(connect)
            #endif
    }

    /// Where what's typed goes, said once under the fields.
    private var privacyLine: String {
        #if os(macOS)
        if isTrackside {
            return signIn == .password
                ? String(localized: "Your password goes to your server once, to pair this Mac. Tracks keeps only the key it gets back, in this Mac’s keychain.")
                : String(localized: "Tracks keeps the key your server gives this Mac in its keychain.")
        }
        return String(localized: "Tracks sends a token made from your password, never the password itself, and keeps the password in this Mac’s keychain.")
        #else
        if isTrackside {
            return signIn == .password
                ? String(localized: "Your password goes to your server once, to pair this iPhone. Tracks keeps only the key it gets back, in this iPhone’s keychain.")
                : String(localized: "Tracks keeps the key your server gives this iPhone in its keychain.")
        }
        return String(localized: "Tracks sends a token made from your password, never the password itself, and keeps the password in this iPhone’s keychain.")
        #endif
    }

    private var canConnect: Bool {
        guard SubsonicServer.address(from: address) != nil else { return false }
        if isTrackside {
            if capabilities?.setupRequired == true { return false }
            if signIn == .approval { return approval == nil }
            if signIn == .code { return PairingPayload.typedCode(code) != nil }
        }
        return !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    /// The server's name where it says, then its host, or "Home Server" until there's an address.
    private var defaultName: String {
        if let name = capabilities?.name, !name.isEmpty { return name }
        let host = SubsonicServer.address(from: address)?.host() ?? ""
        return host.isEmpty ? String(localized: "Home Server") : host
    }

    /// Plain http to anywhere but the home network. Trackside is never sent a password,
    /// code or key there at all.
    private var isInsecure: Bool {
        guard let url = SubsonicServer.address(from: address), url.scheme == "http" else { return false }
        return !TracksideEndpointProof.isNearby(url)
    }

    private func fillIn() {
        if let editing {
            name = editing.name
            address = editing.url.absoluteString
            username = editing.username
            if editing.isTrackside {
                method = .code
                focus = .code
            } else {
                focus = .password
            }
        } else if pairing == nil {
            focus = .address
        }
    }

    private func choose(_ server: TracksideBrowser.Found) {
        address = server.url.absoluteString
        if name.trimmingCharacters(in: .whitespaces).isEmpty || nearby.found.contains(where: { $0.name == name }) {
            name = server.name
        }
        method = .code
        problem = nil
        focus = .code
    }

    /// Asks what's at the address once typing pauses, so the form can offer the right way in.
    private func detect() async {
        guard let url = SubsonicServer.address(from: address) else {
            detected = nil
            return
        }
        guard detected?.url != url else { return }
        try? await Task.sleep(for: .milliseconds(500))
        guard !Task.isCancelled else { return }
        await detect(url)
    }

    private func detect(_ url: URL) async {
        do {
            switch try await music.servers.detect(at: url) {
            case .trackside(let capabilities):
                guard !Task.isCancelled else { return }
                detected = .trackside(capabilities, address: url)
                problem = capabilities.setupRequired == true
                    ? String(localized: "Finish setting up your server in its dashboard, then pair this device.")
                    : nil
            case .subsonic:
                detected = .subsonic(address: url)
            }
        } catch {
            // Not reachable yet, or still being typed: Connect asks again.
            if !Task.isCancelled { detected = nil }
        }
    }

    private func connect() {
        guard canConnect, !isConnecting, let url = SubsonicServer.address(from: address) else { return }
        problem = nil
        isConnecting = true
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        Task {
            defer { isConnecting = false }
            if detected?.url != url { await detect(url) }
            let serverName = trimmedName.isEmpty ? defaultName : trimmedName
            do {
                if isTrackside {
                    let device = TracksideDeviceIdentity.current
                    let result: TracksidePairResult
                    if signIn == .approval {
                        guard let approved = try await waitForApproval(at: url, device: device) else { return }
                        result = approved
                    } else if signIn == .code, let typed = PairingPayload.typedCode(code) {
                        result = try await TracksideClient.claim(at: url, code: typed, device: device)
                    } else {
                        result = try await TracksideClient.login(at: url, username: username.trimmingCharacters(in: .whitespaces), password: password, device: device)
                    }
                    await music.servers.pair(result, at: url, name: serverName, replacing: editing)
                } else {
                    var server = editing ?? SubsonicServer(name: "", url: url, username: "")
                    server.name = serverName
                    server.url = url
                    server.username = username.trimmingCharacters(in: .whitespaces)
                    try await music.servers.add(server, password: password)
                }
                dismiss()
            } catch {
                problem = describe(error)
            }
        }
    }

    /// Shows a code for someone signed in to the dashboard to approve, and waits for them.
    /// - Returns: nil when the code expired or the form closed first.
    private func waitForApproval(at url: URL, device: TracksideDeviceInfo) async throws -> TracksidePairResult? {
        let request = try await TracksideClient.requestPairing(at: url, device: device)
        approval = request
        defer { approval = nil }
        let interval = Duration.milliseconds(max(request.pollIntervalMS ?? 2_000, 1_000))
        let waiting = Task { () throws -> TracksidePairResult? in
            while true {
                try await Task.sleep(for: interval)
                let poll = try await TracksideClient.pollPairing(request, at: url)
                switch poll.state {
                case .approved: return poll.result
                case .expired: return nil
                case .pending, .unknown: continue
                }
            }
        }
        waitingForApproval = waiting
        defer { waitingForApproval = nil }
        let result = try await waiting.value
        if result == nil {
            problem = String(localized: "The code expired. Choose Pair for a new one.")
        }
        return result
    }

    /// Stops listening and waiting when the form closes.
    private func stop() {
        nearby.stop()
        waitingForApproval?.cancel()
    }

    /// A scanned code or an opened link: the first of its addresses that proves it is this
    /// server takes the code.
    private func pairFromPayload() async {
        guard let pairing else { return }
        await pair(with: pairing)
    }

    private func pair(with payload: PairingPayload) async {
        guard !isConnecting else { return }
        problem = nil
        isConnecting = true
        defer { isConnecting = false }
        if let first = payload.urls.first?.url {
            address = first.absoluteString
        }
        if name.isEmpty, let named = payload.name { name = named }
        let pins = TracksidePins(payload.urls)
        let transport = URLSessionTracksideTransport(pins: pins)
        // The secret goes only to an address that proves it made it.
        let endpoints = ServerEndpoints(serverUUID: payload.serverID, candidates: payload.urls, current: payload.urls[0].url, proofKey: payload.proofKey, pins: pins, transport: transport)
        guard let chosen = await endpoints.race() else {
            problem = unreachable
            return
        }
        address = chosen.url.absoluteString
        do {
            let result = try await TracksideClient.claim(at: chosen.url, code: payload.code, device: TracksideDeviceIdentity.current, transport: transport)
            let trimmed = name.trimmingCharacters(in: .whitespaces)
            let serverName = trimmed.isEmpty ? (payload.name ?? chosen.url.host() ?? String(localized: "Home Server")) : trimmed
            await music.servers.pair(result, at: chosen.url, name: serverName, replacing: editing)
            dismiss()
        } catch TracksideError.unauthenticated {
            problem = String(localized: "This code has been used or has expired. Make a new one in your server’s dashboard.")
        } catch {
            problem = describe(error)
        }
    }

    private func describe(_ error: any Error) -> String {
        switch error {
        case let error as SubsonicError: MusicServers.describe(error)
        case let error as TracksideError: MusicServers.describe(error)
        default: unreachable
        }
    }

    private var unreachable: String {
        #if os(macOS)
        String(localized: "Couldn’t reach the server. Check the address, and that this Mac can reach it.")
        #else
        String(localized: "Couldn’t reach the server. Check the address, and that this iPhone can reach it.")
        #endif
    }
}

// MARK: - Trackside servers nearby

/// Trackside servers on this network, as they announce themselves with Bonjour: each one's
/// name and the address it answers at, found while the form is open.
@MainActor
@Observable
final class TracksideBrowser {
    struct Found: Identifiable, Hashable {
        /// The server's own id where it says, so one seen on two interfaces is listed once.
        let id: String
        let name: String
        let url: URL
    }

    private(set) var found: [Found] = []
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var resolving: [String: NWConnection] = [:]

    func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_trackside._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated { self?.update(results) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        for connection in resolving.values { connection.cancel() }
        resolving = [:]
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var seen = Set<String>()
        for result in results {
            guard case .service(let name, _, _, _) = result.endpoint else { continue }
            var record: [String: String] = [:]
            if case .bonjour(let txt) = result.metadata { record = txt.dictionary }
            let id = record["id"] ?? name
            seen.insert(id)
            guard !found.contains(where: { $0.id == id }), resolving[id] == nil else { continue }
            resolve(result.endpoint, id: id, name: name, record: record)
        }
        found.removeAll { !seen.contains($0.id) }
    }

    /// Connects to a service just long enough to learn its address. IPv4, since an IPv6
    /// link-local address would need its interface named in the URL.
    private func resolve(_ endpoint: NWEndpoint, id: String, name: String, record: [String: String]) {
        let parameters = NWParameters.tcp
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        let connection = NWConnection(to: endpoint, using: parameters)
        connection.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated { self?.resolved(id: id, name: name, record: record, state: state) }
        }
        resolving[id] = connection
        connection.start(queue: .main)
    }

    private func resolved(id: String, name: String, record: [String: String], state: NWConnection.State) {
        guard let connection = resolving[id] else { return }
        switch state {
        case .ready:
            if case .hostPort(let host, let port) = connection.currentPath?.remoteEndpoint,
               let url = Self.url(host: host, port: port, record: record),
               !found.contains(where: { $0.id == id }) {
                found.append(Found(id: id, name: name, url: url))
                found.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            }
            fallthrough
        case .failed, .cancelled:
            connection.stateUpdateHandler = nil
            connection.cancel()
            resolving[id] = nil
        default:
            break
        }
    }

    private static func url(host: NWEndpoint.Host, port: NWEndpoint.Port, record: [String: String]) -> URL? {
        let address: String
        switch host {
        case .ipv4(let ip): address = ip.rawValue.map(String.init).joined(separator: ".")
        case .name(let name, _): address = name
        case .ipv6: return nil
        @unknown default: return nil
        }
        let scheme = record["tls"] == "1" ? "https" : "http"
        var path = record["path"] ?? ""
        if path == "/" { path = "" }
        return URL(string: "\(scheme)://\(address):\(port.rawValue)\(path)")
    }
}

// MARK: - Pairing links

/// A `trackside://pair` link opened in Tracks, say from a code scanned with the Camera,
/// waiting for the servers list to take it.
@MainActor
@Observable
final class TracksidePairingLinks {
    static let shared = TracksidePairingLinks()

    struct Link: Identifiable {
        let id = UUID()
        let payload: PairingPayload
    }

    var pending: Link?

    /// Takes a pairing link. False for any other link, or one that can't pair, such as an
    /// expired one.
    @discardableResult
    func open(_ url: URL) -> Bool {
        guard let payload = try? PairingPayload(url: url) else { return false }
        pending = Link(payload: payload)
        return true
    }
}

extension View {
    /// Offers to pair with the server a `trackside://pair` link names, wherever the app is.
    /// The link itself is taken by ``TracksidePairingLinks/open(_:)`` where the app follows
    /// its links.
    func presentsPairingLinks(music: YourMusic) -> some View {
        modifier(PairingLinkPresenter(music: music))
    }
}

private struct PairingLinkPresenter: ViewModifier {
    let music: YourMusic
    private var links = TracksidePairingLinks.shared

    init(music: YourMusic) {
        self.music = music
    }

    func body(content: Content) -> some View {
        @Bindable var links = links
        content
            .sheet(item: $links.pending) { link in
                ServerForm(pairing: link.payload)
                    .environment(music)
            }
    }
}

// MARK: - Scanning a pairing code

#if os(iOS)
/// The camera, looking for a Trackside pairing code: the first one found pairs at once.
private struct PairingScannerSheet: View {
    var found: (PairingPayload) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Group {
                switch access {
                case .authorized where DataScannerViewController.isSupported:
                    PairingScanner(found: found, problem: $problem)
                        .ignoresSafeArea()
                        .overlay(alignment: .bottom) {
                            if let problem {
                                Text(problem)
                                    .font(.subheadline)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .glassEffect(.regular, in: .capsule)
                                    .padding(.bottom, 32)
                                    .padding(.horizontal, 16)
                                    .transition(.opacity)
                            }
                        }
                        .animation(SettingsMotion.fade, value: problem)
                case .authorized:
                    ContentUnavailableView("Can’t Scan on This iPhone", systemImage: "camera", description: Text("Enter the code from your server’s dashboard instead."))
                case .notDetermined:
                    Color.clear
                        .task {
                            _ = await AVCaptureDevice.requestAccess(for: .video)
                            access = AVCaptureDevice.authorizationStatus(for: .video)
                        }
                default:
                    ContentUnavailableView {
                        Label("Camera Access Is Off", systemImage: "camera")
                    } description: {
                        Text("Allow Tracks to use the camera in Settings to scan pairing codes.")
                    } actions: {
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        }
                    }
                }
            }
            .navigationTitle("Scan Pairing Code")
            .toolbarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", systemImage: "xmark") { dismiss() }
                }
            }
        }
    }
}

/// VisionKit's scanner, set to read QR codes.
private struct PairingScanner: UIViewControllerRepresentable {
    var found: (PairingPayload) -> Void
    @Binding var problem: String?

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        context.coordinator.scanner = scanner
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        context.coordinator.parent = self
        if !scanner.isScanning { try? scanner.startScanning() }
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    /// The scanner's delegate. VisionKit calls it on the main thread; its method is
    /// nonisolated so it fits the protocol however VisionKit declares it, reads only the
    /// codes' text there, and handles them on the main actor.
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var parent: PairingScanner
        weak var scanner: DataScannerViewController?
        private var isDone = false

        init(parent: PairingScanner) {
            self.parent = parent
        }

        nonisolated func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            let texts: [String] = addedItems.compactMap { item in
                guard case .barcode(let barcode) = item else { return nil }
                return barcode.payloadStringValue
            }
            guard !texts.isEmpty else { return }
            MainActor.assumeIsolated { self.read(texts) }
        }

        private func read(_ texts: [String]) {
            for text in texts where !isDone {
                do {
                    let payload = try PairingPayload(text)
                    isDone = true
                    scanner?.stopScanning()
                    parent.found(payload)
                } catch PairingPayload.ParseError.expired {
                    parent.problem = String(localized: "This code has expired. Make a new one in your server’s dashboard.")
                } catch PairingPayload.ParseError.unsupportedVersion {
                    parent.problem = String(localized: "This code is from a newer server. Update Tracks to pair with it.")
                } catch {
                    parent.problem = String(localized: "That’s not a pairing code.")
                }
            }
        }
    }
}
#endif
