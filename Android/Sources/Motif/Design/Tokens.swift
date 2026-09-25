import SwiftUI

/// The Material 3 type scale.
///
/// On Android each style is `MaterialTheme.typography`'s own, with its line height and
/// tracking, and it grows with the person's font size setting. SwiftUI's `.title` and friends
/// map onto these with iOS-sized adjustments, which is why Motif names the Material style.
enum MaterialType: String {
    case displayLarge, displayMedium, displaySmall
    case headlineLarge, headlineMedium, headlineSmall
    case titleLarge, titleMedium, titleSmall
    case bodyLarge, bodyMedium, bodySmall
    case labelLarge, labelMedium, labelSmall

    /// Size and weight from the Material 3 spec, for previews off Android.
    var fallback: Font {
        switch self {
        case .displayLarge: .system(size: 57)
        case .displayMedium: .system(size: 45)
        case .displaySmall: .system(size: 36)
        case .headlineLarge: .system(size: 32)
        case .headlineMedium: .system(size: 28)
        case .headlineSmall: .system(size: 24)
        case .titleLarge: .system(size: 22)
        case .titleMedium: .system(size: 16, weight: .medium)
        case .titleSmall: .system(size: 14, weight: .medium)
        case .bodyLarge: .system(size: 16)
        case .bodyMedium: .system(size: 14)
        case .bodySmall: .system(size: 12)
        case .labelLarge: .system(size: 14, weight: .medium)
        case .labelMedium: .system(size: 12, weight: .medium)
        case .labelSmall: .system(size: 11, weight: .medium)
        }
    }
}

extension View {
    func materialType(_ style: MaterialType) -> some View {
        #if os(Android)
        self.composeModifier { MaterialTypeModifier(style: style.rawValue) }
        #else
        self.font(style.fallback)
        #endif
    }
}

/// The Material 3 shape scale, in dp.
enum Corner {
    /// Chips' inner parts, small badges.
    static let extraSmall: CGFloat = 4
    /// Chips, snackbars, text fields.
    static let small: CGFloat = 8
    /// Cards.
    static let medium: CGFloat = 12
    /// Featured cards, the navigation drawer.
    static let large: CGFloat = 16
    /// Sheets, dialogs, large containers.
    static let extraLarge: CGFloat = 28
}

/// Spacing on Material's 4 dp grid.
enum Space {
    /// Between a screen's edge and its content, and inside cards.
    static let screen: CGFloat = 16
    /// Between cards in a column.
    static let cards: CGFloat = 16
    /// Between cards in a grid.
    static let grid: CGFloat = 12
    /// Between rows of a list.
    static let rows: CGFloat = 8
    /// Between an icon and its text.
    static let iconGap: CGFloat = 16
    /// The smallest target a finger should be asked to hit.
    static let touchTarget: CGFloat = 48
}

#if SKIP
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

struct MaterialTypeModifier : ContentModifier {
    let style: String

    func modify(view: any View) -> any View {
        view.font(Font(fontImpl: { MaterialTextStyles.style(style) }))
    }
}

// SKIP @nobridge
struct MaterialTextStyles {
@Composable static func style(_ style: String) -> androidx.compose.ui.text.TextStyle {
    let type = MaterialTheme.typography
    switch style {
    case "displayLarge": return type.displayLarge
    case "displayMedium": return type.displayMedium
    case "displaySmall": return type.displaySmall
    case "headlineLarge": return type.headlineLarge
    case "headlineMedium": return type.headlineMedium
    case "headlineSmall": return type.headlineSmall
    case "titleLarge": return type.titleLarge
    case "titleMedium": return type.titleMedium
    case "titleSmall": return type.titleSmall
    case "bodyLarge": return type.bodyLarge
    case "bodyMedium": return type.bodyMedium
    case "bodySmall": return type.bodySmall
    case "labelLarge": return type.labelLarge
    case "labelMedium": return type.labelMedium
    case "labelSmall": return type.labelSmall
    default: return type.bodyMedium
    }
}
}
#endif
