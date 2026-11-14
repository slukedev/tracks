package com.luke.tracks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.luke.tracks.ui.theme.GoogleSansFlexCondensed

/**
 * A cover: the real one when there is an address and it loads, otherwise a stand-in drawn from
 * the name, so a song without art still has a face of its own and the same face every time.
 */
@Composable
fun Artwork(
    url: Any?,
    seed: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    showInitials: Boolean = true,
) {
    Box(modifier.aspectRatio(1f).clip(shape)) {
        if (url == null || (url is String && url.isBlank())) {
            StandInCover(seed, showInitials)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { StandInCover(seed, showInitials = false) },
                error = { StandInCover(seed, showInitials) },
            )
        }
    }
}

/** Two tones from the name, laid on the theme so stand-ins sit well in light and dark. */
@Composable
fun StandInCover(seed: String, showInitials: Boolean = true, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (top, bottom) = remember(seed, scheme.primary) {
        val hash = seed.hashCode()
        val palette = listOf(
            scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer,
            scheme.primary, scheme.tertiary, scheme.secondary,
        )
        val a = palette[Math.floorMod(hash, palette.size)]
        val b = palette[Math.floorMod(hash / 7, palette.size)]
        a to lerp(b, Color.Black, 0.12f)
    }
    val onColor = if (top.luminance() > 0.5f) Color(0xDD000000) else Color(0xEEFFFFFF)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (showInitials) {
            Text(
                text = initials(seed),
                color = onColor,
                fontFamily = GoogleSansFlexCondensed,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun initials(name: String): String =
    name.split(' ', '-', '&').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "♪" }
