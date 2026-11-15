package com.luke.tracks.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.playback.radio.RadioWords
import com.luke.tracks.playback.radio.RadioWords.isTurningAway
import com.luke.tracks.ui.play.components.pressable
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioSteering

/** The symbol for why a song came up, as the iPhone has one for each. */
val RadioReason.icon: ImageVector
    get() = when (this) {
        RadioReason.NewFind, RadioReason.NewFromYourArtist, is RadioReason.NewFindLike -> Icons.Rounded.AutoAwesome
        RadioReason.OnTheRoad -> Icons.Rounded.DirectionsCar
        RadioReason.AroundNow -> Icons.Rounded.Schedule
        is RadioReason.LeaningInto -> Icons.Rounded.Tune
        is RadioReason.OldFavorite -> Icons.Rounded.History
        RadioReason.MostPlayed -> Icons.Rounded.LocalFireDepartment
        is RadioReason.MoreOf, is RadioReason.MoreLike -> Icons.Rounded.TrackChanges
        RadioReason.Probing -> Icons.Rounded.AltRoute
        is RadioReason.GoesWith -> Icons.Rounded.Link
    }

/**
 * Why a song Tracks Radio picked came up, in a line under it: "New to you", "One of your most
 * played". The symbol sits in a column of its own width, so the words line up whichever symbol
 * leads them.
 */
@Composable
fun PickReasonLabel(reason: RadioReason, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val line = RadioWords.line(reason)
    Row(
        modifier.clearAndSetSemantics { contentDescription = line },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(reason.icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(line, style = MaterialTheme.typography.labelMedium, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** What Tracks Radio is doing about what you've skipped and let play, or how it picks. */
@Composable
fun SteeringNote(steering: RadioSteering?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = steering,
        transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
        label = "steering",
        modifier = modifier,
    ) { shown ->
        Row(verticalAlignment = Alignment.Top) {
            if (shown != null) {
                Icon(
                    if (shown.isTurningAway) Icons.Rounded.AltRoute else Icons.Rounded.TrackChanges,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.padding(top = 2.dp).size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                shown?.let(RadioWords::line) ?: RadioWords.PICKS_AS_IT_GOES,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

/** What Tracks Radio is tuned to, and the way to change it, from the station it changes. */
@Composable
fun TuneRadioCard(summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick = onClick, pressedScale = 0.98f)
            .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Rounded.Tune, contentDescription = null, tint = scheme.primary)
        Column(Modifier.weight(1f)) {
            Text("Tune Tracks Radio", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}
