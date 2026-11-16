import SwiftUI
import TracksCore

@MainActor
@Observable
final class PlayerHandoff {
    static let shared = PlayerHandoff()

    var isPresented = false {
        didSet {
            guard oldValue, !isPresented else { return }
            settleClosed()
        }
    }

    private(set) var progress: CGFloat = 1
    private(set) var fade: CGFloat = 0
    private(set) var isLifted = false
    /// Whether this opening or closing carries the cover into the mini player, decided as it
    /// starts so nothing changes shape part way.
    private(set) var travels = false
    private(set) var isClosing = false
    private(set) var isDragging = false
    private(set) var landings = 0
    /// Counts the times the lifted cover was first drawn, so the mini player hides its own
    /// only once there's one to see.
    private(set) var isLiftedCoverDrawn = false

    var miniCover: CGRect?
    var miniBar: CGRect?
    var source: Source?
    var screen: CGRect = .zero
    var safeArea = EdgeInsets()
    var reducesMotion = false

    /// Where the player is drawn this frame, mid-spring, so a drag can take it from there.
    @ObservationIgnored var shownProgress: CGFloat = 1
    @ObservationIgnored private var dragBase: CGFloat = 0
    @ObservationIgnored private var awaitsOpening = false
    @ObservationIgnored private var landsByDrag = false
    /// Bumped by each new motion, so a finished spring that's been taken over does nothing.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var settling: (target: CGFloat, generation: Int)?

    enum Source: Equatable {
        case cover(CGRect)
        case fullCover(FullCoverPicture)

        var square: CGRect {
            switch self {
            case .cover(let rect): rect
            case .fullCover(let picture): CGRect(origin: picture.shown.origin, size: CGSize(width: picture.side, height: picture.side))
            }
        }

        var fades: [Double] {
            guard case .fullCover(let picture) = self else { return [] }
            return [picture.top, picture.bottom, picture.leading, picture.trailing]
        }

        func sameKind(as other: Source) -> Bool {
            switch (self, other) {
            case (.cover, .cover), (.fullCover, .fullCover): true
            default: false
            }
        }

        var drawn: CGRect {
            switch self {
            case .cover(let rect): rect
            case .fullCover(let picture): picture.shown
            }
        }
    }

    // MARK: - Geometry

    var target: CGRect? {
        miniCover.map { $0.offsetBy(dx: -screen.minX, dy: -screen.minY) }
    }

    var bar: CGRect? {
        guard travels else { return nil }
        return miniBar.map { $0.offsetBy(dx: -screen.minX, dy: -screen.minY) }
    }

    var distance: CGFloat {
        let slide = max(screen.height + safeArea.top + safeArea.bottom, 400) + 40
        guard let bar else { return slide }
        let travel = bar.minY + safeArea.top
        return travel > 120 ? travel : slide
    }

    private var canTravel: Bool {
        !reducesMotion && miniCover != nil && miniBar != nil
    }

    // MARK: - Opening and closing

    func open() {
        guard !isPresented else { return reopen() }
        generation += 1
        isClosing = false
        landsByDrag = false
        travels = canTravel
        isLifted = travels
        if reducesMotion {
            progress = 0
            fade = 1
        } else {
            progress = 1
            fade = 0
        }
        shownProgress = progress
        awaitsOpening = true
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { isPresented = true }
    }

    func playerDidLayOut() {
        guard awaitsOpening, !isDragging else { return }
        awaitsOpening = false
        if reducesMotion {
            withAnimation(.easeOut(duration: 0.22)) { fade = 0 }
        } else {
            settle(to: 0, velocity: 0)
        }
    }

    func reopen() {
        guard isPresented, isClosing else { return }
        generation += 1
        setWithoutAnimation { progress = shownProgress }
        settle(to: 0, velocity: 0)
    }

