package com.luke.tracks.ui.tv.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * The 960 by 540dp frame of a 1080p TV. Focusable content stays inside the overscan-safe
 * area; backgrounds and art bleed to the edges, under the collapsed rail.
 */
object TvLayout {
    val SafeTop = 27.dp
    val SafeBottom = 27.dp
    val SafeEnd = 48.dp

    /** The collapsed rail: 56dp items with 12dp either side, over the left safe margin. */
    val RailCollapsedWidth = 80.dp
    val RailExpandedWidth = 280.dp

    /** Where focusable content starts, clear of the collapsed rail. */
    val ContentStart = RailCollapsedWidth + 28.dp

    val ContentPadding = PaddingValues(start = ContentStart, end = SafeEnd, top = SafeTop, bottom = SafeBottom)

    val CardWidth = 196.dp
    val WideCardWidth = 320.dp
    val ShelfSpacing = 20.dp
    val SectionSpacing = 36.dp
}
