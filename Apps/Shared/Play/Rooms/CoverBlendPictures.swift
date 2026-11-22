import SwiftUI

/// The covers' pictures for a blend, in order. A cover from an address comes through
/// ``ArtworkImages``; a song with no cover at all brings the cover Tracks draws for it
/// everywhere else. A cover that can't be had is left out, never stood in for.
enum CoverBlendPictures {
    static let limit = 4

    static func load(_ covers: [CoverArt], pixels: Int) async -> [CGImage] {
        var slots = [CGImage?](repeating: nil, count: covers.count)
        var remote: [(Int, ArtworkImages.Key)] = []
        for (index, cover) in covers.enumerated() {
            switch cover {
            case .url(let address, let seed):
                if let address, let url = URL(string: address) {
                    remote.append((index, ArtworkImages.Key(url: url, pixels: pixels)))
                } else {
                    slots[index] = generated(seed)
                }
            case .artwork(let artwork):
                // A library cover only MusicKit can draw has no picture to blend.
                if let url = CoverImage.loadableURL(of: artwork, pixels: pixels) {
                    remote.append((index, ArtworkImages.Key(url: url, pixels: pixels)))
                }
            }
        }
        await withTaskGroup(of: (Int, CGImage?).self) { group in
            for (index, key) in remote {
                group.addTask { (index, await ArtworkImages.shared.image(for: key)) }
            }
            for await (index, picture) in group {
                slots[index] = picture
            }
        }
        return slots.compactMap(\.self)
    }

    private static func generated(_ seed: String) -> CGImage? {
        let renderer = ImageRenderer(content: GeneratedCover(seed: seed).frame(width: 128, height: 128))
        renderer.scale = 2
        return renderer.cgImage
    }
}
