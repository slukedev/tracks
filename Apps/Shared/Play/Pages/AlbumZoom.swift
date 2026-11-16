import SwiftUI
import MusicKit

extension EnvironmentValues {
    @Entry var albumZoom: Namespace.ID?
}

extension PlayRoute {
    var albumZoomID: MusicItemID? {
        if case .album(let album) = self { album.id } else { nil }
    }
}

extension View {
    func albumZoomSource(_ route: PlayRoute) -> some View {
        modifier(AlbumZoomSource(id: route.albumZoomID))
    }

    func albumZoomSource(album: Album) -> some View {
        modifier(AlbumZoomSource(id: album.id))
    }

    func albumZoomDestination(_ album: Album) -> some View {
        modifier(AlbumZoomDestination(id: album.id))
    }

    func albumZoomSpace() -> some View {
        modifier(AlbumZoomSpace())
    }
}

private struct AlbumZoomSpace: ViewModifier {
    @Namespace private var namespace

    func body(content: Content) -> some View {
        content.environment(\.albumZoom, namespace)
    }
}

private struct AlbumZoomSource: ViewModifier {
    let id: MusicItemID?
    @Environment(\.albumZoom) private var namespace

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

private struct AlbumZoomDestination: ViewModifier {
    let id: MusicItemID
    @Environment(\.albumZoom) private var namespace

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
