package com.luke.tracks.ui.tv.detail

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.graph
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.play.MixCover
import com.luke.tracks.ui.play.covers
import com.luke.tracks.ui.play.hue
import com.luke.tracks.ui.play.icon
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvSkeletonBlock
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.tracks_engine.Mix
import androidx.compose.material3.MaterialTheme as M3Theme

/**
 * The play request in flight, by the key of what started it, so that card can show its ring
 * from the press until the player moves (or a few seconds pass with nothing to show for it).
 */
@Stable
class PendingPlay internal constructor(private val player: PlayerController, private val scope: CoroutineScope) {
    var key: Any? by mutableStateOf(null)
        private set
    private var watch: Job? = null

    fun isPending(key: Any): Boolean = this.key == key

    /** Shows the ring on [key] at once, then runs [start], which only posts to the player. */
    fun run(key: Any, start: () -> Unit) {
        val before = player.state.value.let { Triple(it.current?.mediaId, it.origin, it.isPlaying) }
        this.key = key
        start()
        watch?.cancel()
        watch = scope.launch {
            withTimeoutOrNull(PendingMs) {
                player.state.first { Triple(it.current?.mediaId, it.origin, it.isPlaying) != before && !it.isLoading }
            }
            this@PendingPlay.key = null
        }
    }

    fun play(key: Any, songs: List<PlayableSong>, start: Int, origin: QueueOrigin, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        run(key) { player.play(songs, start.coerceIn(0, songs.lastIndex), origin, shuffle) }
    }

    private companion object {
        const val PendingMs = 6_000L
    }
}

@Composable
fun rememberPendingPlay(): PendingPlay {
    val player = LocalContext.current.graph.player
    val scope = rememberCoroutineScope()
    return remember(player, scope) { PendingPlay(player, scope) }
}

/** The library's lookups, kept current: what plays here, by identity, album and artist. */
@Composable
fun rememberLibraryIndex(): State<LibraryIndex> {
    val library = LocalContext.current.graph.library
    return library.index.collectAsStateWithLifecycle()
}

