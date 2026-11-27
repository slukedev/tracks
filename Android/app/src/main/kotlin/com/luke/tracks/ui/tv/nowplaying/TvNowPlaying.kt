package com.luke.tracks.ui.tv.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.playback.MediaControllerPlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RadioState
import com.luke.tracks.playback.RepeatChoice
import com.luke.tracks.playback.artistLabel
import com.luke.tracks.playback.titleLabel
import com.luke.tracks.ui.nowplaying.PickReasonLabel
import com.luke.tracks.ui.nowplaying.rememberCoverSeed
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvIconButton
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme as M3Theme

/** The full-screen player, from the app's player, radio and settings. */
@Composable
fun TvNowPlayingScreen(onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val player = graph.player
    // Everything but the keep ring's progress, which moves every second and isn't shown here.
    val state by remember(player) { player.state.map { it.copy(keepProgress = 0f, positionMs = 0) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(player.state.value.copy(keepProgress = 0f, positionMs = 0))
    val radio by graph.radio.state.collectAsStateWithLifecycle()
    val identity = state.current?.identity
    val marks by remember(identity) {
        graph.settings.settings.map { SongMarks(identity in it.likedSongs, identity in it.hiddenSongs) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(SongMarks(false, false))
    val scope = rememberCoroutineScope()
    TvNowPlaying(
        state = state,
        position = player.position,
        player = player,
        radio = radio.takeIf { state.origin?.kind == QueueOrigin.Kind.Radio },
        marks = marks,
        onLike = { (player as? MediaControllerPlayer)?.toggleLike() },
        onHearLess = { id, less ->
            scope.launch { graph.settings.update { it.copy(hiddenSongs = if (less) it.hiddenSongs + id else it.hiddenSongs - id) } }
        },
        onClose = onClose,
    )
}

/** Whether the song on now is liked, and asked to be heard less of. */
data class SongMarks(val liked: Boolean, val hidden: Boolean)

/**
 * The player drawn from plain values, so tests drive it with a fake player. Colours come from
 * the cover. Back closes (the shell's), the centre key on Play and Pause plays or pauses, and
 * media keys go on to the media session.
 */
@Composable
fun TvNowPlaying(
    state: PlayerState,
    position: StateFlow<Long>,
    player: PlayerController,
    radio: RadioState?,
    marks: SongMarks,
    onLike: () -> Unit,
    onHearLess: (identity: String, less: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val song = state.current
    // The queue running out puts the player away; opened with nothing on, it says so.
    var had by remember { mutableStateOf(false) }
    LaunchedEffect(song == null) {
        if (song != null) had = true else if (had) onClose()
    }
    if (song == null) {
        val memory = rememberFocusMemory()
        memory.ClaimFocus(fallback = null)
        TvEmptyState(title = "Nothing Playing")
        return
    }
    val seed = rememberCoverSeed(song.artworkUri, song.albumTitle ?: song.title)
    TvTracksTheme(dark = LocalDarkTheme.current, seed = seed) {
        Player(state, song, position, player, radio, marks, onLike, onHearLess)
    }
}

private enum class Panel { Queue, Sleep }

@Composable
private fun Player(
    state: PlayerState,
    song: PlayableSong,
    position: StateFlow<Long>,
    player: PlayerController,
    radio: RadioState?,
    marks: SongMarks,
    onLike: () -> Unit,
    onHearLess: (String, Boolean) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val memory = rememberFocusMemory()
    memory.ClaimFocus(fallback = memory.requester(PlayKey))
    var panel by remember { mutableStateOf<Panel?>(null) }
    val touches = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    val idle by rememberIdleFrame(touches, enabled = panel == null)
    var swallowed by remember { mutableStateOf<Key?>(null) }
    val rest by animateFloatAsState(
        if (idle.asleep) 1f else 0f,
        tween(if (idle.asleep) RestMs else WakeMs, easing = TvMotion.EaseInOut),
        label = "rest",
    )
    val dim by animateFloatAsState(if (idle.stage == IdleStage.Dim) DimAlpha else 0f, tween(DimMs), label = "dim")
    val driftX by animateFloatAsState(idle.offset.x.value, tween(if (reduced) 0 else DriftMs, easing = TvMotion.EaseInOut), label = "driftX")
    val driftY by animateFloatAsState(idle.offset.y.value, tween(if (reduced) 0 else DriftMs, easing = TvMotion.EaseInOut), label = "driftY")
    val scheme = M3Theme.colorScheme

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to lerp(scheme.surface, scheme.primaryContainer, 0.55f),
                    0.55f to lerp(scheme.surface, scheme.secondaryContainer, 0.18f),
                    1f to scheme.surface,
                ),
            )
            .onPreviewKeyEvent { event ->
                if (event.key in PassThrough) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        val asleep = idle.asleep
                        touches.tryEmit(Unit)
                        if (!asleep) return@onPreviewKeyEvent false
                        // The first press only wakes it, except the centre key, which plays or pauses as well.
                        swallowed = event.key
                        if (event.key in Centre) player.playPause()
                        true
                    }
                    KeyEventType.KeyUp -> if (swallowed == event.key) {
                        swallowed = null
                        true
                    } else false
                    else -> false
                }
            },
    ) {
        val width = maxWidth
        val artStart = TvLayout.SafeEnd
        // How far the art travels to the middle when the player rests.
        val toMiddle = (width - ArtSize) / 2 - artStart
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = artStart)
                .size(ArtSize)
                .graphicsLayer {
                    translationX = (toMiddle.toPx() * rest) + driftX.dp.toPx()
                    translationY = (-RestLift.toPx() * rest) + driftY.dp.toPx()
                },
        ) {
            Cover(song, state.isPlaying || state.isLoading)
        }
        RestCaption(
            song = song,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = TvLayout.SafeBottom + 8.dp)
                .graphicsLayer {
                    alpha = rest
                    translationX = driftX.dp.toPx()
                    translationY = driftY.dp.toPx()
                },
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = artStart + ArtSize + 48.dp, end = TvLayout.SafeEnd)
                .fillMaxWidth()
                .graphicsLayer { alpha = 1f - rest },
        ) {
            Words(state, song, radio)
            Spacer(Modifier.height(28.dp))
            TvSeekBar(
                position = position,
                lengthMs = state.lengthMs,
                playing = state.isPlaying,
                ticking = !idle.asleep,
                onSeek = player::seekTo,
                modifier = Modifier.rememberFocus(memory, SeekKey),
            )
            Spacer(Modifier.height(20.dp))
            Transport(state, player, memory)
            Spacer(Modifier.height(16.dp))
            Secondary(
                state = state,
                song = song,
                radio = radio,
                marks = marks,
                player = player,
                memory = memory,
                onLike = onLike,
                onHearLess = onHearLess,
                onQueue = { panel = Panel.Queue },
                onSleep = { panel = Panel.Sleep },
            )
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Color.Black))
        TvQueuePanel(visible = panel == Panel.Queue, state = state, radio = radio, player = player, onDismiss = { panel = null })
        TvSleepPanel(visible = panel == Panel.Sleep, sleepAtMs = state.sleepAtMs, onPick = { minutes ->
            player.setSleepTimer(minutes)
            panel = null
        }, onDismiss = { panel = null })
    }
}

