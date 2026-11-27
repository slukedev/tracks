package com.luke.tracks.ui.devices

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.speakers.SpeakerClock
import kotlinx.coroutines.isActive
import java.time.Instant

/** The glyph for a kind of speaker. */
fun TracksideSpeaker.icon(): ImageVector = when (kindValue) {
    TracksideSpeaker.Kind.Group -> Icons.Rounded.SpeakerGroup
    TracksideSpeaker.Kind.StereoPair -> Icons.Rounded.SurroundSound
    TracksideSpeaker.Kind.Phone -> Icons.Rounded.Smartphone
    TracksideSpeaker.Kind.Speaker, TracksideSpeaker.Kind.Unknown -> if (group != null && isLeader) Icons.Rounded.SpeakerGroup else Icons.Rounded.Speaker
}

/**
 * Whether the person turned animations off (Remove animations, or an animator scale of 0):
 * movement then gives way to a fade, so nothing slides or grows.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
}

/**
 * Where the speaker is in its song, worked out every frame from the last position it said
 * and the server's clock, so the bar moves as smoothly as the screen draws. Paused, it
 * doesn't tick at all. Read the value only in the small parts that draw it.
 */
@Composable
fun rememberSpeakerPosition(speaker: TracksideSpeaker, serverNow: () -> Instant): State<Long> {
    val position = remember { mutableLongStateOf(SpeakerClock.position(speaker, serverNow())) }
    LaunchedEffect(speaker.playback, speaker.nowPlaying?.durationMs) {
        position.longValue = SpeakerClock.position(speaker, serverNow())
        if (speaker.playback?.state != "playing") return@LaunchedEffect
        while (isActive) {
            withFrameNanos { position.longValue = SpeakerClock.position(speaker, serverNow()) }
        }
    }
    return position
}

/**
 * A speaker's volume: the slider answers the finger at once and the speaker follows, the
 * repository sending only the latest value as it goes. Adjustable with TalkBack's volume
 * gestures, read as a percentage.
 */
@Composable
fun SpeakerVolume(
    name: String,
    volume: Int,
    muted: Boolean?,
    onVolume: (Int) -> Unit,
    onMuted: ((Boolean) -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastStep by remember { mutableIntStateOf(-1) }
    val shown = dragging ?: (volume / 100f)
    val silent = muted == true || shown == 0f
    val emphasis by animateFloatAsState(if (silent) 0.6f else 1f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "volume")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val icon = if (silent) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp
        if (onMuted != null && muted != null) {
            IconToggleButton(
                checked = muted,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onMuted(it)
                },
                enabled = enabled,
            ) { Icon(icon, contentDescription = if (muted) "Unmute $name" else "Mute $name", modifier = Modifier.graphicsLayer { alpha = emphasis }) }
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(12.dp).graphicsLayer { alpha = emphasis })
        }
        Spacer(Modifier.width(4.dp))
        Slider(
            value = shown,
            onValueChange = { value ->
                dragging = value
                val percent = (value * 100).toInt()
                // A light tick every tenth, so a drag can be felt.
                val step = percent / 10
                if (step != lastStep) {
                    if (lastStep >= 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    lastStep = step
                }
                onVolume(percent)
            },
            onValueChangeFinished = {
                dragging = null
                lastStep = -1
            },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "$name volume"
                    stateDescription = "${(shown * 100).toInt()} percent"
                },
        )
        Spacer(Modifier.width(8.dp))
    }
}