/** The media id of the song on now, read alone so playback ticks don't recompose a page. */
@Composable
fun rememberCurrentMediaId(): State<String?> {
    val player = LocalContext.current.graph.player
    val flow = remember(player) { player.state.map { it.current?.mediaId }.distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(player.state.value.current?.mediaId)
}

/** A mix's cover for TV: four of its albums, each decoded small, in the mix's own colors. */
@Composable
fun TvMixArt(mix: Mix, modifier: Modifier = Modifier, sizePx: Int = TvArtwork.CardPx, shape: Shape = RoundedCornerShape(16.dp), badge: Boolean = true) {
    val covers = remember(mix.id, mix.songs.size) { mix.covers }
    MixColors(mix) {
        Box(modifier.aspectRatio(1f).clip(shape).background(M3Theme.colorScheme.primaryContainer)) {
            Collage(covers, sizePx)
            if (badge) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(10.dp).size(36.dp).clip(CircleShape).background(M3Theme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(mix.kind.icon, contentDescription = null, tint = M3Theme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Collage(covers: List<MixCover>, sizePx: Int) {
    if (covers.size < 4) {
        val first = covers.firstOrNull()
        TvArtwork(first?.url, first?.seed ?: "Tracks", Modifier.fillMaxSize(), sizePx = sizePx, shape = RectangleShape, showInitials = false)
        return
    }
    Column(Modifier.fillMaxSize()) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 2) {
                    val cover = covers[row * 2 + col]
                    TvArtwork(cover.url, cover.seed, Modifier.weight(1f), sizePx = sizePx / 2, shape = RectangleShape, showInitials = false)
                }
            }
        }
    }
}

/** The mix's own colors, from its hue, as the phone tints its card and page. */
@Composable
fun MixColors(mix: Mix, content: @Composable () -> Unit) {
    TvTracksTheme(dark = LocalDarkTheme.current, seed = mix.kind.hue, content = content)
}

private val ArtSize = 280.dp
private val ListGap = 24.dp

/** How far a row's content sits in from its edge, for lining things up with it. */
val RowInset = 16.dp

/**
 * A detail page as TV lays one out: the art fixed on the left, and on the right a column that
 * scrolls, headed by the title, its facts and actions, then the songs.
 */
@Composable
fun TvDetailLayout(
    art: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Box(Modifier.padding(start = TvLayout.ContentStart, top = TvLayout.SafeTop + 12.dp).width(ArtSize)) { art() }
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
            contentPadding = PaddingValues(start = ListGap, end = TvLayout.SafeEnd, top = TvLayout.SafeTop + 12.dp, bottom = TvLayout.SafeBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

/** The title block over a detail page's actions: name, who or what it is, and its facts. */
@Composable
fun TvDetailHeading(title: String, subtitle: String?, facts: String?, modifier: Modifier = Modifier) {
    // In line with the art of the rows below, which sit inside their own padding.
    Column(modifier.fillMaxWidth().padding(start = RowInset)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (facts != null) {
            Spacer(Modifier.height(8.dp))
            Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One short line where a list would be: why there's nothing, or what went wrong. */
@Composable
fun TvNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 12.dp))
}

/** A song's length, "3:41", for a row's end. */
fun trackLength(ms: Long?): String? {
    val seconds = (ms ?: return null) / 1000
    if (seconds <= 0) return null
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** A song row's thumbnail size, and the size its cover is decoded at. */
val RowArt = 48.dp
const val RowArtPx = 96

/**
 * One slow breath over a hand-built skeleton, as the shared skeletons breathe; still under
 * reduced motion.
 */
@Composable
fun TvPulse(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val pulse = if (reduced) null else rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(900, easing = TvMotion.EaseInOut), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        modifier
            .semantics { contentDescription = "Loading" }
            .graphicsLayer { alpha = (pulse?.value ?: 1f).coerceAtMost(1f) },
    ) { content() }
}

/** A detail page while it first loads, in the page's own shape. */
@Composable
fun TvDetailSkeleton(circle: Boolean = false) {
    TvPulse(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.padding(start = TvLayout.ContentStart, top = TvLayout.SafeTop + 12.dp).width(ArtSize)) {
                TvSkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), if (circle) CircleShape else RoundedCornerShape(16.dp))
            }
            Column(Modifier.weight(1f).padding(start = ListGap, end = TvLayout.SafeEnd, top = TvLayout.SafeTop + 12.dp)) {
                TvSkeletonBlock(Modifier.width(320.dp).height(30.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.height(12.dp))
                TvSkeletonBlock(Modifier.width(180.dp).height(16.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.height(12.dp))
                TvSkeletonBlock(Modifier.width(240.dp).height(12.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvSkeletonBlock(Modifier.width(120.dp).height(48.dp), CircleShape)
                    TvSkeletonBlock(Modifier.width(132.dp).height(48.dp), CircleShape)
                }
                Spacer(Modifier.height(28.dp))
                TvRowsSkeleton(rows = 5)
            }
        }
    }
}

/** Dense rows standing in for songs, at a row's size, inside a [TvPulse]. */
@Composable
fun TvRowsSkeleton(rows: Int, modifier: Modifier = Modifier, leading: Boolean = true) {
    Column(modifier) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (leading) TvSkeletonBlock(Modifier.size(RowArt), RoundedCornerShape(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvSkeletonBlock(Modifier.fillMaxWidth(if (it % 2 == 0) 0.5f else 0.38f).height(13.dp), RoundedCornerShape(4.dp))
                    TvSkeletonBlock(Modifier.fillMaxWidth(0.26f).height(10.dp), RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

private val LocalRowScroll = staticCompositionLocalOf<BringIntoViewSpec?> { null }

/** Where a focused card's top settles on a page of shelves: its shelf's title in view above it. */
private val ShelfPivot = 100.dp

/**
 * A page of shelves scrolls the way Google TV's home does: moving to a shelf brings it up to
 * the same height each time, title and all, rather than just far enough to show the card.
 * Anything already in the top of the page stays put. Rows inside keep their own scrolling
 * through [TvRowScroll].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvShelfScroll(content: @Composable () -> Unit) {
    val rows = LocalBringIntoViewSpec.current
    val pivot = with(LocalDensity.current) { ShelfPivot.toPx() }
    val spec = remember(pivot) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (offset >= 0 && offset + size <= containerSize * 0.7f) 0f else offset - pivot
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalRowScroll provides rows) { content() }
}

/** A row inside a [TvShelfScroll] page, scrolling sideways as rows normally do. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRowScroll(content: @Composable () -> Unit) {
    val rows = LocalRowScroll.current
    if (rows == null) content() else CompositionLocalProvider(LocalBringIntoViewSpec provides rows) { content() }
}

/** The first focus target of a page, for [com.luke.tracks.ui.tv.components.ClaimFocus]. */
@Composable
fun rememberRequester(): FocusRequester = remember { FocusRequester() }
