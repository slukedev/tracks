package com.luke.tracks.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * The top of a page: an expressive shape holding the page's icon, where it stands, a line on
 * what that means, and the one thing to do about it.
 */
@Composable
fun StatusHero(
    icon: ImageVector,
    shape: RoundedPolygon,
    tone: Tone,
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = tone.colors()
    val spin by animateFloatAsState(headline.hashCode() % 45f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "spin")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Surface(
                    color = colors.container,
                    shape = shape.toShape(),
                    modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = spin },
                ) {}
                Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(16.dp))
            val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = headline to body,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                label = "status",
            ) { (h, b) ->
                Column {
                    Text(h, style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(b, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actions != null) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}
