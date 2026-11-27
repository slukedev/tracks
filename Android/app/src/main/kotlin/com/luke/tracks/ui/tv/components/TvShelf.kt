package com.luke.tracks.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.theme.TvLayout

/**
 * A titled row of cards. Coming back into the row focuses the card you left, and with a
 * [focusMemory] so does coming back to the page.
 */
@Composable
fun <T> TvShelf(
    title: String?,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    focusMemory: FocusMemory? = null,
    contentPadding: PaddingValues = PaddingValues(start = TvLayout.ContentStart, end = TvLayout.SafeEnd),
    spacing: Dp = TvLayout.ShelfSpacing,
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    Column(modifier) {
        if (title != null) {
            TvSectionTitle(title, Modifier.padding(start = contentPadding.startPadding()))
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(
                start = contentPadding.startPadding(),
                end = contentPadding.endPadding(),
                // Room for the focused card's growth and glow, which the row would clip.
                top = 12.dp,
                bottom = 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.focusRestorer(),
        ) {
            items(items, key = key) { item ->
                if (focusMemory != null) {
                    Box(Modifier.rememberFocus(focusMemory, key(item))) { itemContent(item) }
                } else {
                    itemContent(item)
                }
            }
        }
    }
}

/** A shelf or section heading, in the TV title size. */
@Composable
fun TvSectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

private fun PaddingValues.startPadding(): Dp = calculateLeftPadding(LayoutDirection.Ltr)
private fun PaddingValues.endPadding(): Dp = calculateRightPadding(LayoutDirection.Ltr)
