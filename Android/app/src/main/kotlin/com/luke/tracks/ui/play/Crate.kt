package com.luke.tracks.ui.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.radio.RadioWords
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.play.components.MenuItem
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.CoverCollage
import com.luke.tracks.ui.play.components.MixMenu
import com.luke.tracks.ui.play.components.MixTheme
import com.luke.tracks.ui.play.components.MorphPlayButton
import com.luke.tracks.ui.play.components.PlayActions
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.theme.GoogleSansFlexCondensed
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixKind

/** One record in the crate. */
@Immutable
sealed interface CrateItem {
    val key: String

    data object Radio : CrateItem {
        override val key = "radio"
    }

    data class OfMix(val mix: Mix) : CrateItem {
        override val key get() = mix.id
    }
}

/** The crate's records. Tracks Radio leaves it when it's hidden in Settings. */
fun crateItems(feed: PlayFeed?, showsRadio: Boolean = true): List<CrateItem> {
    val radio = listOfNotNull(CrateItem.Radio.takeIf { showsRadio })
    if (feed == null) return radio
    val mixes = feed.crate.map { CrateItem.OfMix(it) }
    // This hour's mix leads, Tracks Radio beside it, then the rest of today.
    return if (mixes.isEmpty()) radio else listOf(mixes.first()) + radio + mixes.drop(1)
}

/**
 * Today's crate: a hero carousel of this hour's mix, Tracks Radio and the other times of day,
 * with the record in front named underneath and a big play button for it.
 */
@Composable
fun Crate(
    items: List<CrateItem>,
    feed: PlayFeed?,
    player: PlayerState,
    actions: PlayActions,
    onOpenMix: (Mix) -> Unit,
    onUnplayable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberCarouselState { items.size }
    val haptics = LocalHapticFeedback.current
    // A tick as each record snaps to the front, as the crate is flipped.
    LaunchedEffect(state) {
        snapshotFlow { state.currentItem }.distinctUntilChanged().drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    Column(modifier) {
        if (items.size == 1 && items[0] == CrateItem.Radio) {
            // Tracks Radio alone, before there's any history: one card, no carousel.
            var menu by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(312.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .pressable(onClick = { actions.startRadio() }, onLongClick = { menu = true }),
            ) {
                RadioArt()
                RadioMenu(menu, { menu = false }, player, actions)
            }
        } else HorizontalCenteredHeroCarousel(
            state = state,
            modifier = Modifier.fillMaxWidth().height(312.dp),
            itemSpacing = 8.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) { index ->
            val item = items[index]
            CrateCard(item, feed, player, actions, onOpenMix, onUnplayable)
        }
        val focused = items.getOrNull(state.currentItem) ?: items.first()
        CrateCaption(focused, feed, player, actions, onUnplayable)
    }
}

@Composable
private fun CarouselItemScope.CrateCard(
    item: CrateItem,
    feed: PlayFeed?,
    player: PlayerState,
    actions: PlayActions,
    onOpenMix: (Mix) -> Unit,
    onUnplayable: () -> Unit,
) {
    val info = carouselItemDrawInfo
    // Names fade in as a record comes forward, so narrow ones at the edges are pure art.
    val reveal = ((info.size - info.minSize) / (info.maxSize - info.minSize).coerceAtLeast(1f)).coerceIn(0f, 1f)
    var menu by remember { mutableStateOf(false) }
    when (item) {
        CrateItem.Radio -> Box(
            Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .pressable(onClick = { actions.startRadio() }, onLongClick = { menu = true }),
        ) {
            RadioArt(reveal)
            RadioMenu(menu, { menu = false }, player, actions)
        }
        is CrateItem.OfMix -> {
            val playable = remember(item.mix, feed) { feed?.playable(item.mix).orEmpty() }
            Box(
                Modifier
                    .fillMaxSize()
                    .maskClip(MaterialTheme.shapes.extraLarge)
                    .pressable(onClick = { onOpenMix(item.mix) }, onLongClick = { menu = true }),
            ) {
                MixTheme(item.mix) {
                    Box(Modifier.fillMaxSize().sharedElement("cover:mix:${item.mix.id}")) {
                        CoverCollage(item.mix.covers)
                    }
                    MixCardLabel(item.mix, reveal)
                }
                MixMenu(menu, { menu = false }, item.mix, playable, actions, onOpen = { onOpenMix(item.mix) }, onUnplayable = onUnplayable)
            }
        }
    }
}

@Composable
private fun MixCardLabel(mix: Mix, reveal: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.62f),
                ),
            ),
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .graphicsLayer { alpha = reveal },
    ) {
        val kind = mix.kind
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kind.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(
                if (kind is MixKind.RightNow) "Right now" else kind.tileLine,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            mix.kind.title,
            style = MaterialTheme.typography.displaySmallEmphasized,
            fontFamily = GoogleSansFlexCondensed,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Tracks Radio's cover: Tracks' red, with scalloped Material shapes ringing out from the
 * corner like a signal, and its name set big.
 */
@Composable
fun RadioArt(reveal: Float = 1f, modifier: Modifier = Modifier) {
    TracksTheme(dark = LocalDarkTheme.current, seed = RadioHue) {
        val scheme = MaterialTheme.colorScheme
        val dark = LocalDarkTheme.current
        val top = if (dark) scheme.primaryContainer else scheme.primary
        val bottom = if (dark) scheme.tertiaryContainer else scheme.tertiary
        Box(
            modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(top, bottom))),
        ) {
            val ring = MaterialShapes.Cookie12Sided.toShape()
            val glow = Color.White
            listOf(420.dp to 0.08f, 300.dp to 0.10f, 190.dp to 0.14f).forEach { (size, alpha) ->
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = size * 0.36f, y = size * 0.36f)
                        .size(size)
                        .clip(ring)
                        .background(glow.copy(alpha = alpha)),
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .graphicsLayer { alpha = reveal },
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.22f))
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Sensors, null, Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Your station", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Tracks\nRadio",
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    fontFamily = GoogleSansFlexCondensed,
                    color = Color.White,
                    lineHeight = MaterialTheme.typography.displayMedium.fontSize * 0.98f,
                )
            }
        }
    }
}

