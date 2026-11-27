package com.luke.tracks.ui.tv.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import androidx.compose.material3.MaterialTheme as M3Theme

/**
 * A panel that slides in from the right over a dimmed page, for the queue and for options.
 * Focus moves into it and can't wander out; Back closes it, and focus goes back to the item
 * that had it before. Place it last in a full-size Box so it draws over the page.
 */
@Composable
fun TvSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    width: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val store = LocalTvFocusStore.current
    val routeKey = LocalTvRouteKey.current
    val ms = TvMotion.PanelMs
    BackHandler(enabled = visible, onBack = onDismiss)

    // Hands focus back as soon as it closes, rather than leaving it in the panel sliding away.
    val opened = remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible && opened.value) {
            withFrameNanos { }
            store.restore(routeKey)
        }
        opened.value = visible
    }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ms, easing = TvMotion.EaseOut)),
            exit = fadeOut(tween(TvMotion.FocusOutMs * 2, easing = TvMotion.EaseOut)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = if (reduced) fadeIn(tween(ms)) else slideInHorizontally(tween(ms, easing = TvMotion.EaseOut)) { it } + fadeIn(tween(ms / 2)),
            exit = if (reduced) fadeOut(tween(ms)) else slideOutHorizontally(tween(ms - 50, easing = TvMotion.EaseOut)) { it } + fadeOut(tween(ms - 50)),
        ) {
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                // The first focusable inside, once the panel has laid out.
                repeat(6) {
                    withFrameNanos { }
                    if (requester.requestFocus()) return@LaunchedEffect
                }
            }
            Column(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .padding(top = 12.dp, bottom = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(M3Theme.colorScheme.surfaceContainer)
                    .padding(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom, start = 28.dp, end = 28.dp)
                    .focusRequester(requester)
                    .focusProperties { onExit = { if (visible) cancelFocusChange() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                content()
            }
        }
    }
}
