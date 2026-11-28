package com.luke.tracks.ui.sources

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.settings.rowColors

/**
 * Add Source: one row for each kind of source that can still be added. A row starts that
 * source's own flow: the music permission for this device, the server form for a server,
 * a registered source's own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourcePanel(options: List<AddOption>, onClose: () -> Unit, fullScreen: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.then(if (fullScreen) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
        TopAppBar(
            title = { Text("Add source", modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            },
            windowInsets = TopAppBarDefaults.windowInsets.only(),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedListItem(
                    onClick = {
                        onClose()
                        option.onClick()
                    },
                    shapes = ListItemDefaults.segmentedShapes(index, options.size),
                    colors = rowColors(),
                    leadingContent = { SourceGlyph(option.kind, iconRes = option.iconRes) },
                    supportingContent = option.subtitle?.let { { Text(it) } },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = ListItemDefaults.SegmentedGap),
                ) {
                    Text(option.title)
                }
            }
        }
    }
}

/** Window insets with none: the panel places itself. */
private fun androidx.compose.foundation.layout.WindowInsets.only() = androidx.compose.foundation.layout.WindowInsets(0)

/**
 * Whether the person has turned animations off (Settings, Accessibility, Remove animations):
 * then container transforms give way to a plain fade. Compose already makes springs instant
 * when the animator scale is 0.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
