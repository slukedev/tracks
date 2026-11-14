package com.luke.tracks.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.sharedElement

/** Room under lists for the floating mini player. */
val MiniPlayerClearance = 96.dp

/** Springs to a little smaller while pressed, the expressive way. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Tap, and press and hold with the long-press haptic, as in Google's own apps. */
@Composable
fun Modifier.tapAndHold(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    indication: Boolean = true,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return combinedClickable(
        interactionSource = interaction,
        indication = if (indication) ripple() else null,
        onClick = onClick,
        onLongClickLabel = if (onLongClick != null) "More options" else null,
        onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
    )
}

/** The shared element key for an album's cover, the same in the grid and the album page. */
fun albumCoverKey(albumId: String) = "cover:album:$albumId"
fun artistAvatarKey(artistId: String) = "avatar:artist:$artistId"

@Composable
fun AlbumCell(
    album: LibraryAlbum,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtist: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .tapAndHold(interaction, onClick, onLongClick)
            .padding(6.dp),
    ) {
        Artwork(
            url = album.artworkUri,
            seed = album.title + album.artistName,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().sharedElement(albumCoverKey(album.id)),
        )
        Spacer(Modifier.padding(top = 10.dp))
        Text(
            album.title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (showArtist) {
            Text(
                album.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else if (album.year != null) {
            Text(
                "${album.year}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** One song: cover or track number, title, who and where from, and a menu. */
@Composable
fun SongRow(
    song: PlayableSong,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    showAlbum: Boolean = true,
    /** On an album page: the album's artist, left unsaid on songs that are theirs. */
    albumArtist: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .tapAndHold(interaction, onClick, onMore)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary)
                else Text("$trackNumber", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Artwork(
                url = song.artworkUri,
                seed = (song.albumTitle ?: song.title) + song.artistName,
                shape = RoundedCornerShape(12.dp),
                showInitials = false,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = if (showAlbum && !song.albumTitle.isNullOrBlank()) "${song.artistName} · ${song.albumTitle}" else song.artistName
            if (albumArtist == null || !song.artistName.equals(albumArtist, ignoreCase = true)) Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent && trackNumber == null) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${song.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An artist's face: their newest cover, cut to an expressive shape of their own. */
@Composable
fun ArtistAvatar(artist: LibraryArtist, size: Dp, modifier: Modifier = Modifier) {
    val shapes = listOf(
        MaterialShapes.Cookie9Sided, MaterialShapes.Cookie12Sided, MaterialShapes.Clover8Leaf,
        MaterialShapes.SoftBurst, MaterialShapes.Cookie7Sided, MaterialShapes.Circle,
    )
    val shape: Shape = shapes[Math.floorMod(artist.name.hashCode(), shapes.size)].toShape()
    val art = artist.albums.firstNotNullOfOrNull { it.artworkUri }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Artwork(url = art, seed = artist.name, shape = shape, showInitials = false, modifier = Modifier.fillMaxSize())
        if (art == null) {
            // No cover to show: their initial, centered in the shape.
            Text(
                artist.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "\u266A",
                style = MaterialTheme.typography.displaySmallEmphasized,
                fontSize = (size.value * 0.42f).sp,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

@Composable
fun ArtistRow(artist: LibraryArtist, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .tapAndHold(interaction, onClick, onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistAvatar(artist, 60.dp, Modifier.pressScale(interaction, 0.92f).sharedElement(artistAvatarKey(artist.id)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val albums = artist.albums.joinToString(", ") { it.title }
            if (albums.isNotEmpty()) {
                Text(
                    albums,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Play and Shuffle, side by side as a connected pair that changes shape as you press. */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        Button(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPlay() },
            shapes = ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape),
            contentPadding = ButtonDefaults.MediumContentPadding,
            modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
            Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
            Text("Play", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onShuffle() },
            shapes = ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape),
            contentPadding = ButtonDefaults.MediumContentPadding,
            modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight),
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
            Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
            Text("Shuffle", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A small heading over a section, in the style of Google's apps. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)),
    )
}
