package com.luke.tracks.ui.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Outer and inner corners of a segmented group, as Android 16's Settings draws them. */
val GroupOuter = 24.dp
val GroupInner = 6.dp
val GroupGap = 2.dp

/** The shape of item [index] of [count] in a segmented group. */
fun segmentedShape(index: Int, count: Int, outer: Dp = GroupOuter, inner: Dp = GroupInner): Shape {
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}

/** Shrinks a little while pressed, on the expressive spring. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A card on the first container tier, with generous corners and even padding. */
@Composable
fun InsightCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: Shape = RoundedCornerShape(GroupOuter),
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A coloured card heading with its icon, as on iOS ("Listening", "Streak"). */
@Composable
fun CardLabel(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmallEmphasized, color = tint)
    }
}

/** A section heading over a group, with an optional action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null && onAction != null) {
            val haptics = LocalHapticFeedback.current
            TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onAction() }) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** An icon sitting in an expressive shape, for highlights and tiles. */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
) {
    Box(
        modifier.size(size).background(container, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** [value], counting up to it when it first shows and gliding when it changes. */
@Composable
fun rememberCountUp(value: Long): Long {
    val animatable = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(value) { animatable.animateTo(value.toFloat(), spec) }
    return animatable.value.toLong()
}

/** A number that counts up to [value] when it first shows and when it changes. */
@Composable
fun CountUpText(
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    format: (Long) -> String = Format::number,
) {
    val animatable = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(value) { animatable.animateTo(value.toFloat(), spec) }
    Text(
        format(animatable.value.toLong()),
        style = style,
        color = color,
        modifier = modifier.semantics { contentDescription = format(value) },
        maxLines = 1,
    )
}

/** Movement since the last period: up, down, new or the same. */
@Composable
fun MovementBadge(rank: UInt, previousRank: UInt?, hasPrevious: Boolean, modifier: Modifier = Modifier) {
    if (!hasPrevious) return
    val scheme = MaterialTheme.colorScheme
    val (label, icon, colors) = when {
        previousRank == null -> Triple("New", null, scheme.primary to scheme.onPrimary)
        previousRank > rank -> Triple((previousRank - rank).toString(), Icons.Rounded.ArrowUpward, scheme.tertiaryContainer to scheme.onTertiaryContainer)
        previousRank < rank -> Triple((rank - previousRank).toString(), Icons.Rounded.ArrowDownward, scheme.errorContainer to scheme.onErrorContainer)
        else -> Triple("", Icons.Rounded.Remove, scheme.surfaceContainerHighest to scheme.onSurfaceVariant)
    }
    val spoken = when {
        previousRank == null -> "New this period"
        previousRank > rank -> "Up ${previousRank - rank}"
        previousRank < rank -> "Down ${rank - previousRank}"
        else -> "Same place"
    }
    Row(
        modifier
            .heightIn(min = 22.dp)
            .background(colors.first, RoundedCornerShape(50))
            .padding(horizontal = if (label.isEmpty()) 4.dp else 7.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = colors.second, modifier = Modifier.size(14.dp))
        if (label.isNotEmpty()) {
            if (icon != null) Spacer(Modifier.size(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmallEmphasized, color = colors.second)
        }
    }
}

/**
 * Connected toggle buttons, M3 Expressive's segmented choice: the checked one morphs to a
 * pill. Ticks as the choice changes.
 */
@Composable
fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = {
                    if (option != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Loading, Material's expressive way. */
@Composable
fun InsightsLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator()
    }
}

/** A friendly empty page: a shaped icon, a headline, a line or two, and maybe a button. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapeIcon(
            icon,
            shape = MaterialShapes.Cookie12Sided.toShape(),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            size = 112.dp,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(28.dp))
            val haptics = LocalHapticFeedback.current
            FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onAction() }) {
                Text(action)
            }
        }
    }
}

/** Fills the space and centres an empty state in it. */
@Composable
fun CenteredEmpty(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
