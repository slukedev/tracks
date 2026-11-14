package com.luke.tracks.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** The shell's shared transition scope, so a cover can fly from a list into a page header. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * The Now Playing overlay's visibility scope, for the mini player's cover to grow into the
 * full player's artwork. Null outside the overlay.
 */
val LocalNowPlayingVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks this element as the same thing on both pages of a navigation, keyed by [key] (use the
 * same key on both sides, e.g. "cover:mix:<id>"). A no-op outside the shell, so previews and
 * screenshots are unaffected.
 */
@Composable
fun Modifier.sharedElement(key: Any, scope: AnimatedVisibilityScope? = null): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    // Inside the shell every page sits in NavDisplay, which provides its animated scope. The mini
    // player and Now Playing pass theirs explicitly.
    val visibility = scope ?: LocalNavAnimatedContentScope.current
    return with(shared) {
        this@sharedElement.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
        )
    }
}
