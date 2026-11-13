import SwiftUI

/// A Material 3 colour role.
///
/// Tracks follows the phone's dynamic colour, as Android's own apps do: every surface and accent
/// comes from the wallpaper, and changes when it does. SwiftUI only names `primary` (as the
/// accent colour), so the others are read from `MaterialTheme.colorScheme` on Android.
enum MaterialRole: String {
    case surface
    case surfaceContainerLowest, surfaceContainerLow, surfaceContainer
    case surfaceContainerHigh, surfaceContainerHighest
    case primary, onPrimary, primaryContainer, onPrimaryContainer
    case secondary, secondaryContainer, onSecondaryContainer
    case tertiary, tertiaryContainer, onTertiaryContainer
    case onSurface, onSurfaceVariant, outline, outlineVariant
    case error

    /// The nearest system colour, for previews off Android.
    var fallback: Color {
        switch self {
        case .surface, .surfaceContainerLowest: Color.white
        case .surfaceContainerLow, .surfaceContainer: Color.gray.opacity(0.08)
        case .surfaceContainerHigh, .surfaceContainerHighest: Color.gray.opacity(0.16)
        case .primary, .onPrimaryContainer: Color.accentColor
        case .onPrimary: Color.white
        case .primaryContainer: Color.accentColor.opacity(0.18)
        case .secondary, .onSecondaryContainer: Color.purple
        case .secondaryContainer: Color.purple.opacity(0.14)
        case .tertiary, .onTertiaryContainer: Color.teal
        case .tertiaryContainer: Color.teal.opacity(0.16)
        case .onSurface: Color.primary
        case .onSurfaceVariant, .outline: Color.secondary
        case .outlineVariant: Color.gray.opacity(0.3)
        case .error: Color.red
        }
    }
}

extension View {
    /// Fills behind the view with a colour role, in a rounded rectangle.
    ///
    /// Material 3 cards are 12 dp, and the larger containers Android 16 uses for grouped
    /// content are 24 to 28.
    func materialBackground(_ role: MaterialRole, cornerRadius: CGFloat = 0) -> some View {
        #if os(Android)
        self.composeModifier { MaterialBackgroundModifier(role: role.rawValue, cornerRadius: Double(cornerRadius)) }
        #else
        self.background(role.fallback, in: RoundedRectangle(cornerRadius: cornerRadius))
        #endif
    }

    /// Text, symbols and shape fills in a colour role.
    func materialForeground(_ role: MaterialRole) -> some View {
        #if os(Android)
        self.composeModifier { MaterialForegroundModifier(role: role.rawValue) }
        #else
        self.foregroundStyle(role.fallback)
        #endif
    }
}

#if SKIP
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

struct MaterialBackgroundModifier : ContentModifier {
    let role: String
    let cornerRadius: Double

    func modify(view: any View) -> any View {
        let color = Color(colorImpl: { MaterialColors.color(role) })
        if cornerRadius > 0.0 {
            return view.background(color, in: RoundedRectangle(cornerRadius: cornerRadius))
        }
        return view.background(color)
    }
}

struct MaterialForegroundModifier : ContentModifier {
    let role: String

    func modify(view: any View) -> any View {
        view.foregroundStyle(Color(colorImpl: { MaterialColors.color(role) }))
    }
}

// SKIP @nobridge
struct MaterialColors {
@Composable static func color(_ role: String) -> androidx.compose.ui.graphics.Color {
    let scheme = MaterialTheme.colorScheme
    switch role {
    case "surface": return scheme.surface
    case "surfaceContainerLowest": return scheme.surfaceContainerLowest
    case "surfaceContainerLow": return scheme.surfaceContainerLow
    case "surfaceContainer": return scheme.surfaceContainer
    case "surfaceContainerHigh": return scheme.surfaceContainerHigh
    case "surfaceContainerHighest": return scheme.surfaceContainerHighest
    case "primary": return scheme.primary
    case "onPrimary": return scheme.onPrimary
    case "primaryContainer": return scheme.primaryContainer
    case "onPrimaryContainer": return scheme.onPrimaryContainer
    case "secondary": return scheme.secondary
    case "secondaryContainer": return scheme.secondaryContainer
    case "onSecondaryContainer": return scheme.onSecondaryContainer
    case "tertiary": return scheme.tertiary
    case "tertiaryContainer": return scheme.tertiaryContainer
    case "onTertiaryContainer": return scheme.onTertiaryContainer
    case "onSurface": return scheme.onSurface
    case "onSurfaceVariant": return scheme.onSurfaceVariant
    case "outline": return scheme.outline
    case "outlineVariant": return scheme.outlineVariant
    case "error": return scheme.error
    default: return scheme.onSurface
    }
}
}
#endif