    func close(velocity: CGFloat = 0) {
        guard isPresented, !isClosing else { return }
        if reducesMotion {
            isClosing = true
            generation += 1
            let generation = generation
            withAnimation(.easeOut(duration: 0.22), completionCriteria: .removed) {
                fade = 1
            } completion: { [self] in
                guard generation == self.generation else { return }
                land()
            }
            return
        }
        if !isLifted, progress == 0 {
            travels = canTravel
            isLifted = travels
        }
        settle(to: 1, velocity: max(velocity, 0))
    }

    // MARK: - Dragging

    func beginDrag(fromMiniPlayer: Bool = false) {
        generation += 1
        settling = nil
        isDragging = true
        awaitsOpening = false
        if !isPresented {
            guard fromMiniPlayer else { return }
            isClosing = false
            travels = canTravel
            isLifted = travels
            progress = 1
            shownProgress = 1
            fade = 0
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { isPresented = true }
        } else if !isLifted, shownProgress == 0 {
            travels = canTravel
            isLifted = travels
        }
        dragBase = shownProgress
        isClosing = false
        setWithoutAnimation { progress = shownProgress }
    }

    func drag(_ translation: CGFloat) {
        guard isDragging else { return }
        let next = min(1, max(0, dragBase + translation / distance))
        setWithoutAnimation { progress = next }
    }

    func endDrag(translation: CGFloat, predicted: CGFloat, velocity: CGFloat) {
        guard isDragging else { return }
        isDragging = false
        let headedTo = progress + (predicted - translation) / distance
        let closes: Bool
        if velocity > 350 {
            closes = true
        } else if velocity < -350 {
            closes = false
        } else if dragBase < 0.05 {
            closes = velocity > -100 && (translation > 120 || predicted > 180)
        } else if dragBase > 0.95 {
            closes = !(velocity < 100 && (-translation > 120 || -predicted > 180))
        } else {
            closes = headedTo > 0.5
        }
        if closes {
            landsByDrag = true
            if reducesMotion {
                isClosing = false
                close()
            } else {
                settle(to: 1, velocity: velocity)
            }
        } else {
            settle(to: 0, velocity: velocity)
        }
    }

    func cancelDrag() {
        guard isDragging else { return }
        isDragging = false
        settle(to: progress > 0.5 && dragBase > 0.5 ? 1 : 0, velocity: 0)
    }

    /// Takes the cover's place in the player as it's laid out. Read back through the player's
    /// own movement, it comes out a hair different each time, which would lay the player out
    /// again for ever; only a real move counts.
    func place(_ new: Source?) {
        guard let new, let source else {
            if new != source { source = new }
            return
        }
        let resized = abs(new.square.width - source.square.width) + abs(new.drawn.height - source.drawn.height)
        let moved = abs(new.drawn.minX - source.drawn.minX) + abs(new.drawn.minY - source.drawn.minY)
        // While the player moves, only a new size counts: its place is read back through the
        // movement and wavers by a fraction of a point.
        let counts = isLifted || isDragging ? resized > 0.5 : resized + moved > 0.25
        if counts || !new.sameKind(as: source) || new.fades != source.fades { self.source = new }
    }

    // MARK: - The lifted cover

    func liftedCoverAppeared() {
        isLiftedCoverDrawn = true
    }

    func liftedCoverDisappeared() {
        isLiftedCoverDrawn = false
    }

    var hidesMiniCover: Bool { isPresented && isLifted && isLiftedCoverDrawn }

    // MARK: -

    private func settle(to target: CGFloat, velocity: CGFloat) {
        generation += 1
        let generation = generation
        isClosing = target == 1
        let remaining = target - progress
        // A spring's starting speed is a share of the way it has to go.
        let speed = abs(remaining) > 0.001 ? min(max(velocity / distance / remaining, -30), 30) : 0
        let animation: Animation = reducesMotion
            ? .easeOut(duration: 0.22)
            : .interpolatingSpring(duration: target == 1 ? 0.44 : 0.4, bounce: 0, initialVelocity: speed)
        settling = (target, generation)
        withAnimation(animation, completionCriteria: .removed) {
            progress = target
        } completion: { [self] in
            arrive(target, generation: generation)
        }
    }

