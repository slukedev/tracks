package com.luke.tracks.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.navigationBarsPadding
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RadioState
import com.luke.tracks.system.isTv
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SettingsPageRoute
import uniffi.tracks_engine.RadioReason
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.playback.ListenCounter
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlaybackBridge
import com.luke.tracks.playback.artistLabel
import com.luke.tracks.playback.hasArtist
import com.luke.tracks.playback.titleLabel
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.RepeatChoice
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNowPlayingVisibilityScope
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.MenuItem
import com.luke.tracks.ui.play.components.MorphPlayButton
import com.luke.tracks.ui.play.components.PlayActions
import com.luke.tracks.ui.play.components.ref
import com.luke.tracks.ui.play.components.rememberPlayActions
import com.luke.tracks.ui.play.playsLabel
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * The full player. Its colors come from the cover, as Pixel's do: a scheme seeded from the art
 * just for this screen. Big artwork that rests a little smaller when paused, a wavy seek bar,
 * the transport as an expressive button group and the song's place in your history.
 */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val state by graph.player.state.collectAsStateWithLifecycle()
    val song = state.current
    val haptics = LocalHapticFeedback.current
    val visibility = LocalNowPlayingVisibilityScope.current

    // A firm tick once the player has finished rising into place.
    LaunchedEffect(visibility) {
        val transition = visibility?.transition ?: return@LaunchedEffect
        snapshotFlow { transition.currentState == EnterExitState.Visible && !transition.isRunning }
            .filter { it }.first()
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }
    // While a speaker plays, the player shows and controls it instead.
    val devices = com.luke.tracks.ui.devices.devicesViewModel()
    val devicesUi by devices.state.collectAsStateWithLifecycle()
    val casting = devicesUi.casting
    LaunchedEffect(song == null && casting == null) { if (song == null && casting == null) onClose() }
    if (casting != null) {
        com.luke.tracks.ui.devices.CastNowPlaying(casting, devices, onClose)
        return
    }
    if (song == null) return

    val seed = rememberCoverSeed(song.artworkUri, song.albumTitle ?: song.title)
    TracksTheme(dark = LocalDarkTheme.current, seed = seed) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            NowPlayingContent(state, song, graph.player, onClose)
        }
    }
}

