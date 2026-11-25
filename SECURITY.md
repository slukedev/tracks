# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub's private vulnerability reporting by opening the repository's Security tab and clicking "Report a vulnerability", or by going to [the advisory form](https://github.com/slukedev/tracks/security/advisories/new).

Do not open a public issue, pull request or discussion for a vulnerability.

Please include what you found, how to reproduce it, the platform and OS version, and the commit or release you tested. You can expect an acknowledgement within a week. Tracks is currently maintained by just one person, so a fix may take longer, but you'll be kept informed, and credited in the advisory if you'd like to be.

## Supported versions

Security fixes are made on `main` and included in the next release. Older releases are not patched.

## Scope

Tracks has no account server. What there is to attack is the app, its extensions, the App Clip and the Mac watchdog, the data they keep on the device and in iCloud, and two ways devices talk to each other:

- **Your Devices.** Each device listens on the local network with Bonjour (`_tracks._tcp`), and only accepts TLS with a pre-shared key that the person's devices share through iCloud key-value storage. A way for another device to connect, read what's playing, or send commands without that key is in scope.
- **SharePlay's relay.** Passengers joining by code exchange messages through a Supabase Realtime channel, sealed with AES-GCM under a key derived from the code. A way to read or forge those messages without the code is in scope. The relay's host and publishable key ship inside the app and can be extracted, so the project relies on Supabase's own limits, not on keeping them secret.

The self-hosted Trackside server has its own section below.

The Last.fm session key never expires and is enough by itself to scrobble as the user, so Tracks stores it in the Keychain and not in `UserDefaults` or the App Group container. Any way of getting at it is in scope, whether through another app, an extension that shouldn't have it, a log or crash report, or a request to anywhere other than Last.fm.

The Last.fm API key and shared secret identify the application. They're read from the gitignored `Config/Secrets.xcconfig`, and the tracked `Config/Tracks.xcconfig` leaves them empty. A commit, workflow or build step that would publish them is in scope. Any Last.fm desktop client has to ship its secret inside the app, where it can be extracted, and that alone is a limitation of Last.fm's authentication that Tracks can't fix.

Listening history is stored with SwiftData in an App Group container that only the Tracks widgets share, and mirrored to the user's private CloudKit database. Report anything that lets another app or another iCloud user read it, or that sends it anywhere except the user's iCloud and, if connected, Last.fm.

Passwords and API keys for the person's own music servers and the Lidarr API key are stored in the Keychain. Any way of getting at them, or of sending them anywhere but the server they belong to, is in scope. So is a Subsonic token or Trackside media token reaching a log, an image cache, Now Playing or any other address handed to the system: cover addresses carry no credentials and are signed only as they're fetched, logs redact every address, and redirects are followed only to the same server. Credentials only cross plain HTTP to nearby addresses (private, link-local, loopback, 100.64/10, `.local`, `home.arpa` and the like).

On Android the same credentials are sealed with a Keystore key. Each server's certificates are pinned in that server's own client, never in the clients Apple Music, Last.fm and the web use, so a pin reaching another host is in scope. So is a `trackside://pair` link that replaces a server already paired, an API key sent over plain HTTP to anything but the device itself, a Subsonic password token sent over plain HTTP on a network other than the one the server was added on while it has an https address, and another installed app browsing history or queueing songs through the exported media service without being a trusted controller. Release builds without the upload key fail unless the build opts in to the debug key.

With Trackside Cloud as the sync backend, the history, settings and hidden lists go to the person's own Trackside server instead of iCloud. Anything that sends them, or the device key, to an address that hasn't proven it is that server is in scope. The Your Devices key is never sent there.

The Mac app sends Apple events to Music under scripting-target entitlements limited to specific access groups. A way to make Tracks control another app, or run a script, beyond those groups is in scope.

### The Trackside server

Trackside, the self-hosted music server, lives in [slukedev/tracks-companion](https://github.com/slukedev/tracks-companion). Report problems in the server there, through [its advisory form](https://github.com/slukedev/tracks-companion/security/advisories/new). It is in scope: the `trackside` binary, the container images published at `ghcr.io/slukedev/trackside`, the release binaries and `.deb`, `.rpm` and Homebrew packages, and the systemd unit and NAS templates in its `packaging/` folder. It listens on port 4747 by default, where it serves the web dashboard, the native API for Tracks (`/api/v1`) and the Subsonic API (`/rest`), with optional built-in HTTPS.

In scope, for example:

- Signing in, or using any API, without valid credentials, or claiming a new server without its setup token.
- One account reading or changing another's listens, playlists or favourites, or reaching a library it wasn't given.
- A device key, API key, app password or media token reaching the admin API, or outliving its revocation.
- Tracks sending a device key, password, pairing code or QR secret to an address that hasn't proven it is the server (the `/api/v1/ping` endpoint proof), or over plain HTTP to an address that isn't on the local network or tailnet.
- Cross-site request forgery, script injection or framing in the dashboard, or a way past its re-authentication for sensitive changes.
- A request that reads or writes files outside the server's data folder and libraries, or writes to a library that isn't marked writable.
- Passwords, keys, tokens or the secret key ending up in a log, an error response or a backup someone shouldn't be able to download.
- An image or package that runs the server as root, or a release that isn't signed as its `docs/ops/install.md` describes.

Out of scope for the server: what an admin can already do with their own server, attacks that need shell access to the host or write access to its data folder, and the security of a reverse proxy or VPN you put in front of it. A proxy has to pass `/api/v1/ping` through with its query string; one that doesn't makes that address unusable, which is expected, not a vulnerability. The server's own threat model is in [its `docs/architecture.md`, section 14](https://github.com/slukedev/tracks-companion/blob/main/docs/architecture.md#14-security-model-and-threat-model).

### Out of scope

- Vulnerabilities in Apple's frameworks, Apple Music, iCloud or Last.fm themselves. Please report those to Apple or Last.fm.
- Attacks that need a jailbroken or already-compromised device, or physical access to an unlocked one.
- Missing hardening with no demonstrated impact.

## Keeping your own credentials safe

If you build Tracks yourself, keep your Last.fm key and secret, and the relay's host and key, in `Config/Secrets.xcconfig`, which is gitignored, and never paste them into an issue, a screenshot or a log. If a secret does leak, register a new Last.fm API application and stop using the old one.
