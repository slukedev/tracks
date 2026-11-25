package com.luke.tracks.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.navigation.currentNavigator

/** Room under the floating mini player and the navigation bar. */
val BottomRoom: androidx.compose.ui.unit.Dp
    @Composable get() = com.luke.tracks.ui.navigation.bottomClearance

/** The tone of a row's icon circle, as Android 16's Settings colors each row. */
enum class Tone { Primary, Secondary, Tertiary, Error, Neutral }

@Immutable
data class ToneColors(val container: Color, val content: Color)

@Composable
fun Tone.colors(): ToneColors {
    val c = MaterialTheme.colorScheme
    return when (this) {
        Tone.Primary -> ToneColors(c.primaryContainer, c.onPrimaryContainer)
        Tone.Secondary -> ToneColors(c.secondaryContainer, c.onSecondaryContainer)
        Tone.Tertiary -> ToneColors(c.tertiaryContainer, c.onTertiaryContainer)
        Tone.Error -> ToneColors(c.errorContainer, c.onErrorContainer)
        Tone.Neutral -> ToneColors(c.surfaceContainerHighest, c.onSurfaceVariant)
    }
}

/** A Settings page: a large flexible app bar that collapses as you scroll, then the list. */
@Composable
fun SettingsScaffold(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = true,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val navigator = currentNavigator()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = BottomRoom,
            ),
            content = content,
        )
    }
}

/** A titled group of rows, joined like Android 16's Settings: round outside, tight inside. */
fun LazyListScope.group(title: String? = null, key: String? = title, rows: @Composable GroupScope.() -> Unit) {
    item(key = key, contentType = "group") {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).animateItem()) {
            if (title != null) GroupTitle(title)
            Group(rows)
        }
    }
}

@Composable
fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
    )
}

/**
 * Collects a group's rows first, so each knows where it sits (first, middle, last) and takes
 * the matching corners.
 */
class GroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (index: Int, count: Int) -> Unit>()

    fun row(content: @Composable (index: Int, count: Int) -> Unit) {
        rows += content
    }
}

@Composable
fun Group(build: @Composable GroupScope.() -> Unit) {
    val scope = GroupScope()
    scope.build()
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        val count = scope.rows.size
        scope.rows.forEachIndexed { index, row -> row(index, count) }
    }
}

/** A round tonal circle with the row's icon. */
@Composable
fun ToneIcon(icon: ImageVector, tone: Tone, modifier: Modifier = Modifier) {
    val colors = tone.colors()
    Box(
        modifier.size(40.dp).background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
    }
}

/** A row that opens something, with an icon, a title and a line saying where it stands. */
fun GroupScope.link(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Primary,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) = row { index, count ->
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        enabled = enabled,
        leadingContent = leading ?: icon?.let { { ToneIcon(it, tone) } },
        trailingContent = trailing,
        supportingContent = summary?.let { { Text(it) } },
        onLongClick = onLongClick?.let { long -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); long() } },
        colors = rowColors(),
    ) {
        Text(title)
    }
}

/** A row that only tells: an address, a version. [ltr] keeps addresses and codes left to right. */
fun GroupScope.info(title: String, summary: String? = null, ltr: Boolean = false) = row { index, count ->
    val direction = if (ltr) androidx.compose.ui.text.style.TextDirection.Ltr else androidx.compose.ui.text.style.TextDirection.Content
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        supportingContent = summary?.let { { Text(it) } },
        colors = rowColors(),
    ) {
        Text(title, style = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = direction))
    }
}

/** A row with a switch; the whole row toggles it. */
fun GroupScope.toggle(
    title: String,
    checked: Boolean,
    summary: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Primary,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) = row { index, count ->
    val haptics = LocalHapticFeedback.current
    val change = { on: Boolean -> haptics.toggle(on); onCheckedChange(on) }
    SegmentedListItem(
        onClick = { change(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        enabled = enabled,
        leadingContent = leading ?: icon?.let { { ToneIcon(it, tone) } },
        trailingContent = { TracksSwitch(checked, enabled = enabled, onCheckedChange = change) },
        supportingContent = summary?.let { { Text(it) } },
        onLongClick = onLongClick?.let { long -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); long() } },
        colors = rowColors(),
    ) {
        Text(title)
    }
}

/** Any content as a row of the group, with the group's corners and colors. */
fun GroupScope.custom(content: @Composable ColumnScope.() -> Unit) = row { index, count ->
    val shape = ListItemDefaults.segmentedShapes(index = index, count = count).shape
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        content = content,
    )
}

/** Material's switch with a check or a cross in the thumb, as Google's apps draw it. */
@Composable
fun TracksSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

fun HapticFeedback.toggle(on: Boolean) =
    performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

/** Small print under a group. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 20.dp),
    )
}

fun LazyListScope.footnote(text: String, key: String = text) = item(key = key) { Footnote(text, Modifier.animateItem()) }

/** Shows or hides a part of a page with the theme's springs. */
@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) { content() }
}

/** Android 16's grouped rows sit on the container tier above the page. */
@Composable
fun rowColors() = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
)
