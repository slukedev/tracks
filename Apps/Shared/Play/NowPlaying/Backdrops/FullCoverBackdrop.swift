import SwiftUI
import TracksCore

/// Full Cover: the cover itself, as wide as the player, over the same cover blurred to fill the
/// screen, as Music's full-screen artwork is. The sharp cover dissolves into its blurred self
/// along a tall, soft fade that starts below what it's of (see ``FullCoverDissolve``), so there's
/// no seam, step or line: both are the same picture. Where the song's words sit, the blurred
/// cover is dimmed (or lightened) only as far as they need, softly. Still: nothing here moves,
/// so it costs nothing while it plays.
///
/// Where your music server has the album's animated cover (some find Apple Music's), it plays
/// in the cover's place, looping silently, while the music plays.
///
/// With nowhere to put the cover (Up Next is up), it's the cover's colour, as Cover Colour
/// was, so the list reads on it.
struct FullCoverBackdrop: View {
    let art: BackdropArt
    let cover: CoverArt
    /// Where the player's own cover is, in this view's space.
    let focus: CGRect?
    var isPlaying = false
    /// Settings' previews show the still cover only.
    var allowsMotion = true
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(PlayerModel.self) private var player: PlayerModel?
    @Environment(YourMusic.self) private var music: YourMusic?
    @Environment(\.liftsFullCover) private var isLifted
    @AppStorage(MotionCovers.storageKey) private var playsMotion = true
    @State private var motion: URL?
    /// Whether the player has said where its cover is yet: the first time, the cover is simply
    /// there, rather than fading in a beat after the player opens.
    @State private var hasPlacedCover = false

    var body: some View {
        ZStack {
            CoverStage(tint: art.field)
            if let focus {
                GeometryReader { proxy in
                    placed(in: proxy.size, focus: focus)
                }
                .transition(.opacity)
            }
        }
        .animation(hasPlacedCover ? PlayMotion.panel : nil, value: focus == nil)
        .onChange(of: focus == nil) { _, isNil in
            if !isNil { hasPlacedCover = true }
        }
        .task(id: MotionKey(cover: cover, isOn: allowsMotion && playsMotion)) {
            guard allowsMotion, playsMotion, let track = player?.current, track.cover == cover else {
                motion = nil
                return
            }
            let found = await MotionCovers.url(for: track, servers: music?.servers)
            guard !Task.isCancelled else { return }
            motion = found
        }
    }

    private struct MotionKey: Hashable {
        let cover: CoverArt
        let isOn: Bool
    }

