package com.luke.tracks.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.speakers.ServerSpeakers
import com.luke.tracks.speakers.SpeakerKey
import com.luke.tracks.system.isTv
import com.luke.tracks.ui.nowplaying.openOutputSwitcher

/**
 * Devices: where the music plays. This phone first, then each server's speakers, then the
 * system's own outputs (Bluetooth, wired, Cast) through Android's switcher. A tap on a speaker
 * moves the queue there with a firm tick, and the row shows it connecting until the speaker
 * answers. A server without speakers adds nothing; one that can't reach them says so quietly.
 */
@Composable
fun DevicesSheet(onDismiss: () -> Unit, onOpenSpeaker: (SpeakerKey) -> Unit) {
    val model = devicesViewModel()
    val ui by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val sheet = rememberModalBottomSheetState()
    val messages = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.opened() }
    DisposableEffect(model) {
        model.sheetOpen = true
        onDispose { model.sheetOpen = false }
    }
    LaunchedEffect(model) {
        model.messages.collect {
            messages.currentSnackbarData?.dismiss()
            messages.showSnackbar(it.text)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box {
            LazyColumn(
                Modifier.fillMaxWidth().navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                item(key = "title") {
                    Text(
                        "Play on",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp).semantics { heading() },
                    )
                }
                item(key = "phone") {
                    DeviceRow(
                        icon = Icons.Rounded.Smartphone,
                        title = "This phone",
                        subtitle = null,
                        chosen = ui.active == null,
                        connecting = false,
                        enabled = true,
                        index = 0,
                        count = 1,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (ui.active != null) model.bringHere()
                        },
                    )
                }
                ui.sections.forEach { section ->
                    item(key = "section:${section.serverId}") {
                        SectionTitle(section.title ?: "Speakers")
                    }
                    val calm = calmLine(section)
                    if (calm != null) {
                        item(key = "calm:${section.serverId}") { CalmRow(calm, Modifier.animateItem()) }
                    }
                    itemsIndexed(section.rows, key = { _, row -> "${row.key.serverId}/${row.key.speakerId}" }) { index, row ->
                        DeviceRow(
                            icon = row.speaker.icon(),
                            title = if (row.memberNames.isEmpty()) row.name else (listOf(row.name) + row.memberNames).joinToString(" + "),
                            subtitle = subtitle(row),
                            chosen = row.chosen,
                            connecting = row.connecting,
                            enabled = row.reachable,
                            index = index,
                            count = section.rows.size,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (row.canControl && row.controls.play) model.castTo(row.key) else model.choose(row.key)
                            },
                            trailing = {
                                IconButton(onClick = { onOpenSpeaker(row.key) }) {
                                    Icon(Icons.Rounded.Tune, contentDescription = "${row.name} settings")
                                }
                            },
                        )
                    }
                }
                // A TV has no output switcher to open.
                if (!context.isTv) item(key = "outputs") {
                    Column {
                    SectionTitle("Other outputs")
                    DeviceRow(
                        icon = Icons.Rounded.BluetoothAudio,
                        title = "Bluetooth and more",
                        subtitle = null,
                        chosen = false,
                        connecting = false,
                        enabled = true,
                        index = 0,
                        count = 1,
                        onClick = { openOutputSwitcher(context) },
                    )
                    }
                }
            }
            SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** What a speaker is doing, in as few words as will do. */
private fun subtitle(row: SpeakerUi): String? {
    val speaker = row.speaker
    val playing = speaker.nowPlaying
    return when {
        row.connecting -> "Connecting"
        row.availability == TracksideSpeaker.Availability.Unavailable -> "Offline"
        row.availability == TracksideSpeaker.Availability.PoweredOff -> "Off"
        row.leaderName != null -> "With ${row.leaderName}"
        playing != null && speaker.stateValue != TracksideSpeaker.State.Idle ->
            listOfNotNull(playing.title.takeIf { it.isNotBlank() }, playing.artist).joinToString(" · ").ifBlank { null }
        else -> null
    }
}

private fun calmLine(section: DeviceSection): String? = when (section.status) {
    ServerSpeakers.Status.Loading -> if (section.rows.isEmpty()) "Looking for speakers" else null
    ServerSpeakers.Status.Connecting -> if (section.rows.isEmpty()) "Looking for speakers" else null
    ServerSpeakers.Status.Unavailable -> "Speakers aren't available right now"
    ServerSpeakers.Status.Unreachable -> "Can't reach ${section.serverName} right now"
    ServerSpeakers.Status.Ok -> if (section.rows.isEmpty()) "No speakers yet" else null
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun CalmRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

/**
 * One place to play: its glyph in a tonal circle that fills with the accent once chosen, and
 * a spinner in its place while connecting. Pressing it gives the list's own spring.
 */
@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    chosen: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val reduced = rememberReducedMotion()
    val dim by animateFloatAsState(if (enabled) 1f else 0.5f, motion.defaultEffectsSpec(), label = "dim")
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = scheme.surfaceContainer),
        modifier = modifier
            .heightIn(min = 64.dp)
            .graphicsLayer { alpha = dim }
            .semantics {
                role = Role.Button
                selected = chosen
                stateDescription = when {
                    connecting -> "Connecting"
                    chosen -> "Playing here"
                    else -> ""
                }
            },
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (chosen) scheme.primary else scheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = connecting,
                    transitionSpec = {
                        if (reduced) fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                        else (scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f) + fadeIn(motion.fastEffectsSpec()))
                            .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f) + fadeOut(motion.fastEffectsSpec()))
                    },
                    label = "connecting",
                ) { busy ->
                    if (busy) {
                        LoadingIndicator(Modifier.size(28.dp), color = if (chosen) scheme.onPrimary else scheme.onSecondaryContainer)
                    } else {
                        Icon(icon, contentDescription = null, tint = if (chosen) scheme.onPrimary else scheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                    }
                }
            }
        },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chosen && !connecting) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                trailing?.invoke()
            }
        },
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
