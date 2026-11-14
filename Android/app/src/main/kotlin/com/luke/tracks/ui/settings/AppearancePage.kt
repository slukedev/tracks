package com.luke.tracks.ui.settings

import android.os.Build
import android.app.WallpaperManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import com.luke.tracks.data.PaletteChoice
import com.luke.tracks.data.TRACKS_RED
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.graph
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.theme.seededScheme
import kotlinx.coroutines.launch

/** Light or dark, and where the colors come from, each shown as it will look. */
@Composable
fun AppearancePage() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val dark = LocalDarkTheme.current
    val wallpaperSeeds = remember(context) { wallpaperSeeds(context) }
    fun save(transform: (AppSettings) -> AppSettings) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { graph.settings.update(transform) }
    }

    SettingsScaffold(title = "Appearance") {
        group("Theme", key = "theme") {
            custom { ThemeButtons(settings.theme) { choice -> save { it.copy(theme = choice) } } }
        }
        group("Colors", key = "colors") {
            custom {
                val wallpaper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else seededScheme(TracksBrand, dark)
                val custom = seededScheme(Color(settings.seedColor), dark, settings.paletteStyle)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorCard(
                        "Wallpaper", wallpaper, selected = settings.colors == ColorChoice.Wallpaper, modifier = Modifier.weight(1f),
                    ) { save { it.copy(colors = ColorChoice.Wallpaper) } }
                    ColorCard(
                        "Custom", custom, selected = settings.colors == ColorChoice.Custom, modifier = Modifier.weight(1f),
                    ) { save { it.copy(colors = ColorChoice.Custom) } }
                }
                Reveal(visible = settings.colors == ColorChoice.Custom) {
                    Column(Modifier.padding(top = 24.dp)) {
                        if (wallpaperSeeds.isNotEmpty()) {
                            SwatchLabel("From your wallpaper")
                            SwatchRow(wallpaperSeeds.map { NamedColor("Wallpaper color", it) }, settings, dark) { color ->
                                save { it.copy(colors = ColorChoice.Custom, seedColor = color) }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        SwatchLabel("Colors")
                        SwatchRow(PresetColors, settings, dark) { color ->
                            save { it.copy(colors = ColorChoice.Custom, seedColor = color) }
                        }
                        Spacer(Modifier.height(20.dp))
                        SwatchLabel("Style")
                        StyleButtons(settings.paletteStyle) { style -> save { it.copy(paletteStyle = style) } }
                    }
                }
            }
        }
        footnote(
            if (settings.colors == ColorChoice.Wallpaper) "Tracks uses your phone's colors, and changes with your wallpaper."
            else "Exact uses the color as picked. Tonal, Vibrant and Muted build a palette around it.",
            key = "note",
        )
        group("Dark theme", key = "dark") {
            toggle(
                "Pure black",
                checked = settings.pureBlack,
                summary = "Black backgrounds in the dark theme",
                icon = Icons.Rounded.Contrast,
            ) { on -> scope.launch { graph.settings.update { it.copy(pureBlack = on) } } }
        }
        footnote("Colors also follow the contrast level in Android's accessibility settings.", key = "contrast")
    }
}

/** A color with the name Settings shows for it. */
data class NamedColor(val name: String, val argb: Int)

/** Colors to pick from, spread around the wheel, Tracks red first. */
val PresetColors = listOf(
    NamedColor("Tracks red", TRACKS_RED),
    NamedColor("Tangerine", 0xFFF2711C.toInt()),
    NamedColor("Gold", 0xFFE0A100.toInt()),
    NamedColor("Lime", 0xFF7CB342.toInt()),
    NamedColor("Green", 0xFF2E9E5B.toInt()),
    NamedColor("Teal", 0xFF00897B.toInt()),
    NamedColor("Sky", 0xFF0288D1.toInt()),
    NamedColor("Blue", 0xFF3F6FD8.toInt()),
    NamedColor("Indigo", 0xFF5C4FC4.toInt()),
    NamedColor("Violet", 0xFF8E44AD.toInt()),
    NamedColor("Pink", 0xFFD63384.toInt()),
    NamedColor("Slate", 0xFF5F7A89.toInt()),
)

fun colorName(argb: Int): String = PresetColors.firstOrNull { it.argb == argb }?.name ?: "Your wallpaper's color"

fun styleName(style: PaletteChoice): String = when (style) {
    PaletteChoice.TonalSpot -> "Tonal"
    PaletteChoice.Vibrant -> "Vibrant"
    PaletteChoice.Fidelity -> "Exact"
    PaletteChoice.Neutral -> "Muted"
}

/** The wallpaper's own main colors, as Android offers them under Wallpaper and style. */
private fun wallpaperSeeds(context: android.content.Context): List<Int> = runCatching {
    val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        ?: return@runCatching emptyList<Int>()
    listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
        .map { it.toArgb() }
        .distinct()
}.getOrDefault(emptyList())

