package com.luke.tracks.ui.tv.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import androidx.compose.material3.MaterialTheme as M3Theme

/** A page's scrolling column, inside the safe area. Panels go beside it, not in it. */
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom, end = 8.dp),
        content = content,
    )
}

/** A heading inside a Settings page. */
@Composable
internal fun PageHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
    )
}

/** A setting that's on or off, as a row with a switch drawn at its end. */
@Composable
internal fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, supporting: String? = null) {
    TvListRow(
        onClick = { onChange(!checked) },
        headline = title,
        supporting = supporting,
        leading = icon?.let { { RowIcon(it) } },
        trailing = { Switch(checked) },
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    )
}

/** One of a few choices, marked when picked. */
@Composable
internal fun ChoiceRow(title: String, supporting: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvListRow(
        onClick = onClick,
        headline = title,
        supporting = supporting,
        selected = selected,
        leading = { RadioMark(selected) },
        modifier = modifier.semantics {
            role = Role.RadioButton
            stateDescription = if (selected) "Selected" else "Not selected"
        },
    )
}

@Composable
internal fun RowIcon(icon: ImageVector) {
    Box(Modifier.size(40.dp).background(M3Theme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = M3Theme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Switch(checked: Boolean) {
    val reduced = LocalReducedMotion.current
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(if (reduced) 0 else TvMotion.FocusInMs, easing = TvMotion.EaseOut), label = "switch")
    val colors = MaterialTheme.colorScheme
    val track = androidx.compose.ui.graphics.lerp(M3Theme.colorScheme.surfaceContainerHighest, colors.primary, on)
    Box(Modifier.size(width = 52.dp, height = 32.dp).background(track, CircleShape).padding(4.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer { translationX = 20.dp.toPx() * on }
                .background(androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.onPrimary, on), CircleShape),
        )
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(24.dp).background(if (selected) colors.primary else M3Theme.colorScheme.outline, CircleShape).padding(if (selected) 7.dp else 2.dp),
    ) {
        Box(Modifier.size(24.dp).background(if (selected) colors.onPrimary else M3Theme.colorScheme.surfaceContainer, CircleShape))
    }
}