    private func placed(in size: CGSize, focus: CGRect) -> some View {
        let direction = Self.direction
        let rect = CoverBleedLayout.frame(in: size, focus: focus, direction: direction, standsOnFloor: art.subjectReachesFoot, fillsHeight: art.fillsHeight)
        let shown = direction == .below ? CoverBleedLayout.shownFrame(of: rect, focus: focus) : rect
        let feathers = Self.feathers(rect: rect, shown: shown, in: size, art: art)
        return ZStack(alignment: .topLeading) {
            blurred(in: size, cover: rect)
            dimming(in: size, cover: rect)
            if !isLifted {
                ZStack(alignment: .top) {
                    CoverImage(cover: cover, size: rect.width, isBare: true)
                    if let motion {
                        MotionCoverView(url: motion, isPlaying: isPlaying)
                            .frame(width: rect.width, height: rect.height)
                    }
                }
                .frame(width: rect.width, height: shown.height, alignment: .top)
                .clipped()
                .mask {
                    Self.ramp(start: feathers.top, end: feathers.bottom, axis: .vertical)
                        .mask { Self.ramp(start: feathers.leading, end: feathers.trailing, axis: .horizontal) }
                }
                .offset(x: shown.minX, y: shown.minY)
            }
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .preference(key: FullCoverPictureKey.self, value: FullCoverPicture(
            shown: shown,
            side: rect.width,
            top: feathers.top,
            bottom: feathers.bottom,
            leading: feathers.leading,
            trailing: feathers.trailing
        ))
    }

    /// The cover blurred: right under the sharp one, at its size and place, so the sharp one
    /// dissolves into exactly itself, carrying on past it as its own edge, and giving way
    /// further out to the blurred cover filling the screen.
    private func blurred(in size: CGSize, cover: CGRect) -> some View {
        let beside = Self.direction == .beside
        let remaining = beside ? size.width - cover.maxX : size.height - cover.maxY
        return ZStack(alignment: .topLeading) {
            Image(decorative: art.soft, scale: 1)
                .resizable()
                .interpolation(.high)
                .aspectRatio(contentMode: .fill)
                .saturation(FullCoverDissolve.saturation)
                .brightness(FullCoverDissolve.brightness)
                .frame(width: size.width, height: size.height)
                .clipped()
            carried(art.soft, edges: art.softEdges, in: size, cover: cover, reach: max(cover.width * 0.45, remaining * 0.6))
            carried(art.clear, edges: art.clearEdges, in: size, cover: cover, reach: cover.width * 0.4)
        }
        .frame(width: size.width, height: size.height)
        .clipped()
    }

    /// A softened copy of the cover at its size and place, carried on past its edge as that
    /// edge, softening along its length as it goes, and fading out over `reach` beyond it.
    private func carried(_ picture: CGImage, edges: EdgeStrips, in size: CGSize, cover: CGRect, reach: CGFloat) -> some View {
        let beside = Self.direction == .beside
        let strip = CGRect(
            x: beside ? cover.maxX : cover.minX,
            y: beside ? cover.minY : cover.maxY,
            width: beside ? max(0, size.width - cover.maxX) : cover.width,
            height: beside ? cover.height : max(0, size.height - cover.maxY)
        )
        func edge(_ image: CGImage?) -> some View {
            Group {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: strip.width, height: strip.height)
                }
            }
        }
        let widening = cover.width * 0.15 / max(1, beside ? strip.width : strip.height)
        return ZStack(alignment: .topLeading) {
            Image(decorative: picture, scale: 1)
                .resizable()
                .interpolation(.high)
                .frame(width: cover.width, height: cover.height)
                .offset(x: cover.minX, y: cover.minY)
            ZStack {
                edge(beside ? edges.trailing : edges.foot)
                edge(beside ? edges.wideTrailing : edges.wideFoot)
                    .mask {
                        LinearGradient(
                            stops: (0...8).map { step in
                                let t = Double(step) / 8
                                return .init(color: .black.opacity(FullCoverDissolve.smootherstep(t)), location: widening * t)
                            },
                            startPoint: beside ? .leading : .top,
                            endPoint: beside ? .trailing : .bottom
                        )
                    }
            }
            .frame(width: strip.width, height: strip.height)
            .offset(x: strip.minX, y: strip.minY)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .mask {
            let length = beside ? size.width : size.height
            let end = beside ? cover.maxX : cover.maxY
            LinearGradient(
                stops: (0...12).map { step in
                    let t = Double(step) / 12
                    return .init(color: .black.opacity(1 - FullCoverDissolve.smootherstep(t)), location: min(1, (end + reach * t) / max(1, length)))
                },
                startPoint: beside ? .leading : .top,
                endPoint: beside ? .trailing : .bottom
            )
        }
    }

    /// Where the song's words sit, the blurred cover dimmed under white type or lightened under
    /// dark, only as far as they need to read, easing in softly from the cover.
    @ViewBuilder
    private func dimming(in size: CGSize, cover: CGRect) -> some View {
        let ink = art.bleed?.ink(CoverBleedLayout.edge(for: Self.direction)) ?? .light
        let amount = FullCoverDissolve.dimming(under: art.underWords, ink: ink, increasedContrast: contrast == .increased)
        if amount > 0.005 {
            let colour: Color = ink == .light ? .black : .white
            let horizontal = Self.direction == .beside
            let from = horizontal ? cover.maxX : cover.maxY
            let to = from + max(140, cover.width * 0.35)
            let length = horizontal ? size.width : size.height
            LinearGradient(
                stops: (0...10).map { step in
                    let t = Double(step) / 10
                    let location = (from + (to - from) * t) / max(1, length)
                    return .init(color: colour.opacity(amount * FullCoverDissolve.smootherstep(t)), location: min(1, max(0, location)))
                },
                startPoint: horizontal ? .leading : .top,
                endPoint: horizontal ? .trailing : .bottom
            )
            .frame(width: size.width, height: size.height)
            .allowsHitTesting(false)
        }
    }