@Composable
private fun SwatchLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(colors: List<NamedColor>, settings: AppSettings, dark: Boolean, onPick: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 6,
        modifier = Modifier.fillMaxWidth(),
    ) {
        colors.forEach { color ->
            Swatch(
                color = color,
                style = settings.paletteStyle,
                dark = dark,
                selected = settings.colors == ColorChoice.Custom && settings.seedColor == color.argb,
                modifier = Modifier.weight(1f),
            ) { onPick(color.argb) }
        }
    }
}

/**
 * A color as Android's wallpaper picker draws it: a circle split into the palette's primary,
 * secondary and tertiary. The chosen one turns into a rounded square with a check.
 */
@Composable
private fun Swatch(
    color: NamedColor,
    style: PaletteChoice,
    dark: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = seededScheme(Color(color.argb), dark, style)
    val motion = MaterialTheme.motionScheme
    val corner by animateFloatAsState(if (selected) 30f else 50f, motion.defaultSpatialSpec(), label = "corner")
    val check by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec(), label = "check")
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, motion.defaultEffectsSpec(), label = "ring",
    )
    Box(
        modifier
            .aspectRatio(1f)
            .border(BorderStroke(2.5.dp, ring), RoundedCornerShape(percent = corner.toInt()))
            .padding(4.dp)
            .clip(RoundedCornerShape(percent = corner.toInt()))
            .clickable(onClickLabel = color.name, onClick = onClick)
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = color.name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(color.argb), size = Size(size.width, size.height / 2))
            drawRect(scheme.secondary, topLeft = Offset(0f, size.height / 2), size = Size(size.width / 2, size.height / 2))
            drawRect(scheme.tertiary, topLeft = Offset(size.width / 2, size.height / 2), size = Size(size.width / 2, size.height / 2))
        }
        Box(
            Modifier
                .size(26.dp)
                .scale(check)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}

private val styleOptions = listOf(PaletteChoice.TonalSpot, PaletteChoice.Vibrant, PaletteChoice.Fidelity, PaletteChoice.Neutral)

/** How the picked color spreads into a palette, as a connected button group. */
@Composable
private fun StyleButtons(current: PaletteChoice, onChoose: (PaletteChoice) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        styleOptions.forEachIndexed { index, style ->
            val checked = current == style
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) onChoose(style) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { role = Role.RadioButton; selected = checked },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    styleOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(styleName(style), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private data class ThemeOption(val choice: ThemeChoice, val label: String, val icon: ImageVector)

private val themeOptions = listOf(
    ThemeOption(ThemeChoice.System, "System", Icons.Rounded.BrightnessAuto),
    ThemeOption(ThemeChoice.Light, "Light", Icons.Rounded.LightMode),
    ThemeOption(ThemeChoice.Dark, "Dark", Icons.Rounded.DarkMode),
)

/** A connected button group, as Material 3 Expressive joins single-choice options. */
@Composable
fun ThemeButtons(current: ThemeChoice, onChoose: (ThemeChoice) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        themeOptions.forEachIndexed { index, option ->
            val checked = current == option.choice
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (!checked) onChoose(option.choice) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .semantics { role = Role.RadioButton; selected = checked },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    themeOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Icon(if (checked) Icons.Rounded.Check else option.icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(option.label, maxLines = 1)
            }
        }
    }
}

/** A choice of colors, drawn as a little Tracks screen in those colors. */
@Composable
private fun ColorCard(label: String, scheme: ColorScheme, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(if (selected) 28.dp else 18.dp, motion.defaultSpatialSpec(), label = "corner")
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, motion.defaultEffectsSpec(), label = "ring")
    val badge by animateFloatAsState(if (selected) 1f else 0f, motion.fastSpatialSpec(), label = "badge")
    Column(modifier.semantics { role = Role.RadioButton; this.selected = selected }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), RoundedCornerShape(corner + 4.dp))
                .padding(6.dp)
                .clip(RoundedCornerShape(corner))
                .background(scheme.surfaceContainer)
                .clickable(onClick = onClick),
        ) {
            MiniScreen(scheme)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .scale(badge)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            label,
            style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

@Composable
private fun MiniScreen(scheme: ColorScheme) {
    Column(Modifier.padding(12.dp).fillMaxHeight()) {
        Box(Modifier.width(56.dp).height(10.dp).background(scheme.onSurface.copy(alpha = 0.75f), CircleShape))
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(54.dp).background(scheme.primaryContainer, RoundedCornerShape(14.dp)).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxHeight()) {
                listOf(0.4f, 0.75f, 0.55f, 1f, 0.65f).forEachIndexed { i, h ->
                    Box(
                        Modifier
                            .width(9.dp)
                            .fillMaxHeight(h)
                            .background(if (i == 3) scheme.primary else scheme.onPrimaryContainer.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).background(scheme.primary, CircleShape))
            Box(Modifier.size(22.dp).background(scheme.secondary, CircleShape))
            Box(Modifier.size(22.dp).background(scheme.tertiary, CircleShape))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(26.dp).background(scheme.surfaceContainerHighest, CircleShape).padding(4.dp)) {
            Box(Modifier.width(34.dp).fillMaxHeight().background(scheme.secondaryContainer, CircleShape))
        }
    }
}