@Composable
private fun NowPlayingContent(state: PlayerState, song: PlayableSong, player: PlayerController, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val actions = rememberPlayActions()
    var queueOpen by remember { mutableStateOf(false) }
    var devicesOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var dragDown by remember { mutableStateOf(0f) }
    val navigator = LocalNavigator.current
    val devices = com.luke.tracks.ui.devices.devicesViewModel()
    val devicesUi by devices.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Devices when a server has speakers; otherwise the system's own switcher, which a TV doesn't have.
    val onDevices: (() -> Unit)? = when {
        devicesUi.offered -> { { devicesOpen = true } }
        context.isTv -> null
        else -> { { openOutputSwitcher(context) } }
    }
    val radioState by LocalContext.current.graph.radio.state.collectAsStateWithLifecycle()
    // Tracks Radio's own song on now, which can steer what it plays next.
    val radio = radioState.takeIf { state.origin?.kind == QueueOrigin.Kind.Radio }
    val tuneRadio = {
        queueOpen = false
        onClose()
        navigator.open(SettingsPageRoute("radio"))
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
            // Pull down anywhere to put the player away.
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
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
                .graphicsLayer { translationY = dragDown * 0.5f },
        ) {
            val controls: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                TitleBlock(state, song, actions, radio?.reason(song))
                Spacer(Modifier.height(16.dp))
                WavySeekBar(
                    position = player.position,
                    durationMs = state.lengthMs ?: 0,
                    isPlaying = state.isPlaying,
                    onSeek = player::seekTo,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
                SourceBadge(song, onGoToAlbum = { onClose(); it() })
                Spacer(Modifier.height(12.dp))
                Transport(state, player, isStation = radio != null || state.origin?.isStation == true)
            }
            // Wide and short (a tablet, a phone on its side, a foldable open): cover beside the controls.
            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                Column(Modifier.fillMaxSize()) {
                    TopRow(state, song, actions, onClose, radio, player, tuneRadio)
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true)) { Cover(state, song, player) }
                        }
                        Spacer(Modifier.width(32.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            controls()
                            Spacer(Modifier.height(16.dp))
                            BottomActions(state, player, onQueue = { queueOpen = true }, onShare = { actions.share(song.ref()) }.takeUnless { context.isTv }, onDevices = onDevices)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TopRow(state, song, actions, onClose, radio, player, tuneRadio)
                    Spacer(Modifier.weight(0.6f))
                    Cover(state, song, player)
                    Spacer(Modifier.weight(0.6f))
                    controls()
                    Spacer(Modifier.weight(0.4f))
                    BottomActions(state, player, onQueue = { queueOpen = true }, onShare = { actions.share(song.ref()) }.takeUnless { context.isTv }, onDevices = onDevices)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        SkipMessages()
        com.luke.tracks.ui.devices.SpeakerSnackbar(devices, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }

    if (queueOpen) QueueSheet(state, player, onDismiss = { queueOpen = false }, onTuneRadio = tuneRadio)
    if (devicesOpen) {
        com.luke.tracks.ui.devices.DevicesSheet(
            onDismiss = { devicesOpen = false },
            onOpenSpeaker = { key ->
                devicesOpen = false
                onClose()
                navigator.open(com.luke.tracks.ui.devices.speakerPage(key))
            },
        )
    }
}

/** The player's one quiet line when it skips songs it couldn't play, while Now Playing is open. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SkipMessages() {
    val registry = com.luke.tracks.ui.sources.rememberSources()
    val host = remember { SnackbarHostState() }
    LaunchedEffect(registry) {
        registry.skips.collect { skipped ->
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(listOfNotNull(skipped.message, skipped.reason).joinToString("\n"))
        }
    }
    SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
}

/**
 * Where this song is playing from, under the seek bar: there with two or more sources. A tap offers the album. The mark and name fade as songs change.
 */
@Composable
private fun SourceBadge(song: PlayableSong, onGoToAlbum: (() -> Unit) -> Unit) {
    val registry = com.luke.tracks.ui.sources.rememberSources()
    val sources by com.luke.tracks.ui.sources.sourceList()
    val source = sources.firstOrNull { it.id == registry.sourceOf(song.mediaId) } ?: return
    if (sources.size < 2) return
    val index = com.luke.tracks.ui.play.components.rememberLibraryIndex()
    val navigator = LocalNavigator.current
    val album = index.albumIdOf(song)
    var menu by remember { mutableStateOf(false) }
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = album != null, onClickLabel = "More") { menu = true }
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Playing from ${source.name}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.luke.tracks.ui.sources.SourceMark(source, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            AnimatedContent(source.name, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "source") { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
            if (album != null) {
                MenuItem(Icons.Rounded.Album, "Go to album") {
                    menu = false
                    onGoToAlbum { navigator.open(com.luke.tracks.ui.navigation.LibraryAlbumRoute(album)) }
                }
            }
        }
    }
}

