package com.luke.tracks.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.theme.TvLayout

/**
 * A page with nothing to show yet: a short title and at most one action, centred in the
 * content area. The action is the page's focus target.
 */
@Composable
fun TvEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            TvButton(
                text = actionLabel,
                onClick = onAction,
                style = TvButtonStyle.Primary,
                modifier = Modifier.padding(top = 8.dp).then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )
        }
    }
}
