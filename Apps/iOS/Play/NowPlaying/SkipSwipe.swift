import SwiftUI
import TracksCore

struct SkipSwipe<Item: View>: View {
    let travel: CGFloat
    var follows = true
    /// Shared where the swipe is read further out: in the tab bar's accessory a pan only
    /// begins on the row itself.
    var state: SkipSwipeState?
    @ViewBuilder let item: (PlayerTrack) -> Item
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var ownState = SkipSwipeState()

    private var model: SkipSwipeState { state ?? ownState }

    var body: some View {
        let shown = model.held?.outgoing ?? model.arriving ?? (player.current?.id == model.leaving?.id ? nil : player.current)
        let incoming = model.held.map(\.incoming) ?? (model.offset == 0 ? nil : model.neighbour(toward: model.offset, in: player))
        ZStack {
            if let incoming {
                item(incoming)
                    .offset(x: model.offset + (model.offset < 0 ? travel : -travel))
                    .accessibilityHidden(true)
            }
            if let shown {
                item(shown)
                    .offset(x: model.offset)
            }
        }
        .gesture(model.pan(player: player, travel: travel, moves: follows && !reduceMotion, isEnabled: state == nil))
        .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: model.commits)
        .onChange(of: player.current?.id) { model.playerMovedOn() }
    }
}

@MainActor
@Observable
final class SkipSwipeState {
    static let mini = SkipSwipeState()

    private(set) var offset: CGFloat = 0
    private(set) var held: (outgoing: PlayerTrack, incoming: PlayerTrack?)?
    private(set) var arriving: PlayerTrack?
    /// The song slid away, kept off until the player moves on from it, when a live mix
    /// hadn't picked the next one yet.
    private(set) var leaving: PlayerTrack?
    private(set) var commits = 0
    @ObservationIgnored private var axis: Axis?

    func pan(player: PlayerModel, travel: CGFloat, moves: Bool, isEnabled: Bool = true, onVertical: ((PanGesture.Value, _ hasEnded: Bool) -> Void)? = nil) -> PanGesture {
        PanGesture(isEnabled: isEnabled) { [self] value in
            let width = value.translation.width
            let height = value.translation.height
            if axis == nil {
                guard max(abs(width), abs(height)) >= 4 else { return }
                axis = abs(width) > abs(height) ? .horizontal : .vertical
            }
            if axis == .vertical { onVertical?(value, false) }
            guard axis == .horizontal, held == nil, moves else { return }
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                offset = canGo(toward: width, in: player) ? width : rubberBand(width, travel: travel)
            }
        } onEnded: { [self] value in
            defer { axis = nil }
            if axis == .vertical { onVertical?(value, true) }
            guard axis == .horizontal, held == nil else { return }
            let width = value.translation.width
            let velocity = value.velocity.width
            let projected = value.predictedEndTranslation.width
            let goes = (abs(projected) > travel * 0.45 && projected.sign == width.sign)
                || (abs(velocity) > 500 && velocity.sign == width.sign)
            guard goes, let current = player.current, canGo(toward: width, in: player) else {
                return springBack(velocity: velocity)
            }
            commit(from: current, to: neighbour(toward: width, in: player), leftward: width < 0, velocity: velocity, travel: travel, moves: moves, player: player)
        }
    }

    func playerMovedOn() {
        arriving = nil
        leaving = nil
    }

    func neighbour(toward offset: CGFloat, in player: PlayerModel) -> PlayerTrack? {
        guard let current = player.current else { return nil }
        if offset < 0 { return player.upNext.first }
        guard player.context?.isStation != true else { return nil }
        if player.playbackTime > 3 { return current }
        return player.lastPlayedInQueue ?? current
    }

    /// A live mix picks its next song as it's skipped to, so there's always somewhere to go.
    private func canGo(toward offset: CGFloat, in player: PlayerModel) -> Bool {
        neighbour(toward: offset, in: player) != nil || (offset < 0 && player.isLive)
    }

    private func commit(from current: PlayerTrack, to incoming: PlayerTrack?, leftward: Bool, velocity: CGFloat, travel: CGFloat, moves: Bool, player: PlayerModel) {
        commits += 1
        if leftward { player.skipToNext() } else { player.skipToPrevious() }
        guard moves else { return }
        held = (current, incoming)
        let target = leftward ? -travel : travel
        let remaining = target - offset
        let speed = abs(remaining) > 1 ? min(max(velocity / remaining, 0), 30) : 0
        withAnimation(.interpolatingSpring(duration: 0.34, bounce: 0, initialVelocity: speed), completionCriteria: .removed) {
            offset = target
        } completion: { [self] in
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                let isStillOn = player.current?.id == current.id
                arriving = isStillOn ? incoming : nil
                leaving = isStillOn && incoming == nil ? current : nil
                held = nil
                offset = 0
            }
            Task {
                try? await Task.sleep(for: .seconds(2))
                if let incoming, arriving?.id == incoming.id { arriving = nil }
                if leaving?.id == current.id { leaving = nil }
            }
        }
    }

    private func springBack(velocity: CGFloat) {
        guard offset != 0 else { return }
        let speed = min(max(velocity / -offset, -30), 30)
        withAnimation(.interpolatingSpring(duration: 0.4, bounce: 0.15, initialVelocity: speed)) { offset = 0 }
    }

    private func rubberBand(_ x: CGFloat, travel: CGFloat) -> CGFloat {
        let limit = travel * 0.3
        let given = (1 - 1 / (abs(x) * 0.55 / limit + 1)) * limit
        return x < 0 ? -given : given
    }
}
