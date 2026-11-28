package com.luke.tracks.ui.tv.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.ui.onboarding.Cassette
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.link.TvLinkFlow
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The phone's rule for showing the first run: never onboarded, nothing kept yet, and not sample data. */
fun needsOnboarding(settings: AppSettings, plays: Int, sampleData: Boolean): Boolean =
    !settings.onboarded && plays == 0 && !sampleData

/**
 * The TV's first run, over the app the first time it opens, decided once per launch as on
 * the phone. Nothing asks for a permission.
 */
@Composable
fun TvOnboardingGate(content: @Composable () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle<AppSettings?>(null)
    val count by graph.history.count.collectAsStateWithLifecycle<Int?>(null)
    var showing by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val loaded = settings
    val plays = count
    if (showing == null && loaded != null && plays != null) showing = needsOnboarding(loaded, plays, graph.sampleData)
    val servers = (graph.library as? CombinedLibrary)?.servers
    val entries by remember(servers) { servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsState()
    val linkedTo = entries.firstOrNull { it.server.isTrackside }?.server?.name

    AnimatedContent(
        targetState = showing,
        transitionSpec = { fadeIn(tween(TvMotion.NowPlayingMs, easing = TvMotion.EaseOut)) togetherWith fadeOut(tween(150)) },
        label = "onboarding",
    ) { state ->
        when (state) {
            null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            true -> TvOnboarding(linkedTo = linkedTo, onDone = {
                scope.launch {
                    graph.settings.update { it.copy(onboarded = true) }
                    showing = false
                }
            })
            false -> content()
        }
    }
}

enum class TvOnboardingStep { Welcome, Link, Ready }

/** Welcome, then linking to Trackside (or skipping it), then ready. Back steps back. */
@Composable
fun TvOnboarding(linkedTo: String?, onDone: () -> Unit, start: TvOnboardingStep = TvOnboardingStep.Welcome) {
    var step by rememberSaveable { mutableStateOf(start) }
    val reduced = LocalReducedMotion.current
    BackHandler(enabled = step != TvOnboardingStep.Welcome) { step = TvOnboardingStep.entries[step.ordinal - 1] }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val slide = if (reduced) 0 else 48
                (slideInHorizontally(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) { if (forward) slide else -slide } + fadeIn(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)))
                    .togetherWith(slideOutHorizontally(tween(150, easing = TvMotion.EaseOut)) { if (forward) -slide / 2 else slide / 2 } + fadeOut(tween(120)))
            },
            label = "step",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            Box(Modifier.fillMaxSize().padding(horizontal = Side, vertical = TvLayout.SafeTop + 20.dp), contentAlignment = Alignment.CenterStart) {
                when (shown) {
                    TvOnboardingStep.Welcome -> Welcome(onNext = { step = TvOnboardingStep.Link })
                    TvOnboardingStep.Link -> LinkStep(linkedTo, onNext = { step = TvOnboardingStep.Ready })
                    TvOnboardingStep.Ready -> Ready(onDone)
                }
            }
        }
        Dots(step, Modifier.align(Alignment.BottomCenter).padding(bottom = TvLayout.SafeBottom + 8.dp))
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val first = rememberFirstFocus()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Cassette(Modifier.width(260.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Welcome to Tracks", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            Text("Play your music and keep a record of every song.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(400.dp))
            TvButton("Get Started", onClick = onNext, style = TvButtonStyle.Primary, modifier = Modifier.padding(top = 24.dp).focusRequester(first))
        }
    }
}

@Composable
private fun LinkStep(linkedTo: String?, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Link to Trackside", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        if (linkedTo != null) {
            val first = rememberFirstFocus()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(linkedTo, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvButton("Continue", onClick = onNext, style = TvButtonStyle.Primary, modifier = Modifier.padding(top = 12.dp).focusRequester(first))
        } else {
            Box(Modifier.width(620.dp)) {
                TvLinkFlow(onLinked = onNext, footer = { TvButton("Skip", onClick = onNext) })
            }
        }
    }
}

@Composable
private fun Ready(onDone: () -> Unit) {
    val first = rememberFirstFocus()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ready", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "A song joins your history once it plays for half its length or four minutes.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(560.dp),
        )
        TvButton("Done", onClick = onDone, style = TvButtonStyle.Primary, modifier = Modifier.padding(top = 24.dp).focusRequester(first))
    }
}

/** Where you are in the three steps: the current one a longer pill. */
@Composable
private fun Dots(step: TvOnboardingStep, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvOnboardingStep.entries.forEach { each ->
            val current = each == step
            val width by animateDpAsState(if (current) 28.dp else 8.dp, tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut), label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f), CircleShape),
            )
        }
    }
}

/** A requester that takes focus once the step has laid out. */
@Composable
private fun rememberFirstFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        repeat(8) {
            withFrameNanos { }
            if (requester.requestFocus()) return@LaunchedEffect
        }
    }
    return requester
}

private val Side = 96.dp