@Composable
private fun TopRow(
    state: PlayerState,
    song: PlayableSong,
    actions: PlayActions,
    onClose: () -> Unit,
    radio: RadioState?,
    player: PlayerController,
    onTuneRadio: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            state.origin?.let { origin ->
                Text(
                    origin.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = "Playing from ${origin.title}" },
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                // Steering what Tracks Radio plays next, for its own song on now.
                if (radio != null && radio.isPick(song)) {
                    val asked = song.identity in radio.askedMoreLike
                    DropdownMenuItem(
                        text = { Text(if (asked) "Asked for more like this" else "Play more like this") },
                        leadingIcon = { Icon(if (asked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, contentDescription = null) },
                        enabled = !asked,
                        onClick = {
                            menu = false
                            player.playMoreLikeThis()
                        },
                    )
                    MenuItem(Icons.Rounded.Tune, "Tune Tracks Radio") {
                        menu = false
                        onTuneRadio()
                    }
                }
                if (song.hasArtist) {
                    MenuItem(Icons.Rounded.Person, "Go to artist") {
                        menu = false
                        onClose()
                        actions.goToArtist(song.artistName)
                    }
                }
                if (actions.canHandOff) {
                    MenuItem(Icons.Rounded.Share, "Share") {
                        menu = false
                        actions.share(song.ref())
                    }
                    MenuItem(Icons.AutoMirrored.Rounded.OpenInNew, "Open in another app") {
                        menu = false
                        actions.openElsewhere(song.ref())
                    }
                }
                MenuItem(Icons.Rounded.RemoveCircleOutline, "Hear less of this") {
                    menu = false
                    actions.hearLess(song.identity)
                }
            }
        }
    }
}

/**
 * The cover, big. Swipe it sideways to skip. It breathes ever so slightly while the song
 * plays and settles smaller when paused; at rest nothing moves.
 */
@Composable
private fun Cover(state: PlayerState, song: PlayableSong, player: PlayerController) {
    val haptics = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val rest by animateFloatAsState(if (state.isPlaying) 1f else 0.86f, motion.slowSpatialSpec(), label = "rest")
    val breath = if (state.isPlaying) {
        val transition = rememberInfiniteTransition(label = "breathe")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.018f,
            animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        ).value
    } else 1f
    var swipe by remember { mutableStateOf(0f) }
    val visibility = LocalNowPlayingVisibilityScope.current

    Box(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                val s = rest * breath
                scaleX = s
                scaleY = s
                translationX = swipe * 0.4f
                rotationZ = swipe / 120f
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            swipe < -180f -> player.next().also { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                            swipe > 180f -> player.previous().also { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                        }
                        swipe = 0f
                    },
                    onDragCancel = { swipe = 0f },
                ) { _, amount -> swipe += amount }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .sharedElement("cover:nowplaying", visibility)
                .shadow(if (state.isPlaying) 24.dp else 8.dp, MaterialTheme.shapes.extraLarge, clip = false),
        ) {
            AnimatedContent(
                targetState = song,
                contentKey = { it.mediaId },
                transitionSpec = {
                    (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f) + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 1.04f) + fadeOut(motion.fastEffectsSpec()))
                },
                label = "cover",
            ) { shown ->
                Artwork(shown.artworkUri, shown.albumTitle ?: shown.title, Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge)
            }
        }
    }
}

