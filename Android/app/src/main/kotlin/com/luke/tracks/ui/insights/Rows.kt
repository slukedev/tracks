package com.luke.tracks.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.luke.tracks.ui.theme.GoogleSansFlexCondensed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.theme.GoogleSansFlexRounded

/** The shape a cover takes for its kind: round-ish for artists, soft squares otherwise. */
@Composable
fun coverShape(target: ItemTarget, small: Boolean = true): Shape = when (target) {
    is ItemTarget.Artist -> if (small) CircleShape else MaterialShapes.Cookie9Sided.toShape()
    else -> RoundedCornerShape(if (small) 10.dp else 28.dp)
}

/**
 * One row of a segmented group: optional rank, cover, title and subtitle, and something on
 * the right. Tap opens the page, press and hold opens the menu.
 */
@Composable
fun InsightRow(
    target: ItemTarget,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    subtitle: String? = target.subtitle,
    shared: Boolean = true,
    extra: List<ExtraAction> = emptyList(),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    coverSize: Dp = 52.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val shape = segmentedShape(index, count)
    ItemActionsBox(
        target = target,
        onClick = onClick ?: { navigator.open(target.route) },
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        extra = extra,
        pressScale = false,
    ) {
        Surface(color = color, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rank != null) {
                    Text(
                        rank.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansFlexRounded,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                val coverModifier = Modifier
                    .size(coverSize)
                    .then(if (shared) Modifier.sharedElement(target.coverKey) else Modifier)
                if (target is ItemTarget.Artist) {
                    ArtistAvatar(target.artworkUrl, target.title, CircleShape, coverModifier, initialsSize = 18.sp)
                } else {
                    Artwork(target.artworkUrl, target.title, modifier = coverModifier, shape = coverShape(target))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        target.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    trailing()
                }
            }
        }
    }
}

/** Play count and movement, stacked on the right of a chart row. */
@Composable
fun PlaysAndMovement(plays: Long, rank: UInt, previousRank: UInt?, hasPrevious: Boolean) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            Format.plays(plays),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MovementBadge(rank, previousRank, hasPrevious)
    }
}

/** An artist as a round face with their name, for horizontal shelves. */
@Composable
fun ArtistCell(target: ItemTarget.Artist, caption: String?, modifier: Modifier = Modifier, index: Int = 0) {
    val navigator = LocalNavigator.current
    val shapes = listOf(
        MaterialShapes.Cookie9Sided, MaterialShapes.Circle, MaterialShapes.Cookie7Sided,
        MaterialShapes.Clover8Leaf, MaterialShapes.Sunny,
    )
    val shape = shapes[Math.floorMod(index, shapes.size)].toShape()
    ItemActionsBox(target, onClick = { navigator.open(target.route) }, modifier = modifier.width(112.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ArtistAvatar(target.artworkUrl, target.name, shape, Modifier.size(96.dp).sharedElement(target.coverKey))
            Spacer(Modifier.height(10.dp))
            Text(
                target.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * An artist's face in an expressive shape. Without a picture, their initials sit in the
 * middle, so a scalloped edge never clips them.
 */
@Composable
fun ArtistAvatar(url: String?, name: String, shape: Shape, modifier: Modifier = Modifier, initialsSize: TextUnit = 30.sp) {
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        Artwork(url, name, modifier = Modifier.fillMaxSize(), shape = RectangleShape, showInitials = url != null)
        if (url == null) {
            // The stand-in's first tone, picked as Artwork picks it, so the initials contrast.
            val scheme = MaterialTheme.colorScheme
            val palette = listOf(
                scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer,
                scheme.primary, scheme.tertiary, scheme.secondary,
            )
            val tone = palette[Math.floorMod(name.hashCode(), palette.size)]
            val light = 0.2126f * tone.red + 0.7152f * tone.green + 0.0722f * tone.blue > 0.5f
            Text(
                initialsOf(name),
                color = if (light) Color(0xDD000000) else Color(0xF2FFFFFF),
                fontFamily = GoogleSansFlexCondensed,
                fontWeight = FontWeight.ExtraBold,
                fontSize = initialsSize,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

fun initialsOf(name: String): String =
    name.split(' ', '-', '&').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "\u266A" }

/** Lets a horizontal shelf run to the screen edges past the page's side padding. */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra * 2, minWidth = constraints.minWidth + extra * 2))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** An album as a cover with its title and caption, for horizontal shelves. */
@Composable
fun AlbumCell(target: ItemTarget.Album, caption: String?, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    ItemActionsBox(target, onClick = { navigator.open(target.route) }, modifier = modifier.width(152.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(6.dp)) {
            Artwork(
                target.artworkUrl, target.title,
                modifier = Modifier.fillMaxWidth().sharedElement(target.coverKey),
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(target.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                caption ?: target.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Play and Shuffle side by side, as on iOS; shown only when something is playable. */
@Composable
fun PlayShuffleButtons(songs: List<PlayableSong>, origin: QueueOrigin, modifier: Modifier = Modifier) {
    if (songs.isEmpty()) return
    val graph = rememberGraph()
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); graph.player.play(songs, origin = origin) },
            modifier = Modifier.weight(1f).height(56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); graph.player.play(songs, origin = origin, shuffle = true) },
            modifier = Modifier.weight(1f).height(56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Rounded.Shuffle, null)
            Spacer(Modifier.width(8.dp))
            Text("Shuffle", style = MaterialTheme.typography.titleMedium)
        }
    }
}
