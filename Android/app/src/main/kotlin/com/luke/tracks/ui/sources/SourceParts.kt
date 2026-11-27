package com.luke.tracks.ui.sources

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.sources.SourcesRegistry
import com.luke.tracks.sources.sources
import java.text.NumberFormat

/** The registry for these screens; screenshots provide their own. */
val LocalSources = compositionLocalOf<SourcesRegistry?> { null }

@Composable
fun rememberSources(): SourcesRegistry {
    LocalSources.current?.let { return it }
    val graph = LocalContext.current.graph
    return remember(graph, graph.library) { graph.sources }
}

@Composable
fun sourceList(): State<List<Source>> {
    val registry = rememberSources()
    return registry.sources.collectAsState()
}

@Composable
fun sourceSelection(): State<SourceSelection> = rememberSources().selection.collectAsState()

fun SourceKind.icon(): ImageVector = when (this) {
    SourceKind.Device -> Icons.Rounded.PhoneAndroid
    SourceKind.Server -> Icons.Rounded.Dns
    SourceKind.AppleMusic -> Icons.Rounded.MusicNote
}

/** One word for where a source stands, as its row shows it. */
fun SourceStatus.word(): String = when (this) {
    SourceStatus.SignIn -> "Sign in"
    SourceStatus.CheckCertificate -> "Check certificate"
    is SourceStatus.Offline -> "Offline"
    is SourceStatus.Syncing -> fraction?.let { "Syncing ${(it * 100).toInt()}%" } ?: "Syncing"
    SourceStatus.Away -> "Away"
    SourceStatus.Connected -> "Connected"
    is SourceStatus.Songs -> songs(count)
    SourceStatus.Off -> "Off"
}

fun songs(count: Int): String = if (count == 1) "1 song" else "${NumberFormat.getIntegerInstance().format(count)} songs"

/**
 * The Settings row's word for every source together: what's worst when something needs a
 * hand, else the one source's name, else how many there are.
 */
fun summary(sources: List<Source>): String {
    if (sources.isEmpty()) return "None"
    val worst = sources.firstOrNull { it.status is SourceStatus.SignIn } ?: sources.firstOrNull { it.status is SourceStatus.CheckCertificate }
    if (worst != null) return worst.status.word()
    val offline = sources.count { it.status is SourceStatus.Offline }
    if (offline > 0) return "$offline offline"
    return if (sources.size == 1) sources.single().name else "${sources.size} sources"
}

/** A source's mark in its tonal shape: the phone, a server, or a brand's own icon on black. */
@Composable
fun SourceGlyph(kind: SourceKind, modifier: Modifier = Modifier, iconRes: Int? = null, size: Dp = 40.dp) {
    val brand = iconRes != null
    Surface(
        shape = if (brand) MaterialShapes.Circle.toShape() else MaterialShapes.Cookie9Sided.toShape(),
        color = if (brand) Color.Black else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (iconRes != null) {
                Icon(painterResource(iconRes), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size * 0.6f))
            } else {
                Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.55f))
            }
        }
    }
}

/** The glyph alone, small, for inline use (Now Playing's badge, a queue row). */
@Composable
fun SourceMark(source: Source, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val registry = rememberSources()
    val iconRes = registry.iconRes(source.kind)
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(source.kind to iconRes, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "mark", modifier = modifier) { (kind, res) ->
        if (res != null) {
            Icon(painterResource(res), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size))
        } else {
            Icon(kind.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    }
}

/** The settings page that shows one source. */
fun sourcePage(id: SourceId): String = "source/" + id.raw