@Composable
private fun TitleBlock(state: PlayerState, song: PlayableSong, actions: PlayActions, reason: RadioReason?) {
    val graph = LocalContext.current.graph
    val revision by graph.engine.revision.collectAsStateWithLifecycle()
    val plays by produceState<Long?>(null, song.identity, revision) {
        value = runCatching { graph.engine.read { song(song.identity)?.plays?.toLong() ?: 0L } }.getOrNull()
    }
    Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                song.titleLabel,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = if (state.isPlaying) 2 else 0),
            )
            Text(
                song.artistLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (song.hasArtist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (song.hasArtist) {
                    Modifier.clickable(onClickLabel = "Go to artist") { actions.goToArtist(song.artistName) }
                } else Modifier,
            )
            // Why Tracks Radio picked it.
            val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = reason,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                label = "reason",
            ) { shown ->
                if (shown != null) PickReasonLabel(shown, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(top = 4.dp))
            }
            // A song being got to play: how that's going, until it plays.
            val getting by PlaybackBridge.getting.collectAsStateWithLifecycle()
            AnimatedContent(
                targetState = getting?.takeIf { it.mediaId == song.mediaId }?.status,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                label = "getting",
            ) { said ->
                if (said != null) {
                    Text(
                        said,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // A song of 30 seconds or less (a ringtone, a jingle) is never kept: no ring to fill.
        val keepable = remember(state.lengthMs) { ListenCounter.canKeep(state.lengthMs) }
        if (keepable || state.kept) KeepRing(state.keepProgress, state.kept, plays)
    }
}

/**
 * How close this play is to being kept in your history, as a ring that fills, and how often
 * you've played the song.
 */
@Composable
private fun KeepRing(progress: Float, kept: Boolean, plays: Long?) {
    val scheme = MaterialTheme.colorScheme
    val shown by animateFloatAsState(if (kept) 1f else progress, MaterialTheme.motionScheme.slowEffectsSpec(), label = "keep")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxSize(),
                color = scheme.primary,
                trackColor = scheme.primary.copy(alpha = 0.18f),
                strokeWidth = 3.dp,
                gapSize = 0.dp,
            )
            Icon(
                if (kept) Icons.Rounded.Check else Icons.Rounded.MusicNote,
                contentDescription = if (kept) "Kept in your history" else "Counting toward your history",
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        val label = when {
            plays == null -> ""
            plays == 0L && !kept -> "First listen"
            else -> playsLabel(if (kept) plays.coerceAtLeast(1) else plays)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * How big the transport's middle buttons can be in [width]: the full expressive sizes in a
 * button group on a roomy phone, smaller ones on a narrow one, and a plain row on the
 * narrowest, as Material's button group throws when its buttons don't fit.
 */
internal data class TransportFit(val side: IconButtonDefaults.IconButtonWidthOption, val play: Dp, val grouped: Boolean) {
    companion object {
        private val gaps = 16.dp

        /** Room for a press to widen one button, which the group takes from its own width. */
        private val pressRoom = 24.dp

        fun of(width: Dp): TransportFit {
            val wide = TransportFit(IconButtonDefaults.IconButtonWidthOption.Wide, 88.dp, grouped = true)
            if (width >= wide.width + pressRoom) return wide
            val uniform = TransportFit(IconButtonDefaults.IconButtonWidthOption.Uniform, 72.dp, grouped = true)
            if (width >= uniform.width + pressRoom) return uniform
            return TransportFit(IconButtonDefaults.IconButtonWidthOption.Narrow, 64.dp, grouped = false)
        }
    }

    val width: Dp get() = IconButtonDefaults.mediumContainerSize(side).width * 2 + play + gaps
}

/**
 * Shuffle, previous, play, next and repeat. Previous and next sit in a Material 3 Expressive
 * button group, so a press widens the one under the finger and its neighbors give way. The
 * middle three size themselves to the room there is: 360dp-wide phones (most Galaxies at
 * their default display size) can't fit the largest.
 */
@Composable
private fun Transport(state: PlayerState, player: PlayerController, isStation: Boolean) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
        // Each toggle takes 48dp to touch, and the group keeps 8dp from each.
        val fit = TransportFit.of(maxWidth - 48.dp * 2 - 16.dp)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // A station picks as it goes: there's no order to shuffle or end to repeat, so the
            // toggles leave their room empty and the transport stays centered.
            if (isStation) Spacer(Modifier.size(48.dp)) else FilledTonalIconToggleButton(
                checked = state.shuffle,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    player.setShuffle(it)
                },
                shapes = IconButtonDefaults.toggleableShapes(),
            ) { Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle") }

            // In the group a press widens the button, so the group needs to hear about it.
            val skip: @Composable (ButtonGroupScope?, ImageVector, String, () -> Unit) -> Unit = { group, icon, label, action ->
                val source = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        action()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    interactionSource = source,
                    modifier = Modifier.size(IconButtonDefaults.mediumContainerSize(fit.side))
                        .then(group?.run { Modifier.animateWidth(source) } ?: Modifier),
                ) { Icon(icon, label, Modifier.size(IconButtonDefaults.mediumIconSize)) }
            }
            val play: @Composable () -> Unit = {
                MorphPlayButton(
                    playing = state.isPlaying || state.isLoading,
                    loading = state.isLoading,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        player.playPause()
                    },
                    size = fit.play,
                )
            }
            val middle = Modifier.weight(1f).padding(horizontal = 8.dp)

            if (fit.grouped) {
                ButtonGroup(
                    overflowIndicator = {},
                    modifier = middle,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val group = this
                    customItem(buttonGroupContent = { skip(group, Icons.Rounded.SkipPrevious, "Previous", player::previous) }, menuContent = {})
                    customItem(buttonGroupContent = play, menuContent = {})
                    customItem(buttonGroupContent = { skip(group, Icons.Rounded.SkipNext, "Next", player::next) }, menuContent = {})
                }
            } else {
                Row(
                    middle,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    skip(null, Icons.Rounded.SkipPrevious, "Previous", player::previous)
                    play()
                    skip(null, Icons.Rounded.SkipNext, "Next", player::next)
                }
            }

            if (isStation) Spacer(Modifier.size(48.dp)) else FilledTonalIconToggleButton(
                checked = state.repeat != RepeatChoice.Off,
                onCheckedChange = {
                    val next = when (state.repeat) {
                        RepeatChoice.Off -> RepeatChoice.All
                        RepeatChoice.All -> RepeatChoice.One
                        RepeatChoice.One -> RepeatChoice.Off
                    }
                    haptics.performHapticFeedback(if (next == RepeatChoice.Off) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    player.setRepeat(next)
                },
                shapes = IconButtonDefaults.toggleableShapes(),
            ) {
                Icon(
                    if (state.repeat == RepeatChoice.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = when (state.repeat) {
                        RepeatChoice.Off -> "Repeat off"
                        RepeatChoice.All -> "Repeat all"
                        RepeatChoice.One -> "Repeat one"
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomActions(state: PlayerState, player: PlayerController, onQueue: () -> Unit, onShare: (() -> Unit)?, onDevices: (() -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    var sleepMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDevices != null) BottomAction(Icons.Rounded.SpeakerGroup, "Devices", onDevices)
        Box {
            val sleepAt = state.sleepAtMs
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (sleepAt != null) {
                    FilledTonalIconButton(onClick = { sleepMenu = true }) { Icon(Icons.Rounded.Bedtime, "Sleep timer") }
                } else {
                    IconButton(onClick = { sleepMenu = true }) { Icon(Icons.Rounded.Bedtime, "Sleep timer") }
                }
                if (sleepAt != null) SleepLeft(sleepAt)
            }
            DropdownMenu(sleepMenu, { sleepMenu = false }, shape = MaterialTheme.shapes.large) {
                Text(
                    "Stop playing in",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                listOf(15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour").forEach { (minutes, label) ->
                    MenuItem(Icons.Rounded.Bedtime, label) {
                        sleepMenu = false
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        player.setSleepTimer(minutes)
                    }
                }
                if (sleepAt != null) {
                    MenuItem(Icons.Rounded.TimerOff, "Turn off timer") {
                        sleepMenu = false
                        player.setSleepTimer(null)
                    }
                }
            }
        }
        BottomAction(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", onQueue)
        if (onShare != null) BottomAction(Icons.Rounded.Share, "Share", onShare)
    }
}

/** "23 min", counting down a minute at a time. Only ticks while a timer is set. */
@Composable
private fun SleepLeft(sleepAt: Long) {
    val left by produceState(sleepAt - System.currentTimeMillis(), sleepAt) {
        while (true) {
            value = sleepAt - System.currentTimeMillis()
            if (value <= 0) break
            delay(30_000)
        }
    }
    val minutes = ((left + 59_999) / 60_000).coerceAtLeast(0)
    Text("$minutes min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
}

