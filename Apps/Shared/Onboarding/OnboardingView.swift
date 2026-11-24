import SwiftUI
import MusicKit
import AuthenticationServices
import TracksCore

struct OnboardingView: View {
    @Bindable var onboarding: Onboarding
    var cloud: CloudSyncSettings?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            switch onboarding.page {
            case .welcome:
                OnboardingWelcome {
                    withAnimation(SettingsMotion.row(reduceMotion: reduceMotion)) { onboarding.page = .setup }
                }
                .transition(.opacity)
            case .setup:
                OnboardingSetup(onboarding: onboarding)
                    .transition(.opacity)
            }
        }
        #if os(macOS)
        .frame(width: 520)
        .frame(minHeight: 420)
        #endif
    }
}

// MARK: - Welcome

private struct OnboardingWelcome: View {
    var proceed: () -> Void

    var body: some View {
        #if os(macOS)
        VStack(spacing: 32) {
            content
            OnboardingPrimaryButton("Continue", action: proceed)
        }
        .padding(.horizontal, 40)
        .padding(.top, 40)
        .padding(.bottom, 24)
        #else
        ScrollView {
            content
                .padding(.horizontal, 32)
                .padding(.top, 56)
                .padding(.bottom, 24)
                .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaBar(edge: .bottom) {
            OnboardingPrimaryButton("Continue", action: proceed)
        }
        #endif
    }

    private var content: some View {
        VStack(spacing: 36) {
            VStack(spacing: 16) {
                SettingsIconTile(systemImage: "music.note", color: .accentColor, baseSide: 72)
                Text("Welcome to Tracks")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
            }
            VStack(alignment: .leading, spacing: 24) {
                feature("Every Song You Play", detail: "Radio included, kept in your history.", systemImage: "clock.arrow.circlepath")
                feature("Mixes and Stations", detail: "Made from what you play, hour by hour.", systemImage: "square.stack")
                feature("On All Your Devices", detail: "Your history stays in your private iCloud.", systemImage: "icloud")
            }
        }
    }

    private func feature(_ title: LocalizedStringKey, detail: LocalizedStringKey, systemImage: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            Image(systemName: systemImage)
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 36)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Setup

private struct OnboardingSetup: View {
    var onboarding: Onboarding

    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var yourMusic
    @Environment(\.openURL) private var openURL
    @Environment(\.webAuthenticationSession) private var webAuthentication
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isRequestingAccess = false
    @State private var addsServer = false

    private var demo: OnboardingDemo? { onboarding.demo }

    private var access: MusicAccessStatus {
        demo?.access ?? MusicAccessStatus(model.musicAuthorization)
    }

    private var canPlayCatalog: Bool? {
        if let demo { return demo.canPlayCatalog }
        return model.playFeed.subscription?.canPlayCatalogContent
    }

    private var lastFMUsername: String? {
        if let demo { return demo.lastFMUsername }
        if case .connected(let username) = onboarding.lastFM.state { return username }
        return nil
    }

    private var servers: [String] {
        demo?.servers ?? yourMusic.servers.servers.map(\.name)
    }

    private var offersLastFM: Bool { demo != nil || LastFMClient.configured() != nil }

    private var title: some View {
        Text(OnboardingWords.setupTitle(isReturning: onboarding.isReturning))
            .font(.largeTitle.bold())
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }

    var body: some View {
        page
            .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: onboarding.restore)
            .onAppear(perform: model.refreshMusicAuthorization)
            .sheet(isPresented: $addsServer) {
                ServerForm()
            }
    }

    #if os(macOS)
    private var page: some View {
        VStack(alignment: .leading, spacing: 20) {
            title
            OnboardingGroup {
                appleMusicRow
                if offersLastFM {
                    Divider()
                    lastFMRow
                }
                Divider()
                yourMusicRow
            }
            OnboardingGroup {
                HistoryRestoreRow(restore: onboarding.restore)
            }
            OnboardingPrimaryButton("Start Listening", action: onboarding.finish)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
        }
        .padding(.horizontal, 32)
        .padding(.top, 32)
        .padding(.bottom, 20)
    }
    #else
    private var page: some View {
        Form {
            Section {
                title
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 24, leading: 4, bottom: 4, trailing: 0))
            }
            Section {
                appleMusicRow
                if offersLastFM { lastFMRow }
                yourMusicRow
            }
            Section {
                HistoryRestoreRow(restore: onboarding.restore)
            }
        }
        .formStyle(.grouped)
        .safeAreaBar(edge: .bottom) {
            OnboardingPrimaryButton("Start Listening", action: onboarding.finish)
        }
    }
    #endif

    // MARK: Rows

    private var appleMusicRow: some View {
        OnboardingRow(
            "Apple Music",
            detail: OnboardingWords.appleMusic(access, canPlayCatalog: canPlayCatalog),
            systemImage: "music.note",
            tint: .pink,
            isDone: access == .allowed
        ) {
            switch access {
            case .notAsked:
                Button("Allow") {
                    guard demo == nil else { return }
                    isRequestingAccess = true
                    Task {
                        await model.requestMusicAccess()
                        isRequestingAccess = false
                    }
                }
                .disabled(isRequestingAccess)
            case .denied:
                Button(openSettingsTitle) {
                    guard demo == nil, let settingsURL else { return }
                    openURL(settingsURL)
                }
            case .allowed, .restricted:
                EmptyView()
            }
        }
    }

    private var lastFMRow: some View {
        let state = onboarding.lastFM.state
        let failure: String? = if case .failed(let message) = state { message } else { nil }
        return OnboardingRow(
            "Last.fm",
            detail: OnboardingWords.lastFM(
                connectedAs: lastFMUsername,
                isWaiting: state == .waitingForBrowser,
                failure: failure
            ),
            systemImage: "waveform",
            tint: .red,
            isDone: lastFMUsername != nil
        ) {
            if state == .waitingForBrowser {
                ProgressView()
                    .controlSize(.small)
            } else {
                Button(failure == nil ? "Connect" : "Try Again", action: connectLastFM)
            }
        }
    }

    private var yourMusicRow: some View {
        OnboardingRow(
            "Server",
            detail: OnboardingWords.yourMusic(servers: servers),
            systemImage: "server.rack",
            tint: .indigo,
            isDone: !servers.isEmpty
        ) {
            Button("Connect") {
                guard demo == nil else { return }
                addsServer = true
            }
        }
    }

    // MARK: Actions

    private func connectLastFM() {
        guard demo == nil else { return }
        #if os(iOS)
        onboarding.lastFM.connect(waitsForPage: true) { url in
            _ = try? await webAuthentication.authenticate(
                using: url,
                callbackURLScheme: "tracks",
                preferredBrowserSession: .shared
            )
        }
        #else
        onboarding.lastFM.connect { url in openURL(url) }
        #endif
    }

    #if os(iOS)
    private var openSettingsTitle: LocalizedStringKey { "Open Settings" }
    private var settingsURL: URL? { URL(string: UIApplication.openSettingsURLString) }
    #else
    private var openSettingsTitle: LocalizedStringKey { "Open System Settings" }
    private var settingsURL: URL? {
        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Media")
    }
    #endif
}

