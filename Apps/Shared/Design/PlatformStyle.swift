import SwiftUI

/// The few places where the iPhone and the Mac name the same idea differently. Views say what
/// they mean ("a card's fill", "search under the title") and each platform answers in its own
/// idiom, rather than every view carrying its own `#if`.
extension Color {
    /// Behind a card or a well on the page, the one content surface: a step off the plain
    /// page on iPhone, the quaternary fill on the Mac's window background. Never glass.
    static var cardFill: Color {
        #if os(iOS)
        Color(uiColor: .secondarySystemBackground)
        #else
        Color(nsColor: .quaternarySystemFill)
        #endif
    }

    /// The page itself.
    static var pageBackground: Color {
        #if os(iOS)
        Color(uiColor: .systemBackground)
        #else
        Color(nsColor: .windowBackgroundColor)
        #endif
    }

    /// A placeholder's fill while something loads: a little stronger than a card.
    static var placeholderFill: Color {
        #if os(iOS)
        Color(uiColor: .secondarySystemFill)
        #else
        Color(nsColor: .tertiarySystemFill)
        #endif
    }
}

extension SearchFieldPlacement {
    /// A page's own search: under the large title on iPhone, as Music's library searches are,
    /// and the window's toolbar on the Mac.
    static func pageSearch(alwaysShown: Bool = true) -> SearchFieldPlacement {
        #if os(iOS)
        .navigationBarDrawer(displayMode: alwaysShown ? .always : .automatic)
        #else
        .toolbar
        #endif
    }
}

/// Where Tracks’ access to Apple Music is switched on.
enum SystemSettingsLink {
    /// Settings › Tracks on iPhone; Privacy & Security › Media & Apple Music on the Mac.
    static var musicAccess: URL? {
        #if os(iOS)
        URL(string: UIApplication.openSettingsURLString)
        #else
        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Media")
        #endif
    }
}

extension View {
    /// A field for an address, a user name or a password: no capitals, no corrections, and
    /// the URL keyboard on iPhone when it's an address.
    func literalEntry(isAddress: Bool = false) -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(isAddress ? .URL : .default)
        #else
        autocorrectionDisabled()
        #endif
    }

    /// A field for a name, capitalised as a title is.
    func titleEntry() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.words)
        #else
        self
        #endif
    }

    /// A field for a whole number.
    func numberEntry() -> some View {
        #if os(iOS)
        keyboardType(.numberPad)
        #else
        self
        #endif
    }
}

extension View {
    /// An icon-only menu inside a page: on the Mac, a borderless button with no pop-up
    /// chevron, as the "…" beside an item is in Music. The iPhone's menus look right as they are.
    func iconMenuStyle() -> some View {
        #if os(macOS)
        menuStyle(.button)
            .buttonStyle(.borderless)
            .menuIndicator(.hidden)
            .fixedSize()
        #else
        self
        #endif
    }
}

extension View {
    /// Art or colour behind a page's header that carries on under the bars, and on the Mac
    /// under the sidebar, mirrored and softened by the system, as Music's heroes do. Lay the
    /// header out inside the safe area; the extension fills the rest.
    func heroBackground<Background: View>(alignment: Alignment = .center, @ViewBuilder _ background: () -> Background) -> some View {
        self.background(alignment: alignment) {
            background()
                .backgroundExtensionEffect()
                // Up under the bar from the top of a scroll view, as ArtworkHero's art is.
                .padding(.top, -ArtworkHero<EmptyView>.underBar)
                .accessibilityHidden(true)
        }
    }
}

#if os(macOS)
extension View {
    /// A page whose header runs up under the Mac's title bar. The page's title lives in its
    /// content, so the window's is empty. The bar draws no band of its own: macOS 27's scroll
    /// edge effect softens the content under it once it scrolls there.
    func headerUnderBar() -> some View {
        navigationTitle("")
            .scrollEdgeEffectStyle(.soft, for: .top)
    }
}
#endif

extension View {
    /// What every page of the Mac's main window wears around it: the player's bar at its foot
    /// and your devices at the end of the toolbar. Applied by the page, not the window, since a
    /// pushed page takes the stack's place, and the window's own items would sit beside a
    /// page's centred ones. The iPhone's player lives on its tab bar instead.
    ///
    /// - Parameter isRoot: the stack's first page, the one `-TracksDevices YES` opens on.
    func pageChrome(isRoot: Bool = false) -> some View {
        #if os(macOS)
        ColumnLayout {
            nowPlayingBar()
                .toolbar {
                    ToolbarSpacer(.flexible)
                    ToolbarItem { DevicesButton(opensAtLaunch: isRoot) }
                }
        }
        #else
        self
        #endif
    }
}

extension View {
    /// A chart's axis labels grow with Dynamic Type only so far, as Health's do: past that they
    /// crowd out the bars they label. The words around the chart still grow.
    func chartTextSize() -> some View {
        dynamicTypeSize(...DynamicTypeSize.accessibility1)
    }
}
