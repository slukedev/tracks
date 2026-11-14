package com.luke.tracks.ui.settings

import android.os.Build
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

    SettingsScaffold(title = "Appearance") {
        group("Theme", key = "theme") {
            custom {
                ThemeButtons(settings.theme) { choice ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    scope.launch { graph.settings.update { it.copy(theme = choice) } }
                }
            }
        }
        group("Colors", key = "colors") {
            custom {
                val wallpaper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else seededScheme(TracksBrand, dark)
                val tracks = seededScheme(TracksBrand, dark)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorCard(
                        "Wallpaper", wallpaper, selected = settings.colors != ColorChoice.Tracks, modifier = Modifier.weight(1f),
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scope.launch { graph.settings.update { it.copy(colors = ColorChoice.Wallpaper) } }
                    }
                    ColorCard(
                        "Tracks red", tracks, selected = settings.colors == ColorChoice.Tracks, modifier = Modifier.weight(1f),
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scope.launch { graph.settings.update { it.copy(colors = ColorChoice.Tracks) } }
                    }
                }
            }
        }
        footnote("Wallpaper colors match your phone's wallpaper.", key = "note")
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