struct HistoryRestoreRow: View {
    var restore: HistoryRestore

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SettingsIconTile(systemImage: symbol, color: .blue)
            VStack(alignment: .leading, spacing: 6) {
                Text(restore.title)
                    .contentTransition(.numericText())
                if let fraction = restore.fraction, !restore.isSettled {
                    ProgressView(value: fraction)
                        .animation(SettingsMotion.value, value: fraction)
                }
                if let detail = restore.detail {
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .contentTransition(.numericText())
                }
            }
            if restore == .looking {
                Spacer()
                ProgressView()
                    .controlSize(.small)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        switch restore {
        case .finished: "checkmark.icloud"
        case .unavailable: "exclamationmark.icloud"
        case .nothingToRestore: "icloud"
        default: "arrow.down.circle.dotted"
        }
    }
}

private struct OnboardingRow<Action: View>: View {
    var title: LocalizedStringKey
    var detail: String
    var systemImage: String
    var tint: Color
    var isDone: Bool
    @ViewBuilder var action: Action

    init(
        _ title: LocalizedStringKey,
        detail: String,
        systemImage: String,
        tint: Color,
        isDone: Bool,
        @ViewBuilder action: () -> Action
    ) {
        self.title = title
        self.detail = detail
        self.systemImage = systemImage
        self.tint = tint
        self.isDone = isDone
        self.action = action()
    }

    var body: some View {
        HStack(spacing: 12) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .contentTransition(.opacity)
                }
            } icon: {
                SettingsIconTile(systemImage: systemImage, color: tint)
            }
            Spacer(minLength: 8)
            if isDone {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.green)
                    .transition(.scale.combined(with: .opacity))
                    .accessibilityLabel("Done")
            } else {
                action
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .tint(.accentColor)
                    #if os(macOS)
                    .controlSize(.regular)
                    #else
                    .controlSize(.small)
                    #endif
            }
        }
        .animation(SettingsMotion.value, value: isDone)
    }
}

#if os(macOS)
private struct OnboardingGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.fill.quinary, in: .rect(cornerRadius: 10, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(.separator.opacity(0.6), lineWidth: 0.5)
        }
    }
}
#endif

private struct OnboardingPrimaryButton: View {
    var title: LocalizedStringKey
    var action: () -> Void

    init(_ title: LocalizedStringKey, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.glassProminent)
        .buttonBorderShape(.capsule)
        #if os(iOS)
        .controlSize(.extraLarge)
        #else
        .controlSize(.large)
        #endif
        .keyboardShortcut(.defaultAction)
        .padding(.horizontal, horizontalPadding)
        .padding(.vertical, Design.Spacing.large)
        #if os(macOS)
        .frame(maxWidth: 280)
        #endif
    }

    private var horizontalPadding: CGFloat {
        #if os(macOS)
        0
        #else
        24
        #endif
    }
}

#if DEBUG
#Preview("Welcome") {
    OnboardingWelcome {}
}
#endif