    static var direction: CoverBleedLayout.Direction {
        #if os(macOS)
        .beside
        #else
        .below
        #endif
    }

    /// How far into the cover it dissolves at each edge, as a part of its side: on the song's
    /// side from below its subject (see ``FullCoverDissolve``), a little on any other edge with
    /// screen past it, and not at all where it meets the screen's edge.
    static func feathers(rect: CGRect, shown: CGRect, in size: CGSize, art: BackdropArt) -> (top: Double, bottom: Double, leading: Double, trailing: Double) {
        let songSide = direction == .below
            ? [art.subject, art.focus].compactMap { $0.map { Double($0.maxY) } }.max()
            : [art.subject, art.focus].compactMap { $0.map { Double($0.maxX) } }.max()
        let song = 1 - FullCoverDissolve.start(subjectEnd: songSide)
        func elsewhere(_ hasRoom: Bool) -> Double { hasRoom ? featherElsewhere : 0 }
        switch direction {
        case .below:
            return (elsewhere(shown.minY > 0.5), song, elsewhere(shown.minX > 0.5), elsewhere(shown.maxX < size.width - 0.5))
        case .beside:
            return (elsewhere(shown.minY > 0.5), elsewhere(shown.maxY < size.height - 0.5), elsewhere(shown.minX > 0.5), song)
        }
    }

    static let featherElsewhere = 0.07

    /// Opaque in the middle, easing to clear over `start` at the start and `end` at the end,
    /// along a smootherstep, so the fade has no visible line where it begins or ends.
    static func ramp(start: Double, end: Double, axis: Axis) -> LinearGradient {
        var stops: [Gradient.Stop] = []
        let steps = 12
        if start > 0 {
            for step in 0...steps {
                let t = Double(step) / Double(steps)
                stops.append(.init(color: .black.opacity(smooth(t)), location: start * t))
            }
        } else {
            stops.append(.init(color: .black, location: 0))
        }
        if end > 0 {
            for step in 0...steps {
                let t = Double(step) / Double(steps)
                stops.append(.init(color: .black.opacity(smooth(1 - t)), location: 1 - end + end * t))
            }
        } else {
            stops.append(.init(color: .black, location: 1))
        }
        return axis == .vertical
            ? LinearGradient(stops: stops, startPoint: .top, endPoint: .bottom)
            : LinearGradient(stops: stops, startPoint: .leading, endPoint: .trailing)
    }

    static func smooth(_ t: Double) -> Double { FullCoverDissolve.smootherstep(t) }
}

extension BackdropArt {
    /// Whether what the cover is of runs off its bottom edge, as a figure cut off at the waist.
    var subjectReachesFoot: Bool { (subject?.maxY ?? 0) > 0.95 }

    /// Whether Full Cover beside the song wants the window's whole height: a photo or a picture
    /// that runs to its top or bottom edge, which would otherwise end in a band of colour. A
    /// cover on a plain backdrop is that backdrop already, and keeps the size of a cover.
    var fillsHeight: Bool {
        guard let bleed else { return false }
        return subjectReachesFoot || max(bleed.texture(.top), bleed.texture(.bottom)) > 0.2
    }
}

extension CoverBleed.Colour {
    var swiftUI: Color { Color(red: red, green: green, blue: blue) }
}

extension CoverBleed.Ink {
    /// The colour scheme whose type is this ink.
    var colorScheme: ColorScheme { self == .dark ? .light : .dark }
}
