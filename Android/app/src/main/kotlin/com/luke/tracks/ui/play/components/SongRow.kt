package com.luke.tracks.ui.play.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.play.playsLabel

/**
 * A song in a list: cover, title, artist and how often you've played it. Songs this phone
 * can't play are dimmed and offer "Open in" another app instead of a menu.
 */
@Composable
fun SongRow(
    song: SongRef,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    onOpenElsewhere: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    showPlays: Boolean = true,
    coverSize: Dp = 52.dp,
) {
    val playable = song.playable != null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .pressable(onClick = onClick, onLongClick = onLongClick, pressedScale = 0.98f)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dim = if (playable) 1f else 0.5f
        Box(contentAlignment = Alignment.Center) {
            Artwork(song.artworkUrl, song.coverSeed, Modifier.size(coverSize).alpha(dim), shape = RoundedCornerShape(12.dp))
            if (isCurrent) {
                Icon(Icons.Rounded.Equalizer, contentDescription = "Playing", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).alpha(if (playable) 1f else 0.62f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(song.artistName, song.plays?.takeIf { showPlays }?.let(::playsLabel)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playable) {
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${song.title}")
            }
        } else {
            FilledTonalButton(
                onClick = onOpenElsewhere,
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                modifier = Modifier.padding(end = 8.dp).heightIn(min = 32.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
