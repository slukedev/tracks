import SwiftUI
import TracksCore

private struct DownloadContext {
    let choice: DownloadChoice
    let plan: DownloadPlan
    let player: PlayerModel?
    let openURL: OpenURLAction
    let subject: DownloadSubject

    func run(_ service: DownloadService) {
        Task {
            if let message = await choice.keep(subject, with: service, openURL: { openURL($0) }) {
                player?.confirm(message)
            }
        }
    }

    func tapService(asking: Bool) -> DownloadService? {
        switch plan.tap {
        case .perform(let service): service
        case .choose: asking ? nil : plan.options.first(where: \.isActionable)?.service
        case nil: nil
        }
    }
}

private struct DownloadReader<Content: View>: View {
    let subject: DownloadSubject
    @ViewBuilder let content: (DownloadContext) -> Content

    @Environment(YourMusic.self) private var music: YourMusic?
    @Environment(PlayFeed.self) private var feed: PlayFeed?
    @Environment(PlayerModel.self) private var player: PlayerModel?
    @Environment(\.openURL) private var openURL
    @AppStorage(DownloadChoice.isOnKey) private var isOn = false
    @AppStorage(DownloadChoice.defaultKey) private var preferred = DownloadDefault.ask

    var body: some View {
        if isOn, let music {
            let choice = music.downloadChoice
            let plan = choice.plan(for: subject, cannotPlayCatalog: feed?.cannotPlayCatalog ?? false, preferred: preferred.service)
            if !plan.isEmpty {
                content(DownloadContext(choice: choice, plan: plan, player: player, openURL: openURL, subject: subject))
                    .onAppear { choice.lookUp(subject) }
            }
        }
    }
}

struct DownloadMenuItems: View {
    let subject: DownloadSubject

    var body: some View {
        DownloadReader(subject: subject) { context in
            if context.plan.options.count > 1 {
                Menu("Download", systemImage: "arrow.down.circle") {
                    DownloadOptionButtons(context: context)
                    Divider()
                    AlwaysUsePicker()
                }
            } else {
                DownloadOptionButtons(context: context)
            }
        }
    }
}

private struct DownloadOptionButtons: View {
    let context: DownloadContext

    var body: some View {
        ForEach(context.plan.options, id: \.service) { option in
            let words = DownloadWords(option: option)
            Button(words.title, systemImage: words.symbol) { context.run(option.service) }
                .disabled(!option.isActionable)
        }
    }
}

struct AlwaysUsePicker: View {
    @AppStorage(DownloadChoice.defaultKey) private var preferred = DownloadDefault.ask

    var body: some View {
        Picker("Always Use", systemImage: "pin", selection: $preferred) {
            ForEach(DownloadDefault.allCases, id: \.self) { choice in
                Text(DownloadWords.name(of: choice)).tag(choice)
            }
        }
        .pickerStyle(.menu)
    }
}

struct DownloadControl: View {
    let subject: DownloadSubject
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var taps = 0

    var body: some View {
        DownloadReader(subject: subject) { context in
            let summary = context.plan.summary
            Group {
                if let service = context.tapService(asking: true) {
                    Menu {
                        menuItems(context)
                    } label: {
                        label(summary)
                    } primaryAction: {
                        taps += 1
                        context.run(service)
                    }
                } else {
                    Menu {
                        menuItems(context)
                    } label: {
                        label(summary)
                    }
                }
            }
            .menuIndicator(.hidden)
            .help(Text(DownloadWords.spoken(summary, title: context.subject.title)))
            .accessibilityLabel(Text(DownloadWords.spoken(summary, title: context.subject.title)))
            .animation(reduceMotion ? nil : PlayMotion.value, value: summary)
            #if os(iOS)
            .sensoryFeedback(.impact(weight: .light), trigger: taps)
            #endif
        }
    }

