package com.luke.tracks.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room a page leaves at the end of its list, so the last row can scroll clear of
 * whatever floats over it: the mini player when something is playing, and the gesture bar when
 * the navigation bar has slid away. The shell provides it; pages read [bottomClearance].
 */
val LocalBottomClearance = compositionLocalOf { 112.dp }

val bottomClearance: Dp
    @Composable get() = LocalBottomClearance.current
