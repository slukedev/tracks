import SwiftUI
import TracksCore
import CoreImage
import CoreImage.CIFilterBuiltins

/// Full Cover: the cover itself, as wide as the player, running out past the player's own
/// cover and giving way to its colours, which carry on to the screen's edges (see
/// ``CoverBleed``). Music's full-bleed artwork.
///
/// The cover fades out over its last few rows into a softened copy of itself, drawn on past its
/// edges as its edge pixels stretched out, which in turn gives way to the edges' colours, which
/// settle into one, deep enough for white type or light enough for dark. Sharp to soft to
/// colour, with no line anywhere. Still: nothing here moves, so it costs nothing while it plays.
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
    @State private var fields = BleedFieldCache()
    @State private var motion: URL?
    /// Whether the player has said where its cover is yet: the first time, the cover is simply
    /// there, rather than fading in a beat after the player opens.
    @State private var hasPlacedCover = false

    var body: some View {
        ZStack {
            CoverStage(tint: art.bleed.map { $0.floor(CoverBleedLayout.edge(for: Self.direction)).swiftUI } ?? art.field)
            if let bleed = art.bleed, let focus {
                GeometryReader { proxy in
                    bled(bleed, in: proxy.size, focus: focus)
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

    private func bled(_ bleed: CoverBleed, in size: CGSize, focus: CGRect) -> some View {
        let direction = Self.direction
        let rect = CoverBleedLayout.frame(in: size, focus: focus, direction: direction, standsOnFloor: art.subjectReachesFoot, fillsHeight: art.fillsHeight)
        let shown = direction == .below ? CoverBleedLayout.shownFrame(of: rect, focus: focus) : rect
        let songEdge = CoverBleedLayout.edge(for: direction)
        var shape = CoverBleedField.Shape(
            size: size,
            cover: shown,
            songEdge: songEdge,
            featherToSong: Self.featherToSong(direction),
            featherElsewhere: Self.featherElsewhere,
            settle: Self.settle(direction),
            pointsPerPixel: Self.pointsPerPixel,
            increasedContrast: contrast == .increased
        )
        // A plain edge (a backdrop, a flat colour) is its colour already, and hands over at
        // once; a textured one (a sea, a crowd, a painting) carries on as itself, softened,
        // further, so the texture fades out rather than stopping at a line.
        shape.reach = 0.06 + 0.16 * bleed.texture(songEdge)
        return ZStack(alignment: .topLeading) {
            if let field = fields.image(for: shape, bleed: bleed, picture: art.clear, at: rect) {
                Image(decorative: field, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: size.width, height: size.height)
            }
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
                .mask { Self.feather(shape) }
                .offset(x: shown.minX, y: shown.minY)
            }
        }
        .overlay(alignment: .top) {
            headVeil(bleed, cover: rect)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .preference(key: FullCoverPictureKey.self, value: FullCoverPicture(
            shown: shown,
            side: rect.width,
            top: shape.feather(.top),
            bottom: shape.feather(.bottom),
            leading: shape.feather(.leading),
            trailing: shape.feather(.trailing)
        ))
    }

    /// A wash under the status bar, black under white type and white under dark, as strong as
    /// the cover's top needs for its words to read over a title or a face there, easing away
    /// below it. Nothing on a cover whose top is plain enough already, nor on the Mac. The top
    /// bar's own controls sit on glass.
    @ViewBuilder
    private func headVeil(_ bleed: CoverBleed, cover: CGRect) -> some View {
        let veil = bleed.headVeil(increasedContrast: contrast == .increased)
        if Self.direction == .below, veil > 0.01 {
            let colour: Color = bleed.ink(.top) == .light ? .black : .white
            let solid = max(0, cover.minY) + cover.width * CoverBleed.headDepth
            let fade = cover.width * 0.1
            LinearGradient(
                stops: (0...6).map { step in
                    let t = Double(step) / 6
                    return .init(color: colour.opacity(veil * (1 - Self.smooth(t))), location: (solid + fade * t) / (solid + fade))
                },
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: solid + fade)
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

    /// How far into the cover it fades, as a part of its side: furthest on the edge the song
    /// sits past, where it gives way to its colours, and only a little on the others.
    static func featherToSong(_ direction: CoverBleedLayout.Direction) -> Double {
        direction == .below ? 0.12 : 0.28
    }
    static let featherElsewhere = 0.07
    /// How far past the cover its edge's colours settle into one, as a part of its side.
    private static func settle(_ direction: CoverBleedLayout.Direction) -> Double {
        direction == .below ? 0.18 : 0.32
    }
    /// The colours around the cover are soft: worked out four points to a pixel, stretched.
    private static let pointsPerPixel = 4.0

    /// The cover's alpha: whole inside, easing to nothing at each edge that has the screen
    /// past it, so it gives way to its own colours there.
    private static func feather(_ shape: CoverBleedField.Shape) -> some View {
        ramp(start: shape.feather(.top), end: shape.feather(.bottom), axis: .vertical)
            .mask { ramp(start: shape.feather(.leading), end: shape.feather(.trailing), axis: .horizontal) }
    }

    /// Opaque in the middle, easing to clear over `start` at the start and `end` at the end,
    /// along a smooth curve so the fade has no visible line where it begins.
    static func ramp(start: Double, end: Double, axis: Axis) -> LinearGradient {
        var stops: [Gradient.Stop] = []
        let steps = 6
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

    static func smooth(_ t: Double) -> Double { t * t * (3 - 2 * t) }
}

/// The last field drawn, kept until the shape around the cover changes. A new cover's is drawn
/// at once, coarsely, so the cover never shows without its colours, then finely off the main
/// thread; a new shape (a window being resized) is drawn off the main thread, the last one
/// stretched to fit meanwhile.
@MainActor
@Observable
final class BleedFieldCache {
    /// Counts the fields finished off the main thread, for the view to redraw with each.
    private(set) var drawn = 0
    @ObservationIgnored private var shape: CoverBleedField.Shape?
    @ObservationIgnored private var bleed: CoverBleed?
    @ObservationIgnored private var picture: CGImage?
    @ObservationIgnored private var frame: CGRect?
    @ObservationIgnored private var image: CGImage?
    @ObservationIgnored private var drawing: Task<Void, Never>?

    /// `frame` is where the whole picture lies, which runs past the part that shows when its
    /// foot is cut short.
    func image(for shape: CoverBleedField.Shape, bleed: CoverBleed, picture: CGImage, at frame: CGRect) -> CGImage? {
        _ = drawn
        guard shape != self.shape || bleed != self.bleed || picture !== self.picture || frame != self.frame else { return image }
        // A small step, as a resize takes each frame, can wait; a new cover, or the player
        // laid out at its size for the first time, can't.
        let isStep = bleed == self.bleed && picture === self.picture && self.shape.map { Self.isSmallStep(from: $0, to: shape) } == true
        (self.shape, self.bleed, self.picture, self.frame) = (shape, bleed, picture, frame)
        if image == nil || !isStep {
            // Drawn finely here, a window's field held the main thread for a third of a second.
            var coarse = shape
            coarse.pointsPerPixel *= Self.coarseness
            image = Self.draw(coarse, bleed: bleed, picture: picture, at: frame)
        }
        drawing?.cancel()
        drawing = Task { [weak self] in
            let made = await OffMainActor.run { Self.draw(shape, bleed: bleed, picture: picture, at: frame) }
            guard !Task.isCancelled, let self else { return }
            self.image = made
            self.drawn += 1
        }
        return image
    }

    private static let coarseness = 4.0

    private static func isSmallStep(from old: CoverBleedField.Shape, to new: CoverBleedField.Shape) -> Bool {
        guard old.size.width > 1, old.size.height > 1 else { return false }
        let width = new.size.width / old.size.width
        let height = new.size.height / old.size.height
        return abs(width - 1) < 0.25 && abs(height - 1) < 0.25
    }

    private nonisolated static let context = CIContext(options: [.useSoftwareRenderer: false])

    /// The field, with the cover softened and drawn on past its edges over it where the field
    /// says, at the field's own size.
    nonisolated private static func draw(_ shape: CoverBleedField.Shape, bleed: CoverBleed, picture: CGImage, at frame: CGRect) -> CGImage? {
        let field = CoverBleedField(bleed: bleed, shape: shape)
        guard let colours = Self.picture(field), let weights = Self.weights(field) else { return nil }
        let extent = CGRect(x: 0, y: 0, width: field.width, height: field.height)
        // The cover's square in the field's pixels, Core Image's way up (from the bottom).
        let scale = CGFloat(field.width) / max(1, shape.size.width)
        let cover = CGRect(
            x: frame.minX * scale,
            y: CGFloat(field.height) - frame.maxY * scale,
            width: frame.width * scale,
            height: frame.height * scale
        )
        let source = CIImage(cgImage: picture)
        // Its edge pixels drawn on outward forever, then softened, so past the edge it carries
        // on as streaks of its own colours that blur together, as Music's does.
        let carried = source.clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: cover.width / source.extent.width, y: cover.height / source.extent.height)
                .concatenating(CGAffineTransform(translationX: cover.minX, y: cover.minY)))
            .applyingGaussianBlur(sigma: Double(cover.width) * (0.02 + 0.03 * shape.reach / 0.22))
            .cropped(to: extent)
        let blend = CIFilter.blendWithMask()
        blend.inputImage = carried
        blend.backgroundImage = CIImage(cgImage: colours)
        blend.maskImage = CIImage(cgImage: weights)
        guard let output = blend.outputImage else { return colours }
        return context.createCGImage(output, from: extent, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)) ?? colours
    }

    nonisolated private static func weights(_ field: CoverBleedField) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(field.continuation) as CFData) else { return nil }
        return CGImage(
            width: field.width,
            height: field.height,
            bitsPerComponent: 8,
            bitsPerPixel: 8,
            bytesPerRow: field.width,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    nonisolated private static func picture(_ field: CoverBleedField) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(field.rgba) as CFData) else { return nil }
        return CGImage(
            width: field.width,
            height: field.height,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: field.width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
        )
    }
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
