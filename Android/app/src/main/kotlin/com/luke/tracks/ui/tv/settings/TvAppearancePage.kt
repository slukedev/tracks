package com.luke.tracks.ui.tv.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.PaletteChoice
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.graph
import com.luke.tracks.ui.settings.NamedColor
import com.luke.tracks.ui.settings.PresetColors
import com.luke.tracks.ui.settings.styleName
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.seededScheme
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.tvFocusScale
import kotlinx.coroutines.launch

/** Settings > Appearance: light or dark, where the colours come from, and pure black. */
@Composable
fun TvAppearancePage() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    TvAppearanceContent(settings) { change -> scope.launch { graph.settings.update(change) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvAppearanceContent(settings: AppSettings, update: (transform: (AppSettings) -> AppSettings) -> Unit) {
    PageColumn {
        PageHeading("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOptions.forEach { (choice, label, icon) ->
                TvChip(label, selected = settings.theme == choice, onClick = { update { it.copy(theme = choice) } }, icon = if (settings.theme == choice) Icons.Rounded.Check else icon)
            }
        }
        PageHeading("Colors")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ColorChoice.Wallpaper to "Default", ColorChoice.Custom to "Custom").forEach { (choice, label) ->
                val picked = settings.colors == choice
                TvChip(label, selected = picked, onClick = { update { it.copy(colors = choice) } }, icon = if (picked) Icons.Rounded.Check else null)
            }
        }
        if (settings.colors == ColorChoice.Custom) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                maxItemsInEachRow = 6,
            ) {
                PresetColors.forEach { color ->
                    Swatch(color, settings.paletteStyle, selected = settings.seedColor == color.argb) {
                        update { it.copy(colors = ColorChoice.Custom, seedColor = color.argb) }
                    }
                }
            }
            PageHeading("Style")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleOptions.forEach { style ->
                    val picked = settings.paletteStyle == style
                    TvChip(styleName(style), selected = picked, onClick = { update { it.copy(paletteStyle = style) } }, icon = if (picked) Icons.Rounded.Check else null)
                }
            }
        }
        Box(Modifier.padding(top = 24.dp)) {
            ToggleRow("Pure Black", checked = settings.pureBlack, onChange = { on -> update { it.copy(pureBlack = on) } }, icon = Icons.Rounded.Contrast)
        }
    }
}

private val ThemeOptions = listOf(
    Triple(ThemeChoice.System, "System", Icons.Rounded.BrightnessAuto),
    Triple(ThemeChoice.Light, "Light", Icons.Rounded.LightMode),
    Triple(ThemeChoice.Dark, "Dark", Icons.Rounded.DarkMode),
)

private val StyleOptions = listOf(PaletteChoice.TonalSpot, PaletteChoice.Vibrant, PaletteChoice.Fidelity, PaletteChoice.Neutral)

/** A colour as Android's picker draws it: the colour over its palette's secondary and tertiary. */
@Composable
private fun Swatch(color: NamedColor, style: PaletteChoice, selected: Boolean, onClick: () -> Unit) {
    val scheme = seededScheme(Color(color.argb), LocalDarkTheme.current, style)
    val source = remember { MutableInteractionSource() }
    val ring = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .tvFocusScale(source, TvMotion.CardFocusScale)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = color.name
            },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, pressedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(3.dp, ring.copy(alpha = 0.6f)), inset = 0.dp, shape = CircleShape) else Border.None,
            focusedBorder = Border(BorderStroke(TvMotion.FocusBorderWidth, ring), inset = 0.dp, shape = CircleShape),
        ),
        interactionSource = source,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(color.argb), size = Size(size.width, size.height / 2))
                drawRect(scheme.secondary, topLeft = Offset(0f, size.height / 2), size = Size(size.width / 2, size.height / 2))
                drawRect(scheme.tertiary, topLeft = Offset(size.width / 2, size.height / 2), size = Size(size.width / 2, size.height / 2))
            }
            if (selected) {
                Box(Modifier.size(24.dp).background(scheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
