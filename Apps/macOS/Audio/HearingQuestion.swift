import SwiftUI

extension View {
    func asksToHearMusic(when isWanted: Bool, isDemo: Bool) -> some View {
        modifier(HearingQuestion(isWanted: isWanted, isDemo: isDemo))
    }
}

private struct HearingQuestion: ViewModifier {
    let isWanted: Bool
    let isDemo: Bool
    @State private var id = UUID()
    @State private var isAsking = false

    func body(content: Content) -> some View {
        content
            .onChange(of: isDemo, initial: true) { MusicHearing.shared.playerChanged(isDemo: isDemo) }
            .task(id: isWanted) {
                guard isWanted else {
                    MusicHearing.shared.releaseQuestion(id)
                    return
                }
                try? await Task.sleep(for: .seconds(1.5))
                guard !Task.isCancelled, MusicHearing.shared.claimsQuestion(id) else { return }
                isAsking = true
            }
            .onDisappear { MusicHearing.shared.releaseQuestion(id) }
            .alert("Follow the Music?", isPresented: $isAsking) {
                Button("Continue") { MusicHearing.shared.choose(.on) }
                Button("Not Now", role: .cancel) { MusicHearing.shared.choose(.off) }
            } message: {
                Text("Stage and moving backgrounds can hear what Apple Music plays and move with every beat. Your Mac will ask you to allow it. Nothing is recorded.")
            }
    }
}

struct MusicHearingSettings: View {
    @Bindable private var hearing = MusicHearing.shared

    var body: some View {
        SettingsSwitch("Hear the Music", detail: Text(detail), isOn: $hearing.isOn)
        if hearing.isOn && hearing.isRefused {
            SettingsDetailRow(title: Text("Not Allowed"), detail: Text("Your Mac isn't letting Tracks hear Apple Music.")) {
                Button("Open System Settings") { hearing.openPrivacySettings() }
            }
        }
    }

    private var detail: LocalizedStringKey {
        hearing.isOn
            ? "Stage and moving backgrounds follow the song Apple Music is playing, beat for beat. Nothing is recorded."
            : "Stage and moving backgrounds follow each song's preview."
    }
}
