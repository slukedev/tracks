package com.luke.tracks.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.speakers.SpeakerKey
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.LocalNowPlayingVisibilityScope
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.nowplaying.WavySeekBar
import com.luke.tracks.ui.nowplaying.clock
import com.luke.tracks.ui.nowplaying.rememberCoverSeed
import com.luke.tracks.ui.play.components.MorphPlayButton
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** The route to a speaker's own page in Settings. */
fun speakerPage(key: SpeakerKey) = SettingsPageRoute("device/${key.serverId}/${key.speakerId}")

/** The speaker a `device/<server>/<speaker>` page names. */
fun parseSpeakerPage(page: String): SpeakerKey? {
    val rest = page.removePrefix("device/").takeIf { it != page } ?: return null
    val speaker = rest.substringAfterLast('/', "").takeIf { it.isNotEmpty() } ?: return null
    val server = rest.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } ?: return null
    return SpeakerKey(server, speaker)
}

/**
 * The full player while a speaker plays: the same cover, title and transport as the phone's
 * own, each control there only when the speaker can do it, and the speaker itself below with
 * its volume, power and group. Pull down to put it away, as the phone's.
 */
@Composable
fun CastNowPlaying(cast: SpeakerUi, model: DevicesViewModel, onClose: () -> Unit) {
    val playing = cast.speaker.nowPlaying ?: return
    val seed = rememberCoverSeed(cast.artwork, playing.album ?: playing.title)
    TracksTheme(dark = LocalDarkTheme.current, seed = seed) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            CastContent(cast, playing, model, onClose)
        }
    }
}

@Composable
private fun CastContent(cast: SpeakerUi, playing: TracksideSpeaker.NowPlaying, model: DevicesViewModel, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val navigator = LocalNavigator.current
    var dragDown by remember { mutableFloatStateOf(0f) }
    var devicesOpen by rememberSaveable { mutableStateOf(false) }
    var groupOpen by rememberSaveable { mutableStateOf(false) }
    val position = rememberSpeakerPosition(cast.speaker) { model.serverNow(cast.key.serverId) }
    val openSpeaker = { key: SpeakerKey ->
        devicesOpen = false
        onClose()
        navigator.open(speakerPage(key))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to lerp(scheme.surfaceContainer, scheme.primaryContainer, 0.7f),
                    0.6f to scheme.surfaceContainer,
                    1f to scheme.surfaceContainerLow,
                ),
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragDown > 160f) onClose()
                        dragDown = 0f
                    },
                    onDragCancel = { dragDown = 0f },
                ) { _, amount -> dragDown = (dragDown + amount).coerceAtLeast(0f) }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
                .graphicsLayer { translationY = dragDown * 0.5f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(28.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(cast.speaker.icon(), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Playing on ${cast.name}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.weight(0.5f))
            CastCover(cast, playing)
            Spacer(Modifier.weight(0.5f))
            Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                Text(playing.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.headlineMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                playing.artist?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            CastSeek(cast, playing, position, model)
            Spacer(Modifier.height(12.dp))
            if (cast.canControl) CastTransport(cast, model)
            Spacer(Modifier.weight(0.3f))
            DeviceArea(cast, model, onGroup = { groupOpen = true }, onSettings = { openSpeaker(cast.key) })
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { devicesOpen = true }) {
                Icon(Icons.Rounded.Devices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Devices")
            }
            Spacer(Modifier.height(8.dp))
        }
        SpeakerSnackbar(model, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }

    if (devicesOpen) DevicesSheet(onDismiss = { devicesOpen = false }, onOpenSpeaker = openSpeaker)
    if (groupOpen) GroupSheet(cast.key, model, onDismiss = { groupOpen = false })
}

/** Says what happened to a command while the players are on screen and Devices isn't open. */
@Composable
fun SpeakerSnackbar(model: DevicesViewModel, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(model) {
        model.messages.collect {
            if (model.sheetOpen) return@collect
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(it.text)
        }
    }
    SnackbarHost(host, modifier)
}

/** The cover, resting a touch smaller while the speaker is paused, as the phone's does. */
@Composable
private fun CastCover(cast: SpeakerUi, playing: TracksideSpeaker.NowPlaying) {
    val motion = MaterialTheme.motionScheme
    val reduced = rememberReducedMotion()
    val rest by animateFloatAsState(if (cast.isPlaying || reduced) 1f else 0.86f, motion.slowSpatialSpec(), label = "rest")
    val visibility = LocalNowPlayingVisibilityScope.current
    Box(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = rest
                scaleY = rest
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .sharedElement("cover:nowplaying", visibility)
                .shadow(if (cast.isPlaying) 24.dp else 8.dp, MaterialTheme.shapes.extraLarge, clip = false),
        ) {
            AnimatedContent(
                targetState = cast.artwork to (playing.itemId ?: playing.title),
                transitionSpec = {
                    if (reduced) fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                    else (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f) + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 1.04f) + fadeOut(motion.fastEffectsSpec()))
                },
                label = "cover",
            ) { (art, _) ->
                Artwork(art, playing.album ?: playing.title, Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge)
            }
        }
    }
}

