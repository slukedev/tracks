import AppIntents

/// Tracks' intents in Spotlight and Siri, with the phrases that start them.
struct TracksShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        // Play's: "Play music in Tracks" and the rest. See PlayInTracksIntent. Each
        // choice by name ("Play Discover in Tracks") comes from the last one's parameter, so
        // the fixed phrases don't repeat those.
        AppShortcut(
            intent: PlayInTracksIntent(choice: .station),
            phrases: [
                "Play music in \(.applicationName)",
                "Play \(.applicationName)",
                "Play \(.applicationName) Radio",
                "Play my station in \(.applicationName)",
            ],
            shortTitle: "Tracks Radio",
            systemImageName: "dot.radiowaves.left.and.right"
        )
        AppShortcut(
            intent: PlayInTracksIntent(choice: .forYou),
            phrases: [
                "Play something I like in \(.applicationName)",
            ],
            shortTitle: "My Mix",
            systemImageName: "sun.max"
        )
        // Ten is the most an app can have, and this makes ten. Discover has no shortcut of its
        // own: "Play Discover in Tracks" comes from Play a Mix's parameter below. Songs, albums
        // and artists by name need no shortcut: Siri asks Play Audio's queries for them.
        AppShortcut(
            intent: TopArtistIntent(),
            phrases: [
                "Who's my top artist in \(.applicationName)",
                "Who have I listened to most in \(.applicationName)",
                "My top artist \(\.$period) in \(.applicationName)",
                "Who's my top artist \(\.$period) in \(.applicationName)",
            ],
            shortTitle: "Top Artist",
            systemImageName: "music.mic"
        )
        AppShortcut(
            intent: SearchInTracksIntent(),
            phrases: [
                "Search \(.applicationName)",
                "Search in \(.applicationName)",
            ],
            shortTitle: "Search",
            systemImageName: "magnifyingglass"
        )
        AppShortcut(
            intent: PlayInTracksIntent(),
            phrases: [
                "Play \(\.$choice) in \(.applicationName)",
                "Play \(\.$choice) on \(.applicationName)",
            ],
            shortTitle: "Play a Mix",
            systemImageName: "play.circle"
        )
        AppShortcut(
            intent: PlayPlaylistIntent(),
            phrases: [
                "Play \(\.$playlist) in \(.applicationName)",
                "Play my \(\.$playlist) playlist in \(.applicationName)",
            ],
            shortTitle: "Play Playlist",
            systemImageName: "music.note.list"
        )
        AppShortcut(
            intent: PlayMusicIntent(),
            phrases: [
                "Play a song in \(.applicationName)",
                "Play an album in \(.applicationName)",
                "Play an artist in \(.applicationName)",
                "Play a playlist in \(.applicationName)",
                "Find something to play in \(.applicationName)",
            ],
            shortTitle: "Play Music",
            systemImageName: "music.note"
        )
        AppShortcut(
            intent: ChooseSourceIntent(),
            phrases: [
                "Show \(\.$source) in \(.applicationName)",
                "Use \(\.$source) in \(.applicationName)",
                "Choose a source in \(.applicationName)",
            ],
            shortTitle: "Choose Source",
            systemImageName: "line.3.horizontal.decrease"
        )
        AppShortcut(
            intent: PlayOnDeviceIntent(),
            phrases: [
                "Play on \(\.$device) with \(.applicationName)",
                "Move the music to \(\.$device) with \(.applicationName)",
            ],
            shortTitle: "Play on Device",
            systemImageName: "laptopcomputer.and.iphone"
        )
        AppShortcut(
            intent: PlayHereIntent(),
            phrases: [
                "Bring the music here with \(.applicationName)",
                "Play it here with \(.applicationName)",
            ],
            shortTitle: "Bring Music Here",
            systemImageName: "arrow.down.forward.and.arrow.up.backward"
        )
    }
}