    @ViewBuilder
    private func menuItems(_ context: DownloadContext) -> some View {
        DownloadOptionButtons(context: context)
        if context.plan.options.count > 1 {
            Divider()
            AlwaysUsePicker()
        }
    }

    @ViewBuilder
    private func label(_ summary: DownloadPlan.Summary) -> some View {
        switch summary {
        case .working:
            TurningRing(diameter: 18, lineWidth: 2.5, color: .accentColor)
                .frame(width: 24, height: 24)
        default:
            Image(systemName: DownloadWords.symbol(for: summary))
                .contentTransition(.symbolEffect(.replace))
        }
    }
}

struct DownloadSwipeAction: View {
    let subject: DownloadSubject

    var body: some View {
        DownloadReader(subject: subject) { context in
            if let service = context.tapService(asking: false), let option = context.plan.option(for: service) {
                let words = DownloadWords(option: option)
                Button(words.short, systemImage: words.symbol) { context.run(service) }
                    .tint(.blue)
            }
        }
    }
}

struct DownloadWords {
    let title: LocalizedStringKey
    let short: LocalizedStringKey
    let symbol: String

    init(option: DownloadOption) {
        switch (option.service, option.action) {
        case (.appleMusic, .keep), (.appleMusic, .keepRest):
            (title, short, symbol) = ("Download on Apple Music", "Download", "arrow.down.circle")
        case (.appleMusic, .openInMusic):
            (title, short, symbol) = ("Download in Music", "Download", "arrow.up.forward.app")
        case (.appleMusic, .retry):
            (title, short, symbol) = ("Try Apple Music Again", "Try Again", "arrow.clockwise")
        case (.appleMusic, .inProgress):
            (title, short, symbol) = ("Adding to Apple Music…", "Adding…", "ellipsis.circle")
        case (.appleMusic, .done), (.appleMusic, .partlyDone):
            (title, short, symbol) = ("Downloaded on Apple Music", "Downloaded", "checkmark.circle")
        case (.appleMusic, .offline):
            (title, short, symbol) = ("Apple Music Is Offline", "Offline", "wifi.slash")
        case (.yourMusic, .keep):
            (title, short, symbol) = ("Get on Your Server", "Get", "tray.and.arrow.down")
        case (.yourMusic, .keepRest):
            (title, short, symbol) = ("Get the Rest on Your Server", "Get the Rest", "tray.and.arrow.down")
        case (.yourMusic, .retry):
            (title, short, symbol) = ("Try Your Server Again", "Try Again", "arrow.clockwise")
        case (.yourMusic, .inProgress):
            (title, short, symbol) = ("Getting on Your Server…", "Getting…", "ellipsis.circle")
        case (.yourMusic, .done):
            (title, short, symbol) = ("On Your Server", "On Your Server", "checkmark.circle")
        case (.yourMusic, .partlyDone):
            (title, short, symbol) = ("Partly on Your Server", "Partly Got", "circle.lefthalf.filled")
        case (.yourMusic, .offline), (.yourMusic, .openInMusic):
            (title, short, symbol) = ("Your Server Is Offline", "Offline", "wifi.slash")
        }
    }

    static func name(of choice: DownloadDefault) -> LocalizedStringKey {
        switch choice {
        case .ask: "Ask Each Time"
        case .appleMusic: "Apple Music"
        case .yourMusic: "Your Server"
        }
    }

    static func symbol(for summary: DownloadPlan.Summary) -> String {
        switch summary {
        case .notKept, .partlyKept, .working: "arrow.down.circle"
        case .kept: "checkmark.circle"
        case .failed: "exclamationmark.arrow.circlepath"
        }
    }

    static func spoken(_ summary: DownloadPlan.Summary, title: String) -> String {
        switch summary {
        case .notKept, .partlyKept: String(localized: "Download \(title)")
        case .working: String(localized: "Downloading \(title)")
        case .kept: String(localized: "\(title) Is Downloaded")
        case .failed: String(localized: "Couldn't Download \(title)")
        }
    }
}
