package com.luke.tracks.ui.tv.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import androidx.compose.material3.MaterialTheme as M3Theme

private val StaticPulse: State<Float> = mutableFloatStateOf(0.75f)
private val LocalPulse = staticCompositionLocalOf { StaticPulse }

/** One slow breath shared by every block of a skeleton; still under reduced motion. */
@Composable
private fun Pulsing(content: @Composable () -> Unit) {
    val pulse = if (LocalReducedMotion.current) {
        StaticPulse
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = TvMotion.EaseInOut), RepeatMode.Reverse),
            label = "pulse",
        )
    }
    CompositionLocalProvider(LocalPulse provides pulse) {
        Box(Modifier.semantics { contentDescription = "Loading" }) { content() }
    }
}

/** A placeholder block in the page's own shape. */
@Composable
fun TvSkeletonBlock(modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val pulse = LocalPulse.current
    Box(
        modifier
            .graphicsLayer { alpha = pulse.value }
            .clip(shape)
            .background(M3Theme.colorScheme.surfaceContainerHigh),
    )
}

/** Stands in for a [TvShelf] of [TvCard]s, at the same sizes and spacing. */
@Composable
fun TvShelfSkeleton(
    modifier: Modifier = Modifier,
    cards: Int = 6,
    cardWidth: Dp = TvLayout.CardWidth,
    aspectRatio: Float = 1f,
    titled: Boolean = true,
) {
    Pulsing { ShelfBlocks(modifier, cards, cardWidth, aspectRatio, titled) }
}

@Composable
private fun ShelfBlocks(modifier: Modifier, cards: Int, cardWidth: Dp, aspectRatio: Float, titled: Boolean) {
    Column(modifier) {
        if (titled) {
            // titleLarge's 28sp line, with the bar at its cap height.
            Box(Modifier.padding(start = TvLayout.ContentStart).height(28.dp), contentAlignment = Alignment.CenterStart) {
                TvSkeletonBlock(Modifier.width(180.dp).height(18.dp), RoundedCornerShape(6.dp))
            }
        }
        Row(
            Modifier.padding(start = TvLayout.ContentStart, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing),
        ) {
            repeat(cards) {
                Column(Modifier.width(cardWidth)) {
                    TvSkeletonBlock(Modifier.fillMaxWidth().aspectRatio(aspectRatio), TvCardShape)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                        TvSkeletonBlock(Modifier.fillMaxWidth(0.72f).height(12.dp), RoundedCornerShape(4.dp))
                    }
                    Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) {
                        TvSkeletonBlock(Modifier.fillMaxWidth(0.48f).height(10.dp), RoundedCornerShape(4.dp))
                    }
                }
            }
        }
    }
}

/** Stands in for a column of [TvListRow]s. */
@Composable
fun TvListSkeleton(modifier: Modifier = Modifier, rows: Int = 8, leading: Boolean = true) {
    Pulsing {
        Column(modifier) {
            repeat(rows) {
                Row(
                    Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (leading) TvSkeletonBlock(Modifier.size(48.dp), RoundedCornerShape(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvSkeletonBlock(Modifier.fillMaxWidth(0.5f).height(14.dp), RoundedCornerShape(4.dp))
                        TvSkeletonBlock(Modifier.fillMaxWidth(0.3f).height(11.dp), RoundedCornerShape(4.dp))
                    }
                }
            }
        }
    }
}

/** A page of shelves while it first loads, optionally under a hero band. */
@Composable
fun TvPageSkeleton(modifier: Modifier = Modifier, hero: Boolean = true, shelves: Int = 2) {
    Pulsing {
        Column(modifier.padding(top = TvLayout.SafeTop), verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing)) {
            if (hero) {
                Column(Modifier.padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvSkeletonBlock(Modifier.width(320.dp).height(36.dp), RoundedCornerShape(8.dp))
                    TvSkeletonBlock(Modifier.fillMaxWidth().height(180.dp), RoundedCornerShape(24.dp))
                }
            }
            repeat(shelves) { ShelfBlocks(Modifier, 6, TvLayout.CardWidth, 1f, true) }
        }
    }
}
