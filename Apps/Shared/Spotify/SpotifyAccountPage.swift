#if SPOTIFY
import SwiftUI
import TracksCore

/// Spotify's page in Settings ▸ Sources: signing in, the account and its quality, and signing
/// out. Pushed as a page on iPhone; on the Mac, sections in the Sources pane's form.
struct SpotifyAccountPage: View {
    @AppStorage(CaptureSettings.scrobblesSpotifyKey, store: CaptureSettings.sharedDefaults)
    private var scrobblesSpotify = false
    let service: SpotifyService
    @State private var confirmsSignOut = false
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        #if os(macOS)
        macSections
        #else
        Form {
            hero
            switch service.phase {
            case .connected:
                connected
            case .failed(let problem):
                failure(problem)
            case .signedOut, .signingIn, .checking:
                signIn
            }
        }
        .settingsPage("Spotify")
        .sensoryFeedback(Design.Haptic.done, trigger: service.phase == .connected) { _, connected in connected }
        .confirmationDialog("Sign Out of Spotify?", isPresented: $confirmsSignOut, titleVisibility: .visible) {
            Button("Sign Out", role: .destructive) { service.signOut() }
        } message: {
            Text("Your plays stay in History. Spotify playlists leave your library.")
        }
        #endif
    }

    // MARK: iPhone

    #if os(iOS)
    private var hero: some View {
        Section {
            VStack(spacing: Design.Spacing.small) {
                SpotifyMark(size: 72)
                    .accessibilityHidden(true)
                Text("Spotify")
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                if service.phase == .connected, let name = service.accountName {
                    Text(verbatim: name)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Design.Spacing.small)
            .listRowBackground(Color.clear)
        }
    }

    @ViewBuilder
    private var signIn: some View {
        Section {
            if service.phase == .checking {
                HStack(spacing: Design.Spacing.small) {
                    Text("Checking…")
                        .foregroundStyle(.secondary)
                    ProgressView()
                }
            } else {
                signInButton
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
            }
        } footer: {
            Text("Needs Premium")
                .frame(maxWidth: .infinity)
        }
    }

    private var signInButton: some View {
        let ink: Color = colorScheme == .dark ? .white : .black
        return Button {
            service.signIn()
        } label: {
            Group {
                if service.phase == .signingIn {
                    Text("Signing In…")
                } else {
                    Text("Sign In with Spotify")
                }
            }
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentTransition(.opacity)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .tint(ink)
        .foregroundStyle(colorScheme == .dark ? Color.black : Color.white)
        .disabled(service.phase == .signingIn)
    }

    @ViewBuilder
    private var connected: some View {
        Section("Quality") {
            qualityPicker("Wi-Fi", selection: Binding(get: { service.wiFiQuality }, set: { service.wiFiQuality = $0 }))
            qualityPicker("Cellular", selection: Binding(get: { service.cellularQuality }, set: { service.cellularQuality = $0 }))
        }
        Section {
            Toggle("Scrobble to Last.fm", isOn: $scrobblesSpotify)
        }
        Section {
            Button("Sign Out…", role: .destructive) { confirmsSignOut = true }
        }
    }

    private func qualityPicker(_ title: LocalizedStringKey, selection: Binding<SpotifyQuality>) -> some View {
        Picker(title, selection: selection) {
            ForEach(SpotifyQuality.allCases) { quality in
                Text(quality.title).tag(quality)
            }
        }
    }

    @ViewBuilder
    private func failure(_ problem: SpotifyProblem) -> some View {
        Section {
            Text(problem.message)
            switch problem {
            case .premium:
                Button("Done") { service.signOut() }
            case .refused:
                Button("Try Again") { Task { await service.checkAudio() } }
                Button("Remove", role: .destructive) { service.signOut() }
            case .network:
                Button("Try Again", action: retry)
            case .signIn:
                Button("Sign In with Spotify") { service.signIn() }
            }
        }
    }
    #endif

    // MARK: Mac

    #if os(macOS)
    @ViewBuilder
    private var macSections: some View {
        Section {
            LabeledContent {
                macAction
            } label: {
                Label {
                    Text("Spotify")
                    if service.phase == .connected, let name = service.accountName {
                        Text(verbatim: name)
                    }
                } icon: {
                    SpotifyMark(size: 20)
                        .accessibilityHidden(true)
                }
            }
            if service.phase == .connected {
                Picker("Quality", selection: Binding(get: { service.wiFiQuality }, set: { service.wiFiQuality = $0 })) {
                    ForEach(SpotifyQuality.allCases) { quality in
                        Text(quality.title).tag(quality)
                    }
                }
            }
        } header: {
            Text("Spotify")
        } footer: {
            switch service.phase {
            case .failed(let problem):
                Text(problem.message)
                    .foregroundStyle(.secondary)
            case .signedOut, .signingIn:
                Text("Needs Premium")
                    .foregroundStyle(.secondary)
            case .checking, .connected:
                EmptyView()
            }
        }
        .confirmationDialog("Sign Out of Spotify?", isPresented: $confirmsSignOut) {
            Button("Sign Out", role: .destructive) { service.signOut() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your plays stay in History. Spotify playlists leave your library.")
        }
    }

    @ViewBuilder
    private var macAction: some View {
        switch service.phase {
        case .connected:
            Button("Sign Out…") { confirmsSignOut = true }
        case .checking:
            ProgressView()
                .controlSize(.small)
        case .signingIn:
            Button("Signing In…") {}
                .disabled(true)
        case .signedOut:
            Button("Sign In with Spotify") { service.signIn() }
        case .failed(.premium):
            Button("Done") { service.signOut() }
        case .failed(.refused):
            HStack {
                Button("Remove") { service.signOut() }
                Button("Try Again") { Task { await service.checkAudio() } }
            }
        case .failed(.network):
            Button("Try Again", action: retry)
        case .failed(.signIn):
            Button("Sign In with Spotify") { service.signIn() }
        }
    }
    #endif

    private func retry() {
        if service.account != nil {
            Task { await service.checkAudio() }
        } else {
            service.signIn()
        }
    }
}
#endif
