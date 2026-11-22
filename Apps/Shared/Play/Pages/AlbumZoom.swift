import SwiftUI
import MusicKit

/// The zoom from a cover into the page it opens, as Music's covers open on iPhone. A tile marks
/// itself with ``SwiftUI/View/zoomSource(id:)`` and the page it pushes with
/// ``SwiftUI/View/zoomDestination(id:)``, both with the same id; ``SwiftUI/View/zoomSpace()``
/// on the stack holds them together. Any `Hashable` id works, so long as the two ends use the
/// same type. On the Mac, where pages push without a zoom, all three do nothing.
extension EnvironmentValues {
    @Entry var zoomNamespace: Namespace.ID?
}

extension View {
    func zoomSpace() -> some View {
        modifier(ZoomSpace())
    }

    func zoomSource(id: (some Hashable)?) -> some View {
        let id: AnyHashable? = if let id { AnyHashable(id) } else { nil }
        return modifier(ZoomSource(id: id))
    }

    func zoomDestination(id: some Hashable) -> some View {
        modifier(ZoomDestination(id: AnyHashable(id)))
    }
}

extension PlayRoute {
    var albumZoomID: MusicItemID? {
        if case .album(let album) = self { album.id } else { nil }
    }
}

extension View {
    func albumZoomSource(_ route: PlayRoute) -> some View {
        zoomSource(id: route.albumZoomID)
    }

    func albumZoomSource(album: Album) -> some View {
        zoomSource(id: album.id)
    }

    func albumZoomDestination(_ album: Album) -> some View {
        zoomDestination(id: album.id)
    }

    func albumZoomSpace() -> some View {
        zoomSpace()
    }
}

private struct ZoomSpace: ViewModifier {
    @Namespace private var namespace

    func body(content: Content) -> some View {
        content.environment(\.zoomNamespace, namespace)
    }
}

private struct ZoomSource: ViewModifier {
    let id: AnyHashable?
    @Environment(\.zoomNamespace) private var namespace

    func body(content: Content) -> some View {
        #if os(iOS)
        if let id, let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
        #else
        content
        #endif
    }
}

private struct ZoomDestination: ViewModifier {
    let id: AnyHashable
    @Environment(\.zoomNamespace) private var namespace

    func body(content: Content) -> some View {
        #if os(iOS)
        if let namespace {
            content.navigationTransition(.zoom(sourceID: id, in: namespace))
        } else {
            content
        }
        #else
        content
        #endif
    }
}