/**
 * Where the speaker is in its song. With `seek` it scrubs: the bar holds where it was let go
 * until the speaker says it's there (or a few seconds pass), so it never jumps back.
 */
@Composable
private fun CastSeek(cast: SpeakerUi, playing: TracksideSpeaker.NowPlaying, position: State<Long>, model: DevicesViewModel) {
    val duration = playing.durationMs ?: 0
    var held by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(held) {
        val target = held ?: return@LaunchedEffect
        withTimeoutOrNull(4_000) { snapshotFlow { position.value }.first { abs(it - target) < 1_500 } }
        held = null
    }
    val live = { held ?: position.value }
    if (cast.canControl && cast.controls.seek && duration > 0) {
        WavySeekBar(
            live = live,
            durationMs = duration,
            isPlaying = cast.isPlaying,
            onSeek = { ms ->
                held = ms
                model.seek(cast.key, ms)
            },
            modifier = Modifier.widthIn(max = 560.dp),
        )
    } else if (duration > 0) {
        // It can't seek: the time, without a handle to drag.
        Row(Modifier.fillMaxWidth().widthIn(max = 560.dp).semantics(mergeDescendants = true) {}) {
            Text(clock(live().toFloat()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(clock(duration.toFloat()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shuffle, previous, play or pause, next and repeat: each only when the speaker lists it. */
@Composable
private fun CastTransport(cast: SpeakerUi, model: DevicesViewModel) {
    val haptics = LocalHapticFeedback.current
    val controls = cast.controls
    val playback = cast.speaker.playback
    Row(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (controls.shuffle) {
            FilledTonalIconToggleButton(
                checked = playback?.shuffle == true,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    model.setShuffle(cast.key, it)
                },
                shapes = IconButtonDefaults.toggleableShapes(),
            ) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle") }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controls.nextPrevious) {
                FilledTonalIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); model.previous(cast.key) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),
                ) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(IconButtonDefaults.mediumIconSize)) }
            }
            if (controls.playPause) {
                MorphPlayButton(
                    playing = cast.isPlaying,
                    loading = cast.connecting || cast.speaker.stateValue == TracksideSpeaker.State.Buffering,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (cast.isPlaying) model.pause(cast.key) else model.play(cast.key)
                    },
                    size = 80.dp,
                )
            }
            if (controls.nextPrevious) {
                FilledTonalIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); model.next(cast.key) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform)),
                ) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(IconButtonDefaults.mediumIconSize)) }
            }
        }
        if (controls.repeat) {
            val repeat = playback?.repeat ?: "off"
            FilledTonalIconToggleButton(
                checked = repeat != "off",
                onCheckedChange = {
                    val next = when (repeat) {
                        "off" -> "all"
                        "all" -> "one"
                        else -> "off"
                    }
                    haptics.performHapticFeedback(if (next == "off") HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    model.setRepeat(cast.key, next)
                },
                shapes = IconButtonDefaults.toggleableShapes(),
            ) {
                Icon(
                    if (repeat == "one") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = when (repeat) {
                        "one" -> "Repeat one"
                        "all" -> "Repeat all"
                        else -> "Repeat off"
                    },
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/**
 * The speaker under the transport: its name, power, volume (the group's too on a leader),
 * the speakers playing with it, and its page in Settings.
 */
@Composable
fun DeviceArea(cast: SpeakerUi, model: DevicesViewModel, onGroup: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val speaker = cast.speaker
    val controls = cast.controls
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.padding(start = 8.dp).size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(speaker.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(cast.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val together = cast.memberNames.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "With $it" } ?: cast.leaderName?.let { "With $it" }
                    if (together != null) {
                        Text(together, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (cast.canControl && controls.group && cast.groupable.isNotEmpty()) {
                    IconButton(onClick = onGroup) { Icon(Icons.Rounded.SpeakerGroup, contentDescription = "Group speakers") }
                }
                if (cast.canControl && controls.power) {
                    val on = speaker.powered == true
                    IconButton(onClick = {
                        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        model.setPowered(cast.key, !on)
                    }) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = if (on) "Turn off ${cast.name}" else "Turn on ${cast.name}",
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Tune, contentDescription = "${cast.name} settings") }
            }
            if (controls.volume) {
                SpeakerVolume(
                    name = cast.name,
                    volume = speaker.volume ?: 0,
                    muted = speaker.muted.takeIf { controls.mute },
                    onVolume = { model.setVolume(cast.key, it) },
                    onMuted = { model.setMuted(cast.key, it) },
                    enabled = cast.canControl,
                )
            }
            val groupVolume = speaker.groupVolume
            if (controls.volume && speaker.isLeader && groupVolume != null && cast.memberNames.isNotEmpty()) {
                Text(
                    "All speakers",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
                SpeakerVolume(
                    name = "All speakers",
                    volume = groupVolume,
                    muted = null,
                    onVolume = { model.setGroupVolume(cast.key, it) },
                    onMuted = null,
                    enabled = cast.canControl,
                )
            }
        }
    }
}

/** Which speakers play with this one: a check on each, applied as you tap. */
@Composable
fun GroupSheet(key: SpeakerKey, model: DevicesViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Text(
                "Play together",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp).semantics { heading() },
            )
            GroupPicker(key, model)
        }
    }
}

/** The speakers this one may play with, each with a check when it does. */
@Composable
fun GroupPicker(key: SpeakerKey, model: DevicesViewModel) {
    val ui by model.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val section = ui.sections.firstOrNull { it.serverId == key.serverId } ?: return
    val me = section.all.firstOrNull { it.key == key } ?: return
    val candidates = me.groupable.mapNotNull { id -> section.all.firstOrNull { it.speaker.id == id } }
    val leading = me.speaker.isLeader
    val members = if (leading) me.speaker.group?.memberIds.orEmpty().filter { it != me.speaker.id } else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        if (me.speaker.isMember && me.leaderName != null) {
            SegmentedListItem(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    model.leaveGroup(key)
                },
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                supportingContent = { Text("Playing with ${me.leaderName}") },
            ) { Text("Play on its own") }
            Spacer(Modifier.height(12.dp))
        }
        candidates.forEachIndexed { index, other ->
            val checked = other.speaker.id in members
            SegmentedListItem(
                onClick = {
                    haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    val next = if (checked) members - other.speaker.id else members + other.speaker.id
                    model.setMembers(key, next)
                },
                enabled = other.reachable,
                shapes = ListItemDefaults.segmentedShapes(index = index, count = candidates.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    selected = checked
                },
                leadingContent = { Icon(other.speaker.icon(), contentDescription = null) },
                trailingContent = {
                    val shown by animateFloatAsState(if (checked) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "check")
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.graphicsLayer {
                            alpha = shown
                            scaleX = 0.6f + 0.4f * shown
                            scaleY = 0.6f + 0.4f * shown
                        },
                    )
                },
                supportingContent = when {
                    !other.reachable -> ({ Text("Offline") })
                    other.leaderName != null && other.speaker.group?.leaderId != me.speaker.id -> ({ Text("With ${other.leaderName}") })
                    else -> null
                },
            ) { Text(other.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (candidates.isEmpty()) {
            Text(
                "No speakers can play with ${me.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * The mini player while a speaker plays: the same floating toolbar and play button as the
 * phone's, saying where it's playing, its progress drawn along the button's edge every frame.
 */
@Composable
fun CastMiniPlayer(cast: SpeakerUi, model: DevicesViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val playing = cast.speaker.nowPlaying ?: return
    val haptics = LocalHapticFeedback.current
    val navigator = LocalNavigator.current
    val motion = MaterialTheme.motionScheme
    val reduced = rememberReducedMotion()
    val seed = rememberCoverSeed(cast.artwork, playing.album ?: playing.title)
    val position = rememberSpeakerPosition(cast.speaker) { model.serverNow(cast.key.serverId) }
    var devicesOpen by rememberSaveable { mutableStateOf(false) }
    // Rises into place when the speaker takes over, as the phone's does when a song starts.
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = shown,
        modifier = modifier,
        enter = if (reduced) fadeIn(motion.defaultEffectsSpec()) else slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
    ) {
    TracksTheme(dark = LocalDarkTheme.current, seed = seed, animate = true) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .pressable(onClick = onExpand, onLongClick = { devicesOpen = true }, pressedScale = 0.98f, onClickLabel = "Open player"),
            ) {
                Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp)) {
                        androidx.compose.animation.AnimatedVisibility(visible = !navigator.nowPlayingOpen, enter = fadeIn(), exit = fadeOut()) {
                            Artwork(
                                cast.artwork,
                                playing.album ?: playing.title,
                                Modifier.size(44.dp).sharedElement("cover:nowplaying", this),
                                shape = CircleShape,
                                showInitials = false,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(
                        targetState = playing.title to cast.name,
                        transitionSpec = {
                            if (reduced) fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                            else (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(motion.defaultEffectsSpec()))
                                .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 3 } + fadeOut(motion.fastEffectsSpec()))
                        },
                        modifier = Modifier.weight(1f),
                        label = "cast",
                    ) { (title, name) ->
                        Column {
                            Text(title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(cast.speaker.icon(), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(name, style = MaterialTheme.typography.bodySmall, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (cast.canControl && cast.controls.nextPrevious) {
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            model.next(cast.key)
                        }) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
                    }
                }
            }
            if (cast.canControl && cast.controls.playPause) {
                Spacer(Modifier.width(8.dp))
                MorphPlayButton(
                    playing = cast.isPlaying,
                    loading = cast.connecting,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (cast.isPlaying) model.pause(cast.key) else model.play(cast.key)
                    },
                    size = 64.dp,
                    restShape = MaterialShapes.Circle,
                    playingShape = MaterialShapes.Circle,
                    pressedShape = MaterialShapes.Square,
                    shadowElevation = 6.dp,
                    contentDescription = if (cast.isPlaying) "Pause ${cast.name}" else "Play on ${cast.name}",
                ) {
                    playing.durationMs?.takeIf { it > 0 }?.let { CastProgressEdge(position, it, scheme.onPrimary, Modifier.matchParentSize()) }
                }
            }
        }
    }
    }
    if (devicesOpen) {
        DevicesSheet(onDismiss = { devicesOpen = false }, onOpenSpeaker = { key ->
            devicesOpen = false
            navigator.open(speakerPage(key))
        })
    }
}

/** The song's progress along the button's edge. It reads the position only while drawing, so a frame redraws the arc and nothing else. */
@Composable
private fun CastProgressEdge(position: State<Long>, lengthMs: Long, color: Color, modifier: Modifier) {
    Spacer(
        modifier.drawBehind {
            val width = 4.dp.toPx()
            val inset = width / 2 + 4.dp.toPx()
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(color.copy(alpha = 0.24f), 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            val sweep = 360f * (position.value.toFloat() / lengthMs).coerceIn(0f, 1f)
            drawArc(color, -90f, sweep, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
        },
    )
}