@Composable
private fun Cover(song: PlayableSong, playing: Boolean) {
    val reduced = LocalReducedMotion.current
    val settle by animateFloatAsState(if (playing) 1f else PausedScale, tween(if (reduced) 0 else 300, easing = TvMotion.EaseOut), label = "settle")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = settle
                scaleY = settle
            }
            .shadow(if (playing) 32.dp else 12.dp, RoundedCornerShape(24.dp), clip = false),
    ) {
        AnimatedContent(
            targetState = song,
            contentKey = { it.mediaId },
            transitionSpec = { fadeIn(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) togetherWith fadeOut(tween(TvMotion.ScreenMs)) },
            label = "cover",
        ) { shown ->
            TvArtwork(shown.artworkUri, shown.albumTitle ?: shown.title, Modifier.fillMaxSize(), sizePx = TvArtwork.HeroPx, shape = RoundedCornerShape(24.dp))
        }
    }
}

/** The title and artist under the art while the player rests. */
@Composable
private fun RestCaption(song: PlayableSong, modifier: Modifier) {
    Column(modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(song.titleLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(song.artistLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Words(state: PlayerState, song: PlayableSong, radio: RadioState?) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(song.titleLabel, style = MaterialTheme.typography.headlineLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artistLabel, style = MaterialTheme.typography.titleLarge, color = colors.onSurface.copy(alpha = 0.86f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        song.albumTitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val reason = radio?.reason(song)
        when {
            reason != null -> PickReasonLabel(reason, colors.onSurfaceVariant, Modifier.padding(top = 6.dp))
            state.origin != null -> Origin(state.origin)
        }
    }
}

@Composable
private fun Origin(origin: QueueOrigin) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { contentDescription = "Playing from ${origin.title}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(origin.kind.icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(origin.title, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val QueueOrigin.Kind.icon: ImageVector
    get() = when (this) {
        QueueOrigin.Kind.Mix -> Icons.Rounded.AutoAwesome
        QueueOrigin.Kind.Radio -> Icons.Rounded.Radio
        QueueOrigin.Kind.Album -> Icons.Rounded.Album
        QueueOrigin.Kind.Artist -> Icons.Rounded.Person
        QueueOrigin.Kind.Playlist -> Icons.AutoMirrored.Rounded.QueueMusic
        QueueOrigin.Kind.Library -> Icons.Rounded.LibraryMusic
        QueueOrigin.Kind.Search -> Icons.Rounded.Search
        QueueOrigin.Kind.Other -> Icons.Rounded.MusicNote
    }

@Composable
private fun Transport(state: PlayerState, player: PlayerController, memory: com.luke.tracks.ui.tv.components.FocusMemory) {
    val playing = state.isPlaying || state.isLoading
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TvIconButton(Icons.Rounded.SkipPrevious, "Previous", onClick = player::previous, modifier = Modifier.rememberFocus(memory, "previous"))
        TvIconButton(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pause" else "Play",
            onClick = player::playPause,
            big = true,
            modifier = Modifier.rememberFocus(memory, PlayKey),
        )
        TvIconButton(Icons.Rounded.SkipNext, "Next", onClick = player::next, modifier = Modifier.rememberFocus(memory, "next"))
    }
}

/** The quieter actions in one row, each icon named underneath while it has focus. */
@Composable
private fun Secondary(
    state: PlayerState,
    song: PlayableSong,
    radio: RadioState?,
    marks: SongMarks,
    player: PlayerController,
    memory: com.luke.tracks.ui.tv.components.FocusMemory,
    onLike: () -> Unit,
    onHearLess: (String, Boolean) -> Unit,
    onQueue: () -> Unit,
    onSleep: () -> Unit,
) {
    var named by remember { mutableStateOf<String?>(null) }
    // Shown at once on press, until the settings agree.
    var liked by remember(song.identity, marks.liked) { mutableStateOf(marks.liked) }
    val station = radio != null
    val sleepLeft = sleepMinutes(state.sleepAtMs)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun Action(key: String, icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
                TvIconButton(
                    icon,
                    label,
                    onClick = onClick,
                    selected = selected,
                    enabled = enabled,
                    modifier = Modifier
                        .rememberFocus(memory, key)
                        .onFocusChanged { if (it.isFocused) named = label else if (named == label) named = null },
                )
            }
            Action("like", if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", selected = liked) {
                liked = !liked
                onLike()
            }
            if (radio != null && radio.isPick(song)) {
                val asked = song.identity in radio.askedMoreLike
                Action("more", if (asked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, "More Like This", selected = asked, enabled = !asked, onClick = player::playMoreLikeThis)
            }
            Action("less", if (marks.hidden) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt, if (marks.hidden) "Hear Again" else "Hear Less", selected = marks.hidden) {
                onHearLess(song.identity, !marks.hidden)
            }
            Action("queue", Icons.AutoMirrored.Rounded.QueueMusic, "Up Next", onClick = onQueue)
            // A station picks as it goes: nothing to shuffle, no end to repeat.
            if (!station) {
                Action("shuffle", Icons.Rounded.Shuffle, "Shuffle", selected = state.shuffle) { player.setShuffle(!state.shuffle) }
                Action(
                    "repeat",
                    if (state.repeat == RepeatChoice.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (state.repeat) {
                        RepeatChoice.Off -> "Repeat"
                        RepeatChoice.All -> "Repeat All"
                        RepeatChoice.One -> "Repeat One"
                    },
                    selected = state.repeat != RepeatChoice.Off,
                ) {
                    player.setRepeat(
                        when (state.repeat) {
                            RepeatChoice.Off -> RepeatChoice.All
                            RepeatChoice.All -> RepeatChoice.One
                            RepeatChoice.One -> RepeatChoice.Off
                        },
                    )
                }
            }
            Action("sleep", Icons.Rounded.Bedtime, if (sleepLeft != null) "Sleep Timer, $sleepLeft min" else "Sleep Timer", selected = sleepLeft != null, onClick = onSleep)
        }
        // The one line naming what has focus: icons alone don't say it on a TV.
        Box(Modifier.height(28.dp).padding(top = 8.dp)) {
            named?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** Minutes left on the sleep timer, a minute at a time; null when it's off. */
@Composable
private fun sleepMinutes(sleepAtMs: Long?): Long? {
    val left by produceState<Long?>(null, sleepAtMs) {
        val at = sleepAtMs ?: run { value = null; return@produceState }
        while (true) {
            val ms = at - System.currentTimeMillis()
            value = ((ms + 59_999) / 60_000).coerceAtLeast(0)
            if (ms <= 0) break
            delay((ms % 60_000).coerceAtLeast(1_000))
        }
    }
    return left
}

private val ArtSize: Dp = 400.dp
private val RestLift = 28.dp
private const val PlayKey = "play"
private const val SeekKey = "seek"
private const val PausedScale = 0.94f
private const val RestMs = 600
private const val WakeMs = 250
private const val DriftMs = 4_000
private const val DimMs = 1_200
private const val DimAlpha = 0.3f

private val Centre = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Back is the shell's; media keys belong to the media session. */
private val PassThrough = setOf(
    Key.Back, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaNext, Key.MediaPrevious,
    Key.MediaFastForward, Key.MediaRewind, Key.MediaStop, Key.VolumeUp, Key.VolumeDown, Key.VolumeMute,
)