    /// Where the player is drawn, each frame. A spring's last stretch is too small to see but
    /// takes a while; it's done once it's within half a point of where it's going.
    func noteShown(_ shown: CGFloat) {
        shownProgress = shown
        guard let (target, generation) = settling, abs(shown - target) * distance < 0.5 else { return }
        settling = nil
        Task { @MainActor in arrive(target, generation: generation) }
    }

    private func arrive(_ target: CGFloat, generation: Int) {
        guard generation == self.generation, isLifted || isClosing || target == 0 else { return }
        settling = nil
        if target == 0 {
            isLifted = false
            isLiftedCoverDrawn = false
        } else if isPresented {
            land()
        }
    }

    private func land() {
        if landsByDrag, travels { landings += 1 }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { isPresented = false }
    }

    private func settleClosed() {
        generation += 1
        settling = nil
        isLifted = false
        isLiftedCoverDrawn = false
        isClosing = false
        isDragging = false
        awaitsOpening = false
        landsByDrag = false
        progress = 1
        shownProgress = 1
        fade = 0
        source = nil
    }

    private func setWithoutAnimation(_ change: () -> Void) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction, change)
    }
}

// MARK: - The presentation

struct NowPlayingPresentation: ViewModifier {
    /// Waits for the player's own gestures to give up first, as Up Next's list needs to scroll.
    /// Otherwise the pull runs alongside them: waiting for the cover's double tap to fail held
    /// the pull back for its first 35 points, which then arrived all at once.
    let yields: Bool
    let cover: CoverArt?
    let isFullCover: Bool
    let sinks: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var handoff = PlayerHandoff.shared
    /// Resets when a pull ends, however it ends: one the system cancels doesn't call `onEnded`.
    @GestureState private var isPulling = false
    @State private var pullStart: CGFloat?

    func body(content: Content) -> some View {
        let lifts = handoff.isLifted && handoff.travels
        // The player's own cover steps aside only once the lifted one is there to take over.
        let isCarried = lifts && handoff.isLiftedCoverDrawn
        content
            .environment(\.coverIsLifted, isCarried)
            .environment(\.liftsFullCover, isCarried)
            .environment(\.playerIsMoving, handoff.isLifted || handoff.isDragging || handoff.isClosing || handoff.fade > 0)
            .overlayPreferenceValue(CoverHandoffSourceKey.self) { anchor in
                GeometryReader { proxy in
                    let rect = anchor.map { proxy[$0] }
                    Color.clear
                        .onChange(of: rect, initial: true) { _, rect in
                            guard !isFullCover else { return }
                            handoff.place(rect.map(PlayerHandoff.Source.cover))
                        }
                }
                .allowsHitTesting(false)
            }
            .onPreferenceChange(FullCoverPictureKey.self) { picture in
                guard isFullCover else { return }
                handoff.place(picture.map { picture in
                    var picture = picture
                    picture.shown = picture.shown.offsetBy(dx: -handoff.safeArea.leading, dy: -handoff.safeArea.top)
                    return .fullCover(picture)
                })
            }
            .modifier(CardMotion(
                progress: handoff.progress,
                fade: handoff.fade,
                distance: handoff.distance,
                bar: handoff.bar,
                size: handoff.screen.size,
                safeArea: handoff.safeArea
            ))
            .background {
                Scrim(progress: handoff.progress, fade: handoff.fade)
                    .ignoresSafeArea()
            }
            .overlay {
                if lifts, let cover, let source = handoff.source, let target = handoff.target {
                    LiftedCover(progress: handoff.progress, cover: cover, source: source, target: target, sinks: sinks)
                        .allowsHitTesting(false)
                        .onAppear { handoff.liftedCoverAppeared() }
                        .onDisappear { handoff.liftedCoverDisappeared() }
                }
            }
            .overlay {
                if handoff.isLifted || handoff.isClosing, !handoff.isDragging {
                    Color.clear
                        .contentShape(.rect)
                        .ignoresSafeArea()
                        .gesture(pullGesture(catches: true))
                        .simultaneousGesture(SpatialTapGesture(coordinateSpace: .global).onEnded { tap in
                            guard handoff.isClosing, handoff.miniBar?.contains(tap.location) == true else { return }
                            handoff.reopen()
                        })
                }
            }
            .background {
                GeometryReader { proxy in
                    Color.clear
                        .onChange(of: proxy.frame(in: .global), initial: true) { _, frame in
                            handoff.screen = frame
                        }
                        .onChange(of: proxy.safeAreaInsets, initial: true) { _, insets in
                            handoff.safeArea = insets
                        }
                }
            }
            .gesture(pullGesture(catches: false), isEnabled: yields)
            .simultaneousGesture(pullGesture(catches: false), isEnabled: !yields)
            .interactiveDismissDisabled()
            .accessibilityAction(.escape) { handoff.close() }
            .onChange(of: reduceMotion, initial: true) { _, reduceMotion in
                handoff.reducesMotion = reduceMotion
            }
            .task {
                // A turn of the run loop first, so the player has been laid out and its
                // cover's place is known.
                await Task.yield()
                handoff.playerDidLayOut()
            }
            .onChange(of: isPulling) { _, isPulling in
                guard !isPulling, pullStart != nil else { return }
                pullStart = nil
                handoff.cancelDrag()
            }
    }

