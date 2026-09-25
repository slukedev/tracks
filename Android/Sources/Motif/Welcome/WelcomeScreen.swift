import SwiftUI

/// First launch: what Motif is on Android, and signing in to your music server.
struct WelcomeScreen: View {
    @Environment(ServerStore.self) var servers
    @State var address = ""
    @State var username = ""
    @State var password = ""
    @State var isConnecting = false
    @State var error: ConnectionError?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                IconBadge(symbol: .listening, container: .primaryContainer, content: .onPrimaryContainer, size: 72)
                    .padding(.top, 40)

                Text("You listen.\nMotif remembers.")
                    .materialType(.displaySmall)
                    .materialForeground(.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 24)
                    .accessibilityAddTraits(.isHeader)

                Text("Play the music on your own server, and see what your listening says about you: time listened, top songs and artists, streaks and highlights.")
                    .materialType(.bodyLarge)
                    .materialForeground(.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)

                form
                    .padding(.top, 32)

                Text("Works with Navidrome, Gonic, Airsonic and any server that speaks the Subsonic API. Your history stays on this phone.")
                    .materialType(.bodySmall)
                    .materialForeground(.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 32)
        }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Connect your music server")
                .materialType(.titleMedium)
                .materialForeground(.onSurface)

            TextField("Server address", text: $address)
                .addressKeyboard()
                .autocorrectionDisabled()
                #if !os(macOS)
                .textInputAutocapitalization(.never)
                #endif
            TextField("Username", text: $username)
                .textContentType(.username)
                .autocorrectionDisabled()
                #if !os(macOS)
                .textInputAutocapitalization(.never)
                #endif
            SecureField("Password", text: $password)
                .textContentType(.password)
                .onSubmit { Task { await connect() } }

            if let error {
                HStack(alignment: .top, spacing: 8) {
                    SymbolImage(.warning, size: 18, role: .error)
                    Text(error.message)
                        .materialType(.bodyMedium)
                        .materialForeground(.error)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Button {
                Task { await connect() }
            } label: {
                Text(isConnecting ? "Connecting…" : "Connect")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isConnecting || address.isEmpty || username.isEmpty || password.isEmpty)
            .padding(.top, 8)
        }
    }

    private func connect() async {
        guard !isConnecting else { return }
        isConnecting = true
        error = nil
        defer { isConnecting = false }
        do {
            try await servers.connect(address: address, username: username, password: password)
        } catch let failure {
            error = failure
        }
    }
}
