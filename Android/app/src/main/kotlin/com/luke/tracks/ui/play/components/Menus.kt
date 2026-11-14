package com.luke.tracks.ui.play.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.luke.tracks.playback.PlayableSong
import uniffi.tracks_engine.Mix

/** A long press on a mix: play it, shuffle it, open it or pin it to the home screen. */
@Composable
fun MixMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    mix: Mix,
    playable: List<PlayableSong>,
    actions: PlayActions,
    onOpen: (() -> Unit)? = null,
    onUnplayable: () -> Unit = {},
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(12.dp, (-12).dp),
        shape = MaterialTheme.shapes.large,
    ) {
        MenuItem(Icons.Rounded.PlayArrow, "Play") {
            onDismiss()
            if (!actions.playMix(mix, playable)) onUnplayable()
        }
        MenuItem(Icons.Rounded.Shuffle, "Shuffle") {
            onDismiss()
            if (!actions.playMix(mix, playable, shuffle = true)) onUnplayable()
        }
        onOpen?.let {
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "See all songs") {
                onDismiss()
                it()
            }
        }
        MenuItem(Icons.Rounded.AddToHomeScreen, "Pin to home screen") {
            onDismiss()
            actions.pinMix(mix)
        }
        MenuItem(Icons.Rounded.Share, "Share") {
            onDismiss()
            actions.shareMix(mix)
        }
    }
}

/** A long press on an artist. */
@Composable
fun ArtistMenu(expanded: Boolean, onDismiss: () -> Unit, name: String, actions: PlayActions, onShare: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large) {
        MenuItem(Icons.Rounded.Person, "Go to artist") {
            onDismiss()
            actions.goToArtist(name)
        }
        MenuItem(Icons.Rounded.Share, "Share") {
            onDismiss()
            onShare()
        }
    }
}

@Composable
fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