    private func pullGesture(catches: Bool) -> some Gesture {
        // Measured on the screen, which stays put while the player moves under the finger.
        DragGesture(minimumDistance: catches ? 4 : 10, coordinateSpace: .global)
            .updating($isPulling) { _, isPulling, _ in isPulling = true }
            .onChanged { value in
                let down = value.translation.height
                if pullStart == nil {
                    // Only a pull that's mostly downward; sideways is the scrubber's and the
                    // cover's. Caught mid-flight, either way.
                    guard catches || (down > 0 && down > abs(value.translation.width)) else { return }
                    pullStart = down
                    handoff.beginDrag()
                }
                handoff.drag(down - (pullStart ?? 0))
            }
            .onEnded { value in
                guard let start = pullStart else { return }
                pullStart = nil
                handoff.endDrag(
                    translation: value.translation.height - start,
                    predicted: value.predictedEndTranslation.height - start,
                    velocity: value.velocity.height
                )
            }
    }
}

extension View {
    func nowPlayingPresentation(yields: Bool, cover: CoverArt?, isFullCover: Bool, sinks: Bool) -> some View {
        modifier(NowPlayingPresentation(yields: yields, cover: cover, isFullCover: isFullCover, sinks: sinks))
    }
}

/// Only the offset, clip and opacity change each frame, never the player's layout.
private struct CardMotion: ViewModifier, Animatable {
    var progress: CGFloat
    var fade: CGFloat
    let distance: CGFloat
    let bar: CGRect?
    let size: CGSize
    let safeArea: EdgeInsets

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(progress, fade) }
        set { (progress, fade) = (newValue.first, newValue.second) }
    }

    /// About the screen's own corners on Face ID iPhones.
    private static let screenRadius: CGFloat = 56

    func body(content: Content) -> some View {
        PlayerHandoff.shared.noteShown(progress)
        let p = min(1, max(0, progress))
        let screen = CGRect(
            x: -safeArea.leading,
            y: -safeArea.top,
            width: size.width + safeArea.leading + safeArea.trailing,
            height: size.height + safeArea.top + safeArea.bottom
        )
        let window: CGRect
        let shift: CGSize
        let radius: CGFloat
        let opacity: CGFloat
        if let bar {
            window = lerp(screen, bar, p)
            shift = CGSize(width: window.midX - screen.midX, height: window.minY - screen.minY)
            radius = p > 0.0005 ? lerp(Self.screenRadius, bar.height / 2, p) : 0
            opacity = 1 - smoothstep(p, from: 0.78, to: 1)
        } else {
            shift = CGSize(width: 0, height: p * distance)
            window = screen.offsetBy(dx: shift.width, dy: shift.height)
            radius = p > 0.0005 ? Self.screenRadius : 0
            opacity = 1
        }
        return content
            .clipShape(CardShape(area: window.offsetBy(dx: -shift.width, dy: -shift.height), radius: radius))
            .offset(shift)
            .opacity(opacity * (1 - fade))
    }
}

