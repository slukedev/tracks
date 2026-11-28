package com.luke.tracks.ui.tv.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luke.tracks.ui.components.StandInCover
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.tvFocusScale

val TvCardShape = RoundedCornerShape(16.dp)

/**
 * A focusable tile with its title underneath, as Google TV lays out a row of covers: the tile
 * grows a little with a primary edge and a soft glow while focused, and the words below
 * brighten. [pending] covers the art with a ring while something it started is on its way.
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = TvLayout.CardWidth,
    aspectRatio: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    pending: Boolean = false,
    shape: Shape = TvCardShape,
    centered: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    image: @Composable BoxScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Column(modifier.width(width), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .tvFocusScale(source, TvMotion.CardFocusScale)
                // The words sit outside the focusable tile, so it carries them for TalkBack.
                .semantics { contentDescription = listOfNotNull(title, subtitle).joinToString(", ") },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = colors.surfaceVariant,
                focusedContainerColor = colors.surfaceVariant,
                pressedContainerColor = colors.surfaceVariant,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(TvMotion.FocusBorderWidth, colors.primary), inset = 1.5.dp, shape = shape),
            ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(colors.primary.copy(alpha = 0.45f), 12.dp)),
            interactionSource = source,
        ) {
            image()
            PendingCover(pending)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) colors.onSurface else colors.onSurface.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant.copy(alpha = if (focused) 1f else 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/** A [TvCard] showing a cover; artists get a round one. */
@Composable
fun TvCoverCard(
    art: Any?,
    seed: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = TvLayout.CardWidth,
    circle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    pending: Boolean = false,
) {
    val shape = if (circle) CircleShape else TvCardShape
    TvCard(
        onClick = onClick,
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        width = width,
        onLongClick = onLongClick,
        pending = pending,
        shape = shape,
        centered = circle,
    ) {
        TvArtwork(art, seed, Modifier.fillMaxSize(), shape = shape, showInitials = !circle)
    }
}

@Composable
private fun BoxScope.PendingCover(pending: Boolean) {
    AnimatedVisibility(
        visible = pending,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(TvMotion.FocusInMs, easing = TvMotion.EaseOut)),
        exit = fadeOut(tween(TvMotion.FocusOutMs, easing = TvMotion.EaseOut)),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 4.dp, trackColor = Color.White.copy(alpha = 0.2f))
        }
    }
}

object TvArtwork {
    /** Card covers: about 196dp at 1080p, decoded no bigger than needed. */
    const val CardPx = 300
    /** Now Playing and detail headers. */
    const val HeroPx = 600
}

/**
 * A cover decoded at [sizePx], so a wall of covers stays small in memory on low-RAM boxes,
 * over the app's stand-in, which shows the name's initials if the cover never arrives (only
 * at card size and up; they don't fit a row's thumbnail).
 */
@Composable
fun TvArtwork(
    url: Any?,
    seed: String,
    modifier: Modifier = Modifier,
    sizePx: Int = TvArtwork.CardPx,
    shape: Shape = RoundedCornerShape(12.dp),
    showInitials: Boolean = sizePx >= TvArtwork.CardPx,
) {
    val context = LocalContext.current
    val missing = url == null || (url is String && url.isBlank())
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.aspectRatio(1f)) {
        StandInCover(seed, showInitials = showInitials && (missing || failed), modifier = Modifier.matchParentSize().clip(shape))
        if (!missing) {
            val request = remember(url, sizePx) {
                ImageRequest.Builder(context).data(url).size(sizePx).crossfade(TvMotion.ScreenMs).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { failed = it is AsyncImagePainter.State.Error },
                modifier = Modifier.matchParentSize().clip(shape),
            )
        }
    }
}
