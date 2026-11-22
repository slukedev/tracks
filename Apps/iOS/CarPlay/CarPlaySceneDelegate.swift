import UIKit
import CarPlay
import MusicKit
import TracksCore
import TracksMusic

/// Tracks in the car: four tabs made for a glance and one tap, as Music has them, built from
/// CarPlay's own templates so they look and work like the rest of the car.
///
/// - Listen Now leads with one thing to play (where you left off, Tracks Radio, or Driving),
///   then a few cards, what you played lately, your mixes, and your artists' new releases.
/// - Radio leads with Tracks Radio, then every mood and your genres as tiles, each a tap from
///   playing, then Apple's live stations.
/// - Library opens Playlists, Artists, Albums, Songs and Downloaded as pages; an album,
///   playlist or artist has its cover, Play and Shuffle at the top, as Music's do.
/// - Search is Siri first, then your recent searches, and the keyboard where the car allows.
///
/// Everything plays through the same player as the phone, so every song is kept. The files
/// beside this one build each tab, Now Playing, and the pictures.
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate, CPNowPlayingTemplateObserver, CPTabBarTemplateDelegate, CPInterfaceControllerDelegate, CPSessionConfigurationDelegate {
    /// The car's interface while it's connected, and nil from the moment it goes: nothing
    /// touches it after that.
    var interface: CPInterfaceController?
    /// This connection's moves, from push to sheet: see `CarMover`.
    var mover: CarMover?
    /// Counts connections, so work begun for one car never lands on the next.
    private(set) var connection = 0
    /// Tells when the car limits how long lists may be, as some do while driving.
    private var session: CPSessionConfiguration?
    /// The car whose scene owns the shared Now Playing screen. A car reconnecting quickly can
    /// connect its new scene before the old one has gone.
    private static weak var activeCar: CarPlaySceneDelegate?
    var scale: CGFloat = 2
    /// How many covers a shelf shows: one line's worth on this car's screen. The car keeps room
    /// for every cover it's given even when it draws only one line, which left a blank band
    /// under each shelf; the rest are a tap away on the shelf's page. A tile and its gap take
    /// 82 points, measured on a 720-point screen that fits seven beside the car's own side bar
    /// and the list's page arrows.
    var shelfLength: Int {
        // The window CarPlay gives a template app has no size, nor does its screen; only the
        // list of screens has the car's.
        let car = UIScreen.screens.first { $0.traitCollection.userInterfaceIdiom == .carPlay }
        let width = car?.bounds.width ?? 0
        return min(Int(CPMaximumNumberOfGridImages), max(4, Int((width - 140) / 82)))
    }
    let model = AppModel.shared
    private var watch: Task<Void, Never>?
    private var connecting: [Task<Void, Never>] = []
    private var refresh: Task<Void, Never>?
    /// The source Search was last drawn for.
    var searchBuiltFor: MusicSource?
    /// Whether the rebuild waiting to run was asked for at once.
    private var isRebuildingNow = false
    /// Each tab builds one at a time, and changes that land while one builds are drawn once it's
    /// done. Cancelling a build for each change left Listen Now spinning while the car
    /// connected, since the history, the mixes and Apple Music's shelves all change then.
    /// A build that hangs (a request on a connection that's gone) is cancelled and tried once
    /// more after 20 seconds, rather than holding its tab for good.
    private lazy var radioRefresh = SerialRefresh(onTimeout: Self.refreshTookTooLong("Radio")) { [weak self] in await self?.buildRadio() }
    private lazy var listenRefresh = SerialRefresh(onTimeout: Self.refreshTookTooLong("Listen Now")) { [weak self] in await self?.buildListenNow() }
    private lazy var libraryRefresh = SerialRefresh(onTimeout: Self.refreshTookTooLong("Library")) { [weak self] in await self?.buildLibrary() }
    /// Watches the settings the car shows (Tracks Radio on or off, Autoplay), which live in
    /// UserDefaults, where nothing observable says they changed.
    private var settingsObserver: NSObjectProtocol?
    private var shownSettings: (radio: Bool, autoplay: Bool)?
    /// A look at the settings, waiting out a burst of changes.
    private var settingsCheck: Task<Void, Never>?
    private var showsAccessProblem = false
    /// What each tab was last built from, so a tab only reloads when something on it changed.
    /// A reload while someone's scrolling moves the list under their finger.
    private var builtFrom: [ObjectIdentifier: Int] = [:]
    /// What the Library tab was last built for: the source, and Apple Music access or the size
    /// of your own library.
    private var libraryBuiltFor: String?
    /// Up Next while it's open, to keep it current as the queue moves.
    var queue: CPListTemplate?
    var isQueueShown = false
    var nowPlayingWatch: Task<Void, Never>?
    /// Where Tracks Radio stood when Radio was last built, so the tab is redrawn when it changes.
    var radioRowState: String?
    /// Headers on pages still open, so their Play follows the music.
    var headers: [(header: WeakHeader, playback: HeaderPlayback)] = []
    /// What each tab's header was last made from, so it's only replaced when that changes.
    var headerBuiltFrom: [ObjectIdentifier: String] = [:]
    /// The keyboard's search while it's open.
    var keyboardSearch: KeyboardSearch?
    /// SharePlay's page while it's open, what keeps it current, and its code as last drawn.
    var sharePlayPage: CPListTemplate?
    var sharePlayWatch: Task<Void, Never>?
    var sharePlayCode: (invite: SharePlayInvite, image: UIImage)?
    /// Your genres as last worked out, and what from, since it walks the whole history.
    var genres: (key: String, items: [GenreTileItem], at: ContinuousClock.Instant)?
    /// Apple Music access as last read: nil until the car's scene has settled, since it's
    /// never read in a scene callback (see `refreshMusicAccess`).
    private(set) var musicAccess: MusicAuthorization.Status?
    /// Rows that show the playing marker, and how each knows it's the one playing, so the
    /// marker follows the music on pages already open rather than staying where it was drawn.
    private var marked: [(row: WeakRow, isOn: () -> Bool)] = []
    /// Counts taps that play or open something, so only the latest one moves the car's screen.
    private var tapID = 0
    /// What Now Playing's buttons were last drawn from, so they're only sent again on a change.
    var shownButtons: Int?
    /// What Up Next was last drawn from, for the list open now.
    var shownQueue: Int?

    lazy var listenNow = tab(String(localized: "Listen Now"), symbol: "play.circle.fill")
    lazy var radio = tab(String(localized: "Radio"), symbol: "dot.radiowaves.left.and.right")
    lazy var library = tab(String(localized: "Library"), symbol: "square.stack.fill")
    lazy var search = tab(String(localized: "Search"), symbol: "magnifyingglass")

    // MARK: - Connecting

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        // Before anything can reach the player: the car's connection is a scene change, and a
        // player call inside one can hold the main thread until the watchdog ends the app.
        PlayerQuiet.hold()
        // Connected again without a disconnect in between: nothing of the last car carries over.
        if interface != nil { disconnect() }
        connection &+= 1
        interface = interfaceController
        mover = Self.mover(for: interfaceController)
        Self.activeCar = self
        interfaceController.delegate = self
        session = CPSessionConfiguration(delegate: self)
        scale = interfaceController.carTraitCollection.displayScale
        CarPlayImages.style = scene.contentStyle == .light ? .light : .dark
        configureNowPlaying()
        // Siri, where the car's music apps put it: at the top of Search, or of Library in a car
        // with room for only three tabs.
        let siri = CPAssistantCellConfiguration(position: .top, visibility: .always, assistantAction: .playMedia)
        let hasSearch = CPTabBarTemplate.maximumTabCount >= 4
        if hasSearch {
            search.assistantCellConfiguration = siri
            refreshSearch()
        } else {
            library.assistantCellConfiguration = siri
        }
        let tabs = CPTabBarTemplate(templates: hasSearch ? [listenNow, radio, library, search] : [listenNow, radio, library])
        tabs.delegate = self
        interfaceController.setRootTemplate(tabs, animated: false) { _, error in
            if let error { Self.log.error("CarPlay couldn't show its tabs: \(error.localizedDescription, privacy: .public)") }
        }
        // Radio's moods are drawn here, not fetched, so it's never a blank tab while the rest loads.
        refreshRadioList()
        // Apple Music access, once the car has finished connecting. Held back from now even
        // when the car started Tracks and nothing has asked the player to wait yet.
        PlayerQuiet.followScenes()
        connecting.append(Task { [weak self] in
            await PlayerQuiet.settled()
            guard !Task.isCancelled else { return }
            self?.settingsMayHaveChanged()
        })

        // The car can start Tracks with no phone window, so nothing else has started capture,
        // or listened for SharePlay.
        Task { await model.startCapture() }
        SharePlayController.shared.start(model: model)
        connecting.append(Task { [weak self, model] in
            await model.prepareForPlaying()
            guard !Task.isCancelled else { return }
            self?.rebuild(after: .zero)
            await model.playFeed.loadAppleMusic()
            await model.playFeed.loadFromYourArtists()
            guard !Task.isCancelled else { return }
            self?.rebuild()
        })
        shownSettings = (PlayPreferences.isTracksRadioOn, PlayPreferences.autoplay)
        showsAccessProblem = needsAppleMusicAccess
        // Only our own defaults, and never on the main queue. With `object: nil` this heard
        // Apple's private iTunesCloud defaults too, and a `.main` observer makes the posting
        // thread wait for the main thread. iTunesCloud posts from the queue that MusicKit's
        // first player call waits on, so the two waited on each other until the scene-update
        // watchdog killed the app as the car connected (0x8BADF00D, Nov 2026).
        settingsObserver = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: UserDefaults.standard, queue: nil) { [weak self] _ in
            Task { @MainActor in self?.settingsMayHaveChanged() }
        }
        watch = Task { [weak self] in
            guard let model = self?.model else { return }
            let (feed, library, yourMusic, player) = (model.playFeed, model.library, model.yourMusic, model.player)
            let changes = Observations {
                ContentKey(
                    revision: library.revision,
                    mixes: feed.mixes.all.map(\.id),
                    shelves: (feed.recentlyPlayed + feed.liveStations + feed.newReleases).map(\.id),
                    discover: feed.discover.count,
                    source: model.musicSource,
                    yourMusic: yourMusic.index.tracks.count,
                    yourPlaylists: yourMusic.playlists.all.count,
                    downloads: yourMusic.downloads.items.count,
                    playing: player.context?.title,
                    waiting: player.waitingSession?.tracks.first?.id,
                    captures: model.library.history.captures.count >= 20,
                    isOnline: yourMusic.network.isOnline
                )
            }
            for await _ in changes {
                self?.rebuild()
            }
        }
    }

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        PlayerQuiet.hold()
        guard interface === interfaceController else { return }
        disconnect()
    }

    /// Stops everything begun for the car that's gone, and forgets what it showed. Its tabs are
    /// made again, so a car that connects next starts from nothing of the last one's.
    private func disconnect() {
        let wasActive = Self.activeCar === self
        if wasActive { Self.activeCar = nil }
        connecting.forEach { $0.cancel() }
        connecting = []
        watch?.cancel()
        watch = nil
        refresh?.cancel()
        refresh = nil
        isRebuildingNow = false
        listenRefresh.cancel()
        libraryRefresh.cancel()
        radioRefresh.cancel()
        if let settingsObserver { NotificationCenter.default.removeObserver(settingsObserver) }
        settingsObserver = nil
        settingsCheck?.cancel()
        settingsCheck = nil
        nowPlayingWatch?.cancel()
        nowPlayingWatch = nil
        keyboardSearch = nil
        CPNowPlayingTemplate.shared.remove(self)
        if sharePlayPage != nil {
            sharePlayWatch?.cancel()
            sharePlayWatch = nil
            sharePlayPage = nil
            // The code belongs to whichever car shows it: not hidden from a newer one.
            if wasActive || Self.activeCar == nil { SharePlayController.shared.hideCode(on: .car) }
        }
        sharePlayCode = nil
        mover?.reset()
        mover = nil
        session = nil
        interface = nil
        queue = nil
        isQueueShown = false
        shownQueue = nil
        shownButtons = nil
        marked = []
        headers = []
        headerBuiltFrom = [:]
        radioRowState = nil
        builtFrom = [:]
        libraryBuiltFor = nil
        searchBuiltFor = nil
        listenNow = tab(String(localized: "Listen Now"), symbol: "play.circle.fill")
        radio = tab(String(localized: "Radio"), symbol: "dot.radiowaves.left.and.right")
        library = tab(String(localized: "Library"), symbol: "square.stack.fill")
        search = tab(String(localized: "Search"), symbol: "magnifyingglass")
    }

    /// Whether this car's scene owns the shared Now Playing screen.
    var isActiveCar: Bool { interface != nil && Self.activeCar === self }

    /// Some cars cut lists short while driving and give them back once parked: the tabs are
    /// drawn again to the car's limits now. The keyboard comes and goes the same way.
    func sessionConfiguration(_ sessionConfiguration: CPSessionConfiguration, limitedUserInterfacesChanged limitedUserInterfaces: CPLimitableUserInterface) {
        guard interface != nil else { return }
        builtFrom = [:]
        libraryBuiltFor = nil
        refreshSearch()
        rebuild(after: .zero)
    }

    /// Whether the car lets people type now: many don't while moving.
    var allowsKeyboard: Bool {
        guard let session else { return false }
        return !session.limitedUserInterfaces.contains(.keyboard)
    }

    /// The car's screen turned light or dark, with the time of day or its headlights. What
    /// Tracks draws itself (stand-ins, symbols, track numbers) is drawn again for it.
    func contentStyleDidChange(_ contentStyle: UIUserInterfaceStyle) {
        let style: UIUserInterfaceStyle = contentStyle == .light ? .light : .dark
        guard interface != nil, style != CarPlayImages.style else { return }
        CarPlayImages.style = style
        builtFrom = [:]
        headerBuiltFrom = [:]
        libraryBuiltFor = nil
        searchBuiltFor = nil
        rebuild(after: .zero)
    }

    private static func refreshTookTooLong(_ tab: String) -> @MainActor () -> Void {
        { log.error("CarPlay's \(tab, privacy: .public) tab took too long to build; trying again") }
    }

    /// Search's recent searches can change on the phone at any time, and nothing says so: they're
    /// read again each time the tab is opened.
    func tabBarTemplate(_ tabBarTemplate: CPTabBarTemplate, didSelect selectedTemplate: CPTemplate) {
        guard interface != nil, selectedTemplate === search else { return }
        refreshSearch()
    }

    // MARK: - Screens coming and going

    /// SharePlay's code works while its page is on screen: covered by another screen or popped,
    /// it's hidden, and back on top, shown again.
    func templateWillAppear(_ aTemplate: CPTemplate, animated: Bool) {
        guard interface != nil else { return }
        if aTemplate === CPNowPlayingTemplate.shared {
            updateNowPlayingButtons()
        }
        guard let page = sharePlayPage, aTemplate === page else { return }
        SharePlayController.shared.showCode(on: .car)
    }

    /// The trail has moved on, which frees a move CarPlay never reported back on.
    func templateDidAppear(_ aTemplate: CPTemplate, animated: Bool) {
        mover?.trailChanged()
    }

    func templateDidDisappear(_ aTemplate: CPTemplate, animated: Bool) {
        guard interface != nil else { return }
        mover?.trailChanged()
        guard let page = sharePlayPage, aTemplate === page else { return }
        if interface?.templates.contains(where: { $0 === page }) == true {
            SharePlayController.shared.hideCode(on: .car)
        } else {
            sharePlayPageGone()
        }
    }

    private struct ContentKey: Equatable {
        let revision: Int
        let mixes: [String]
        let shelves: [String]
        let discover: Int
        let source: MusicSource
        let yourMusic: Int
        let yourPlaylists: Int
        let downloads: Int
        /// What's playing, for the rows that show it.
        let playing: String?
        /// The song left paused, for Listen Now's Continue card.
        let waiting: String?
        /// Whether there's enough listening for Tracks Radio.
        let captures: Bool
        /// Offline, Listen Now leads with what's downloaded.
        let isOnline: Bool
    }

    private func tab(_ title: String, symbol: String) -> CPListTemplate {
        let template = CPListTemplate(title: title, sections: [])
        template.tabTitle = title
        template.tabImage = UIImage(systemName: symbol)
        template.showsSpinnerWhileEmpty = true
        return template
    }

    /// Rebuilds the tabs a moment after the last change, since a capture changes the history,
    /// the mixes and the stats one after another. Each tab on its own, so one waiting on covers
    /// doesn't hold up the others. A tab whose rows came out the same is left alone.
    private func rebuild(after delay: Duration = .milliseconds(500)) {
        // A rebuild asked for at once isn't put off by changes arriving behind it: on
        // connecting, the watch's first look would otherwise hold the tabs back half a second.
        guard interface != nil else { return }
        if isRebuildingNow, delay > .zero { return }
        refresh?.cancel()
        isRebuildingNow = delay == .zero
        refresh = Task {
            if delay > .zero { try? await Task.sleep(for: delay) }
            guard !Task.isCancelled else { return }
            isRebuildingNow = false
            // The recent searches are the source's own. Otherwise Search is read again as it's
            // opened, rather than changing under someone looking at it with every song.
            if searchBuiltFor != model.musicSource {
                refreshSearch()
            }
            refreshRadioList()
            listenRefresh.request()
            libraryRefresh.request()
        }
    }

    /// Each tab's first build draws what's on the phone at once, then the rest once it's
    /// fetched: over a slow wireless connection the shelves can take seconds.
    private func isFirstBuild(_ template: CPListTemplate) -> Bool {
        builtFrom[ObjectIdentifier(template)] == nil
    }

    private func buildListenNow() async {
        let content = listenNowCards()
        updateListenNowHeader(content.plan.lead)
        let late = CarPlayImages.Late()
        let cardImages = fetchCardImages(content.cards, late: late)
        // Never drawn empty before the shelves are in, which would say nothing's been played.
        if isFirstBuild(listenNow), case let first = listenNowFirstSections(content, late: late), !first.isEmpty {
            update(listenNow, first)
        }
        let sections = await listenNowSections(content, cardImages: cardImages, late: late)
        guard !Task.isCancelled, interface != nil else { return }
        update(listenNow, sections)
    }

    /// The library doesn't change with a capture: built once, and again if the source, access,
    /// or your own music changes.
    private func buildLibrary() async {
        // MusicKit's library, only once the car has finished connecting.
        await PlayerQuiet.settled()
        guard !Task.isCancelled, interface != nil else { return }
        // Access read first: built before it was known, the tab said Library Unavailable until
        // the settings check a moment later drew it again.
        refreshMusicAccess()
        let music = model.yourMusic
        let accessKey = musicAccess.map { "\($0)" } ?? "unknown"
        let key = model.musicSource == .yourMusic
            ? "yourMusic.\(music.index.albums.count).\(music.playlists.all.count).\(music.downloads.items.isEmpty).\(accessKey)"
            : "appleMusic.\(accessKey).\(music.hasMusic)"
        let libraryKey = "\(key).\(model.player.current == nil)"
        guard libraryBuiltFor != libraryKey else { return }
        // Where the access row stands, the places can't be opened anyway.
        let access = accessSection()
        if isFirstBuild(library), access.isEmpty, let places = model.musicSource == .yourMusic ? yourMusicPlaces() : appleMusicPlaces() {
            library.showsSpinnerWhileEmpty = false
            library.updateSections(Self.capped([places] + (sourceSwitchSection().map { [$0] } ?? [])))
            builtFrom[ObjectIdentifier(library)] = 0
        }
        let shelves = access.isEmpty ? (model.musicSource == .yourMusic ? await yourMusicLibrarySections() : await appleMusicLibrarySections()) : access
        guard !Task.isCancelled, interface != nil else { return }
        library.showsSpinnerWhileEmpty = false
        let switchSection = access.isEmpty ? sourceSwitchSection().map { [$0] } ?? [] : []
        library.updateSections(Self.capped(shelves + switchSection))
        libraryBuiltFor = libraryKey
    }

    private func buildRadio() async {
        updateRadioHeader()
        if isFirstBuild(radio) { update(radio, await radioSections(local: true)) }
        guard !Task.isCancelled, interface != nil else { return }
        let sections = await radioSections()
        guard !Task.isCancelled, interface != nil else { return }
        update(radio, sections)
    }

    /// The other source, the last thing in Library, when there's music in both: the song on
    /// carries on from the other source when it has it, so switching while driving doesn't
    /// stop the music.
    private func sourceSwitchSection() -> CPListSection? {
        guard model.isDemoLaunch || (musicAccess == .authorized && model.yourMusic.hasMusic) else { return nil }
        let other: MusicSource = model.musicSource == .appleMusic ? .yourMusic : .appleMusic
        return CPListSection(items: [useSourceRow(other)])
    }

    /// "Use Your Music" or "Use Apple Music".
    func useSourceRow(_ source: MusicSource) -> CPListItem {
        let title = source == .yourMusic ? String(localized: "Use Your Music") : String(localized: "Use Apple Music")
        let item = CPListItem(text: title, detailText: nil, image: CarPlayImages.symbol("arrow.left.arrow.right", side: CPListItem.maximumImageSize.height, scale: scale))
        item.handler = { [weak self] _, completion in
            guard let self else { return completion() }
            let row = finishing(completion)
            Task {
                await self.model.switchSourceCarryingOn(to: source)
                row.call()
            }
        }
        return item
    }

    /// Redraws what shows a setting when one the car shows has changed: any change to
    /// UserDefaults lands here, most of them nothing to do with the car.
    private func settingsMayHaveChanged() {
        // Once for a burst: the phone writes its own defaults several times a second while
        // music plays, and each write lands here.
        guard interface != nil, settingsCheck == nil else { return }
        settingsCheck = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard let self, !Task.isCancelled else { return }
            settingsCheck = nil
            checkSettings()
        }
    }

    private func checkSettings() {
        // Apple Music access, turned on in Settings, says nothing either.
        let accessChanged = refreshMusicAccess()
        if needsAppleMusicAccess != showsAccessProblem {
            showsAccessProblem = needsAppleMusicAccess
            rebuild()
        } else if accessChanged {
            rebuild()
        }
        let now = (radio: PlayPreferences.isTracksRadioOn, autoplay: PlayPreferences.autoplay)
        guard let shown = shownSettings, shown != now else { return }
        shownSettings = now
        updateNowPlayingButtons()
        if shown.radio != now.radio { rebuild() }
    }

    /// Reads Apple Music access again, and says whether it changed. Not while a scene is
    /// changing: a MusicKit call made then can wait on the services the car's connection is
    /// still starting, holding the main thread until the watchdog ends the app.
    @discardableResult
    private func refreshMusicAccess() -> Bool {
        guard !PlayerQuiet.isQuiet else { return false }
        let now = MusicAuthorization.currentStatus
        guard now != musicAccess else { return false }
        musicAccess = now
        return true
    }

    /// Radio's list again, on its own: its first row follows Tracks Radio starting and pausing.
    func refreshRadioList() {
        guard interface != nil else { return }
        radioRefresh.request()
    }

    func update(_ template: CPListTemplate, _ sections: [CPListSection]) {
        // Built once: from here an empty tab says why, rather than spinning.
        template.showsSpinnerWhileEmpty = false
        let sections = Self.capped(sections)
        let signature = Self.signature(of: sections)
        guard builtFrom[ObjectIdentifier(template)] != signature else { return }
        builtFrom[ObjectIdentifier(template)] = signature
        template.updateSections(sections)
    }

    /// A page cut to what CarPlay takes, before it's shown.
    static func capped(_ page: CPListTemplate) -> CPListTemplate {
        let sections = page.sections
        let items = sections.reduce(0) { $0 + $1.items.count }
        if sections.count > Int(CPListTemplate.maximumSectionCount) || items > Int(CPListTemplate.maximumItemCount) {
            page.updateSections(capped(sections))
        }
        return page
    }

    /// Sections cut to what CarPlay takes: past its limits it shows only the first anyway, and
    /// the rows a list drops shouldn't be counted on by its handlers.
    static func capped(_ sections: [CPListSection]) -> [CPListSection] {
        var room = Int(CPListTemplate.maximumItemCount)
        var kept: [CPListSection] = []
        for section in sections.prefix(Int(CPListTemplate.maximumSectionCount)) where room > 0 {
            guard section.items.count > room else {
                kept.append(section)
                room -= section.items.count
                continue
            }
            kept.append(CPListSection(items: Array(section.items.prefix(room)), header: section.header, sectionIndexTitle: section.sectionIndexTitle))
            room = 0
        }
        return kept
    }

    /// What a row plays, as a number for its `userInfo`, so a row that reads the same but plays
    /// something new (a mix remade after a song counts) still reloads.
    static func contentToken(_ parts: [String]) -> Int {
        var hasher = Hasher()
        parts.forEach { hasher.combine($0) }
        return hasher.finalize()
    }

    /// What a list shows, as one number: its headers, rows, what they say, which is playing,
    /// and what they play.
    static func signature(of sections: [CPListSection]) -> Int {
        var hasher = Hasher()
        for section in sections {
            hasher.combine(section.header)
            for item in section.items {
                switch item {
                case let row as CPListItem:
                    hasher.combine(row.text)
                    hasher.combine(row.detailText)
                    hasher.combine(row.isPlaying)
                    hasher.combine(row.userInfo as? Int)
                case let row as CPListImageRowItem:
                    hasher.combine(row.text)
                    hasher.combine(row.userInfo as? Int)
                    for element in row.elements {
                        switch element {
                        case let card as CPListImageRowItemCardElement:
                            hasher.combine(card.title)
                            hasher.combine(card.subtitle)
                        case let tile as CPListImageRowItemImageGridElement:
                            hasher.combine(tile.title)
                        case let entry as CPListImageRowItemRowElement:
                            hasher.combine(entry.title)
                            hasher.combine(entry.subtitle)
                        case let person as CPListImageRowItemCondensedElement:
                            hasher.combine(person.title)
                            hasher.combine(person.subtitle)
                        default:
                            break
                        }
                    }
                default:
                    break
                }
            }
        }
        return hasher.finalize()
    }

    /// Whether Tracks Radio is on and has enough of your listening to play from.
    var hasTracksRadio: Bool {
        PlayPreferences.isTracksRadioOn && model.library.history.captures.count >= 20
    }

    /// Whether Apple Music is the source but Tracks can't play from it: asked for from the car,
    /// the prompt would land on a phone no one is looking at.
    var needsAppleMusicAccess: Bool {
        guard let musicAccess else { return false }
        return !model.isDemoLaunch && model.musicSource == .appleMusic && musicAccess != .authorized
    }

    /// Said at the top of a tab when nothing from Apple Music can play, rather than on every
    /// tap, with your own music a tap away under it when there is some.
    func accessSection() -> [CPListSection] {
        guard needsAppleMusicAccess else { return [] }
        let row = CPListItem(text: String(localized: "Apple Music Access Is Off"), detailText: nil, image: CarPlayImages.symbol("exclamationmark.triangle", side: CPListItem.maximumImageSize.height, scale: scale))
        row.handler = { _, completion in completion() }
        var rows = [row]
        if model.yourMusic.hasMusic { rows.append(useSourceRow(.yourMusic)) }
        return [CPListSection(items: rows)]
    }

    /// Whether what's playing came from here: a mix, mood or station by its name.
    func isPlaying(_ title: String) -> Bool {
        model.player.hasQueue && model.player.context?.title == title
    }

    /// Shows the playing marker on a row while `isOn` says so, now and as the music moves on.
    func mark(_ row: CPListItem, _ isOn: @escaping () -> Bool) {
        row.isPlaying = isOn()
        if marked.count > 400 { marked.removeAll { $0.row.value == nil } }
        marked.append((WeakRow(value: row), isOn))
    }

    /// Marks the row of what's playing now, wherever one is open, and unmarks the rest.
    func refreshPlayingMarks() {
        marked.removeAll { $0.row.value == nil }
        for (box, isOn) in marked {
            guard let row = box.value else { continue }
            let on = isOn()
            if row.isPlaying != on { row.isPlaying = on }
        }
    }

    /// Whether the song playing is this one from Apple Music.
    func isPlayingSong(_ id: MusicItemID) -> Bool {
        model.player.current?.song?.id == id
    }

    /// Whether the song playing is this one of yours.
    func isPlayingTrack(_ id: LocalTrack.ID) -> Bool {
        model.player.current?.local?.id == id
    }

    // MARK: - Playing

    /// Plays, then shows Now Playing, or says what went wrong, in the car: no one should need
    /// the phone to find out.
    func play(_ action: @escaping () async -> Void, completion: @escaping () -> Void) {
        // Asking for Apple Music access from the car would put a prompt on a phone no one is
        // looking at, and leave the row spinning. Decided here, not from a problem left over
        // from the phone, which would otherwise stop every row playing.
        let blocked: PlayerProblem? = needsAppleMusicAccess ? .accessDenied : nil
        model.player.problem = blocked
        let tap = tapped()
        let row = finishing(completion)
        Task { [weak self] in
            if blocked == nil {
                // The row stops spinning and Now Playing comes up as soon as the player has the
                // queue, rather than once the song has started, which can take seconds more.
                await PlayStart.run(mayAskForAccess: false, whenQueued: { [weak self] in self?.queued(tap, row: row) }) {
                    await action()
                }
            }
            self?.played(tap, row: row)
        }
    }

    /// The player has the tap's queue: Now Playing, unless the row gave up long ago or another
    /// tap has moved the car on since. Only the first word of it counts.
    private func queued(_ tap: Tap, row: RowCompletion) {
        guard row.call() else { return }
        showNowPlayingIfLatest(tap)
    }

    /// The tap's action is over: what went wrong, if anything, or Now Playing if the queue
    /// never said it was ready (your own music starts at once).
    private func played(_ tap: Tap, row: RowCompletion) {
        let isFirst = row.call()
        guard tap.connection == connection, interface != nil else { return }
        if let problem = model.player.problem {
            model.player.problem = nil
            show(CarProblem(problem))
        } else if isFirst {
            showNowPlayingIfLatest(tap)
        }
    }

    private func showNowPlayingIfLatest(_ tap: Tap) {
        guard isLatest(tap) else { return }
        showNowPlaying { [weak self] in self?.isLatest(tap) ?? false }
    }

    /// A row's completion that reaches the car within a few seconds, whatever happens to what
    /// the row started.
    private func finishing(_ completion: @escaping () -> Void) -> RowCompletion {
        RowCompletion(onLate: { Self.log.error("A CarPlay row took too long; letting it go") }, completion)
    }

    /// A tap that plays or opens something, so what it leads to can tell whether it's still
    /// the latest, on the same car.
    struct Tap {
        let connection: Int
        let id: Int
    }

    func tapped() -> Tap {
        tapID &+= 1
        return Tap(connection: connection, id: tapID)
    }

    func isLatest(_ tap: Tap) -> Bool {
        interface != nil && tap.connection == connection && tap.id == tapID
    }

    /// A problem in a sheet: a short title and the condition, with OK.
    func show(_ problem: CarProblem) {
        present(CPActionSheetTemplate(title: problem.title, message: problem.message, actions: [okAction()]))
    }

    /// Pushes a page once it's built. The row keeps its spinner until then. Only the latest
    /// tap opens its page: two rows tapped in a row would otherwise stack both pages. A page
    /// that took so long the row gave up isn't pushed over whatever's on screen by then.
    func open(_ make: @escaping () async -> CPListTemplate?, then completion: @escaping () -> Void) {
        let tap = tapped()
        let row = finishing(completion)
        Task { [weak self] in
            let page = await make()
            guard let self, let page, isLatest(tap), !row.isLate else {
                row.call()
                return
            }
            push(Self.capped(page)) { row.call() }
        }
    }
}

/// A row held without keeping it: a page gone from the car takes its rows with it.
struct WeakRow {
    weak var value: CPListItem?
}