struct NowPlayingChromeFade: ViewModifier {
    @State private var handoff = PlayerHandoff.shared

    func body(content: Content) -> some View {
        content.modifier(ChromeOpacity(progress: handoff.travels ? handoff.progress : 0))
    }
}

private struct ChromeOpacity: ViewModifier, Animatable {
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        content.opacity(1 - smoothstep(progress, from: 0, to: 0.42))
    }
}

private struct Scrim: View, Animatable {
    var progress: CGFloat
    var fade: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(progress, fade) }
        set { (progress, fade) = (newValue.first, newValue.second) }
    }

    var body: some View {
        Color.black.opacity(0.3 * (1 - min(1, max(0, progress))) * (1 - fade))
    }
}

private nonisolated struct CardShape: Shape {
    let area: CGRect
    let radius: CGFloat

    func path(in rect: CGRect) -> Path {
        Path(roundedRect: area.offsetBy(dx: rect.minX, dy: rect.minY), cornerRadius: radius, style: .continuous)
    }
}

/// The cover between the player and the mini player: drawn once at its size in the player and
/// scaled, so its picture is the one already loaded, and nothing is laid out as it moves.
private struct LiftedCover: View, Animatable {
    var progress: CGFloat
    let cover: CoverArt
    let source: PlayerHandoff.Source
    let target: CGRect
    let sinks: Bool

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        let p = min(1, max(0, progress))
        let square = source.square
        let frame = lerp(square, target, p)
        let scale = frame.width / max(1, square.width)
        let radius = lerp(startRadius, CoverImage.radius(for: target.width), p)
        let drawnHeight = lerp(source.drawn.height, square.height, p)
        let shape = RoundedRectangle(cornerRadius: radius / max(scale, 0.01), style: .continuous)
        ZStack(alignment: .topLeading) {
            picture(p)
                .frame(width: square.width, height: drawnHeight, alignment: .top)
                .clipped()
                .mask { feather(p) }
                .clipShape(shape)
                .overlay { shape.stroke(.primary.opacity(isFullCover ? 0.08 * p : 0.08), lineWidth: 1 / max(scale, 0.01)) }
                .scaleEffect(scale, anchor: .topLeading)
                .shadow(color: .black.opacity(shadowOpacity * (1 - p)), radius: shadowRadius * (1 - p), y: shadowY * (1 - p))
                .offset(x: frame.minX, y: frame.minY)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var isFullCover: Bool {
        if case .fullCover = source { true } else { false }
    }

    private var startRadius: CGFloat {
        isFullCover ? 0 : CoverImage.radius(for: source.square.width)
    }

    private var shadowOpacity: Double { isFullCover ? 0 : sinks ? 0.2 : 0.4 }
    private var shadowRadius: CGFloat { sinks ? 14 : 30 }
    private var shadowY: CGFloat { sinks ? 6 : 16 }

    private func picture(_ p: CGFloat) -> some View {
        CoverImage(cover: cover, size: source.square.width, isBare: true)
    }

    @ViewBuilder
    private func feather(_ p: CGFloat) -> some View {
        if case .fullCover(let picture) = source, p < 1 {
            let keep = 1 - p
            FullCoverBackdrop.ramp(start: picture.top * keep, end: picture.bottom * keep, axis: .vertical)
                .mask { FullCoverBackdrop.ramp(start: picture.leading * keep, end: picture.trailing * keep, axis: .horizontal) }
        } else {
            Rectangle()
        }
    }
}

private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat {
    a + (b - a) * t
}

private func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
    CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t), width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
}

private func smoothstep(_ x: CGFloat, from edge0: CGFloat, to edge1: CGFloat) -> CGFloat {
    let t = min(1, max(0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)
}
