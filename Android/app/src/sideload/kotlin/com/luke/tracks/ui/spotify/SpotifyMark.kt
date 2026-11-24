package com.luke.tracks.ui.spotify

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import com.luke.tracks.R

/**
 * Spotify's mark, for attribution. In its own green, or [monochrome]: black on light
 * surfaces and white on dark, the only recolouring Spotify allows.
 */
@Composable
fun SpotifyMark(modifier: Modifier = Modifier, monochrome: Boolean = true, contentDescription: String? = null) {
    val tint = when {
        !monochrome -> Color.Unspecified
        MaterialTheme.colorScheme.surface.luminance() < 0.5f -> Color.White
        else -> Color.Black
    }
    Icon(painterResource(if (monochrome) R.drawable.ic_spotify_mono else R.drawable.ic_spotify), contentDescription = contentDescription, tint = tint, modifier = modifier)
}
