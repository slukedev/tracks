package com.luke.tracks.ui.tv.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.play.PlayFeed
import com.luke.tracks.ui.play.reason
import com.luke.tracks.ui.play.rememberPlayFeed
import com.luke.tracks.ui.play.title
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.MixColors
import com.luke.tracks.ui.tv.detail.RowArt
import com.luke.tracks.ui.tv.detail.RowArtPx
import com.luke.tracks.ui.tv.detail.TvDetailHeading
import com.luke.tracks.ui.tv.detail.TvDetailLayout
import com.luke.tracks.ui.tv.detail.TvDetailSkeleton
import com.luke.tracks.ui.tv.detail.TvMixArt
import com.luke.tracks.ui.tv.detail.rememberCurrentMediaId
import com.luke.tracks.ui.tv.detail.rememberPendingPlay
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import uniffi.tracks_engine.Mix

/** A mix: its art, why it's made, Play and Shuffle, and every song, the ones that play here bright. */
@Composable
fun TvMixScreen(mixId: String) {
    val feed by rememberPlayFeed()
    val memory = rememberFocusMemory()
    val current = feed
    val mix = current?.mix(mixId)
    when {
        current == null -> {
            memory.ClaimFocus(null)
            TvDetailSkeleton()
        }
        mix == null -> {
            val back = remember { androidx.compose.ui.focus.FocusRequester() }
            memory.ClaimFocus(back)
            val navigator = LocalTvNavigator.current
            TvEmptyState("Mix Not Found", actionLabel = "Back", onAction = { navigator.back(railFocused = false) }, focusRequester = back)
        }
        else -> MixColors(mix) { MixPage(mix, current) }
    }
}

@Composable
private fun MixPage(mix: Mix, feed: PlayFeed) {
    val memory = rememberFocusMemory()
    val navigator = LocalTvNavigator.current
    val pending = rememberPendingPlay()
    val playing by rememberCurrentMediaId()
    val playable = remember(mix, feed) { feed.playable(mix) }
    val origin = remember(mix) { QueueOrigin(mix.kind.title, QueueOrigin.Kind.Mix) }
    val first = if (playable.isNotEmpty()) "play" else "song:0"
    memory.ClaimFocus(memory.requester(first))
    val length = playable.sumOf { it.durationMs ?: 0 }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvDetailLayout(art = { TvMixArt(mix, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, badge = false) }) {
            item(key = "heading") {
                TvDetailHeading(
                    mix.kind.title,
                    mix.kind.reason,
                    listOfNotNull(Format.songs(mix.songs.size.toLong()), length.takeIf { it > 0 }?.let(Format::compactDuration)).joinToString(" · "),
                )
            }
            if (playable.isNotEmpty()) item(key = "actions") {
                Row(Modifier.padding(top = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        "Play", { pending.play("play", playable, 0, origin) },
                        Modifier.rememberFocus(memory, "play"), icon = Icons.Rounded.PlayArrow, style = TvButtonStyle.Primary, pending = pending.isPending("play"),
                    )
                    TvButton(
                        "Shuffle", { pending.play("shuffle", playable, 0, origin, shuffle = true) },
                        Modifier.rememberFocus(memory, "shuffle"), icon = Icons.Rounded.Shuffle, pending = pending.isPending("shuffle"),
                    )
                }
            }
            itemsIndexed(mix.songs, key = { _, song -> "song:${song.identity}" }) { index, song ->
                val here = feed.playable[song.identity]
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                TvListRow(
                    onClick = {
                        if (here != null) pending.play("song:$index", playable, playable.indexOf(here), origin)
                        else navigator.open(SongRoute(song.identity, song.title, song.artistName))
                    },
                    headline = song.title,
                    supporting = song.artistName,
                    dense = true,
                    selected = here != null && here.mediaId == playing,
                    interactionSource = source,
                    // Songs that don't play here stay on the list, quieter, and open their page.
                    modifier = Modifier.rememberFocus(memory, "song:$index").alpha(if (here == null && !focused) 0.5f else 1f),
                    leading = {
                        TvArtwork(here?.artworkUri ?: song.artworkUrl, (song.albumTitle ?: song.title) + song.artistName, Modifier.size(RowArt), sizePx = RowArtPx, showInitials = false)
                    },
                    trailing = {
                        Text(Format.plays(song.plays.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}
