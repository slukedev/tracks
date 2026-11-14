package com.luke.tracks.ui.play.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.play.MixCover
import com.luke.tracks.ui.play.covers
import com.luke.tracks.ui.play.icon
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksTheme
import com.luke.tracks.ui.play.hue
import uniffi.tracks_engine.Mix

/**
 * A mix's cover: four of its albums in a grid, as Google's playlists draw theirs, with the
 * mix's own symbol in a tonal badge. Fewer than four albums shows the first one whole.
 */
@Composable
fun MixArt(
    mix: Mix,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    showBadge: Boolean = true,
    badgeSize: Dp = 36.dp,
) {
    val covers = remember(mix.id, mix.songs.size) { mix.covers }
    MixTheme(mix) {
        Box(modifier.aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.primaryContainer)) {
            CoverCollage(covers)
            if (showBadge) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(badgeSize)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        mix.kind.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(badgeSize * 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
fun CoverCollage(covers: List<MixCover>, modifier: Modifier = Modifier) {
    if (covers.size < 4) {
        val first = covers.firstOrNull()
        Artwork(first?.url, first?.seed ?: "Tracks", modifier.fillMaxSize(), shape = RectangleShape, showInitials = false)
        return
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                for (col in 0 until 2) {
                    val cover = covers[row * 2 + col]
                    Artwork(cover.url, cover.seed, Modifier.weight(1f), shape = RectangleShape, showInitials = false)
                }
            }
        }
    }
}

/** The mix's own colors, from its hue, for its card and its page. */
@Composable
fun MixTheme(mix: Mix, content: @Composable () -> Unit) {
    TracksTheme(dark = LocalDarkTheme.current, seed = mix.kind.hue, content = content)
}
