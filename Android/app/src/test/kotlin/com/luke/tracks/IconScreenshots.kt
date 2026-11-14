package com.luke.tracks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.junit.Test

/** The launcher icon as Pixel draws it: circle and squircle masks, and the themed icon. */
class IconScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @Composable
    private fun Adaptive(shape: Shape, size: Int = 120) {
        Box(Modifier.size(size.dp).clip(shape)) {
            // The mask shows the middle 72 of the 108dp layers.
            val scale = 108f / 72f
            Image(painterResource(R.drawable.ic_launcher_background), null,
                Modifier.size(size.dp).graphicsLayer { scaleX = scale; scaleY = scale })
            Image(painterResource(R.drawable.ic_launcher_foreground), null,
                Modifier.size(size.dp).graphicsLayer { scaleX = scale; scaleY = scale })
        }
    }

    @Composable
    private fun Themed(size: Int = 120) {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.size(size.dp).clip(CircleShape).background(scheme.primaryContainer)) {
            val scale = 108f / 72f
            Image(painterResource(R.drawable.ic_launcher_monochrome), null,
                Modifier.size(size.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                colorFilter = ColorFilter.tint(scheme.onPrimaryContainer))
        }
    }

    @Test
    fun icon() = snap("icon") {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Adaptive(CircleShape)
                Adaptive(RoundedCornerShape(36.dp))
                Themed()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Adaptive(CircleShape, 56)
                Adaptive(RoundedCornerShape(16.dp), 56)
                Themed(56)
            }
        }
    }
}