/** What's in front, and the play button for it. */
@Composable
private fun CrateCaption(
    item: CrateItem,
    feed: PlayFeed?,
    player: PlayerState,
    actions: PlayActions,
    onUnplayable: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val settings by LocalContext.current.graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AnimatedContent(
            targetState = item,
            contentKey = { it.key },
            transitionSpec = {
                (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 3 } + fadeOut(motion.fastEffectsSpec()))
            },
            modifier = Modifier.weight(1f),
            label = "caption",
        ) { shown ->
            val (eyebrow, title, line) = when (shown) {
                CrateItem.Radio -> Triple("Your station", "Tracks Radio", RadioWords.summary(settings.radioDiscovery, settings.radioGenres))
                is CrateItem.OfMix -> {
                    val kind = shown.mix.kind
                    Triple(
                        if (kind is MixKind.RightNow) kind.part.moment(kind.isWeekend) else kind.tileLine,
                        kind.title,
                        kind.reason,
                    )
                }
            }
            Column {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val playing = player.isPlaying && when (item) {
            CrateItem.Radio -> player.origin?.kind == QueueOrigin.Kind.Radio
            is CrateItem.OfMix -> player.origin?.kind == QueueOrigin.Kind.Mix && player.origin.title == item.mix.kind.title
        }
        val shapes = when (item) {
            CrateItem.Radio -> MaterialShapes.Cookie9Sided to MaterialShapes.Square
            is CrateItem.OfMix -> MaterialShapes.Sunny to MaterialShapes.Square
        }
        MorphPlayButton(
            playing = playing,
            onClick = {
                when {
                    playing -> actions.playPause()
                    item is CrateItem.OfMix -> if (!actions.playMix(item.mix, feed?.playable(item.mix).orEmpty())) onUnplayable()
                    else -> actions.startRadio()
                }
            },
            restShape = shapes.first,
            playingShape = shapes.second,
            size = 80.dp,
            contentDescription = when {
                playing -> "Pause"
                item is CrateItem.OfMix -> "Play ${item.mix.kind.title}"
                else -> "Play Tracks Radio"
            },
        )
    }
}

/**
 * What can be done with Tracks Radio from a long press, as from its menu on iPhone: play it,
 * or pause or resume it while it's on, and tune it.
 */
@Composable
private fun RadioMenu(expanded: Boolean, onDismiss: () -> Unit, player: PlayerState, actions: PlayActions) {
    val navigator = LocalNavigator.current
    val isOn = player.origin?.kind == QueueOrigin.Kind.Radio
    DropdownMenu(expanded, onDismiss, offset = DpOffset(12.dp, (-12).dp), shape = MaterialTheme.shapes.large) {
        when {
            isOn && player.isPlaying -> MenuItem(Icons.Rounded.Pause, "Pause") {
                onDismiss()
                actions.playPause()
            }
            isOn -> MenuItem(Icons.Rounded.PlayArrow, "Resume") {
                onDismiss()
                actions.playPause()
            }
            else -> MenuItem(Icons.Rounded.PlayArrow, "Play Tracks Radio") {
                onDismiss()
                actions.startRadio()
            }
        }
        MenuItem(Icons.Rounded.Tune, "Tune Tracks Radio") {
            onDismiss()
            navigator.open(SettingsPageRoute("radio"))
        }
    }
}
