package com.luke.tracks.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.graphics.shapes.RoundedPolygon
import com.luke.tracks.graph
import com.luke.tracks.lastfm.LastFm
import com.luke.tracks.lastfm.lastFm

/** The steps of the first run. */
enum class OnboardingStep { Welcome, AddMusic, LastFm, Ready }

/** Each step's shape: the one shape on screen morphs from each to the next. */
private fun OnboardingStep.shape(done: Boolean): RoundedPolygon = when (this) {
    OnboardingStep.Welcome -> MaterialShapes.Cookie9Sided
    OnboardingStep.AddMusic -> MaterialShapes.Clover4Leaf
    OnboardingStep.LastFm -> if (done) MaterialShapes.Sunny else MaterialShapes.SoftBurst
    OnboardingStep.Ready -> MaterialShapes.Flower
}

private fun OnboardingStep.accent(which: Int): RoundedPolygon = when (this) {
    OnboardingStep.Welcome -> if (which == 0) MaterialShapes.Sunny else MaterialShapes.Circle
    OnboardingStep.AddMusic -> if (which == 0) MaterialShapes.Diamond else MaterialShapes.Cookie4Sided
    OnboardingStep.LastFm -> if (which == 0) MaterialShapes.Burst else MaterialShapes.Gem
    OnboardingStep.Ready -> if (which == 0) MaterialShapes.Heart else MaterialShapes.Sunny
}

/**
 * The first run: what Tracks does, the permissions it asks for, optional Last.fm, and a way in
 * with sample data. Every step can be skipped.
 */
@Composable
fun OnboardingFlow(onDone: (sampleData: Boolean) -> Unit, initialStep: OnboardingStep = OnboardingStep.Welcome) {
    val context = LocalContext.current
    val graph = context.graph
    val haptics = LocalHapticFeedback.current
    var stepIndex by rememberSaveable { mutableIntStateOf(initialStep.ordinal) }
    var forward by remember { mutableStateOf(true) }
    val step = OnboardingStep.entries[stepIndex]
    fun go(to: Int) {
        forward = to > stepIndex
        stepIndex = to.coerceIn(0, OnboardingStep.entries.lastIndex)
    }
    fun next() = go(stepIndex + 1)
    BackHandler(enabled = stepIndex > 0) { go(stepIndex - 1) }

    // What's been allowed.
    var notifications by remember { mutableStateOf(granted(context, Manifest.permission.POST_NOTIFICATIONS)) }
    var music by remember { mutableStateOf(granted(context, Manifest.permission.READ_MEDIA_AUDIO)) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notifications = ok
        haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    }
    val askMusic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        music = ok
        haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    }
    val editing = com.luke.tracks.ui.library.servers.rememberServerEditing()
    val sources by com.luke.tracks.ui.sources.sourceList()
    val registry = com.luke.tracks.ui.sources.rememberSources()
    val providers by registry.registered.collectAsStateWithLifecycle()
    val lastFm by graph.lastFm.connection.collectAsStateWithLifecycle()
    LifecycleResumeEffect(lastFm) {
        if (lastFm is LastFm.Connection.Waiting) graph.lastFm.checkNow()
        onPauseOrDispose {}
    }
    var lastFmBefore by remember { mutableStateOf(lastFm) }
    LaunchedEffect(lastFm) {
        if (lastFmBefore is LastFm.Connection.Waiting && lastFm is LastFm.Connection.Connected) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (lastFm is LastFm.Connection.Failed && lastFmBefore !is LastFm.Connection.Failed) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        lastFmBefore = lastFm
    }

    val done = step == OnboardingStep.LastFm && lastFm is LastFm.Connection.Connected
    var spotifyOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val shapeColor = when (step) {
        OnboardingStep.Welcome, OnboardingStep.Ready -> scheme.primaryContainer
        OnboardingStep.AddMusic -> scheme.secondaryContainer
        OnboardingStep.LastFm -> if (done) scheme.primaryContainer else scheme.tertiaryContainer
    }
    val motion = MaterialTheme.motionScheme

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(scheme.surface)) {
        val wordsRoom = maxHeight * 0.62f
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            // Where you are, and the way out.
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Progress(stepIndex, OnboardingStep.entries.size, Modifier.weight(1f))
                if (step != OnboardingStep.Ready) {
                    TextButton(onClick = { go(OnboardingStep.Ready.ordinal) }) { Text("Skip") }
                }
            }

            // The art: one shape, morphing from step to step, with the step's mark on it.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val color by androidx.compose.animation.animateColorAsState(shapeColor, motion.slowEffectsSpec(), label = "shape color")
                MorphingShape(step.shape(done), color, Modifier.size(260.dp))
                // Two small companions that change shape with each step, a beat behind.
                MorphingShape(
                    step.accent(0),
                    scheme.tertiary,
                    Modifier.align(Alignment.Center).offset(x = 118.dp, y = (-112).dp).size(46.dp),
                    turnMillis = 9_000,
                )
                MorphingShape(
                    step.accent(1),
                    scheme.secondary,
                    Modifier.align(Alignment.Center).offset(x = (-124).dp, y = 104.dp).size(30.dp),
                    turnMillis = 13_000,
                )
                AnimatedContent(
                    targetState = step to done,
                    transitionSpec = {
                        (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.defaultEffectsSpec())) togetherWith
                            (scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(motion.fastEffectsSpec()))
                    },
                    label = "mark",
                ) { (s, d) -> Mark(s, d) }
            }

            // The words and the choices, sliding in from the side the step comes from.
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(motion.defaultSpatialSpec<IntOffset>()) { dir * it / 3 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                        (slideOutHorizontally(motion.fastSpatialSpec<IntOffset>()) { -dir * it / 4 } + fadeOut(motion.fastEffectsSpec()))
                },
                label = "step",
                // At the largest text the words scroll rather than squeeze the art away.
                modifier = Modifier.fillMaxWidth().heightIn(max = wordsRoom).verticalScroll(rememberScrollState()),
            ) { s ->
                key(s) {
                    StepContent(
                        step = s,
                        notifications = notifications,
                        music = music,
                        lastFm = lastFm,
                        onAskMusic = { askMusic.launch(Manifest.permission.READ_MEDIA_AUDIO) },
                        servers = sources.count { it.kind == com.luke.tracks.sources.SourceKind.Server },
                        onAddServer = { editing.add() },
                        spotify = providers.firstOrNull { it.kind == com.luke.tracks.sources.SourceKind.Spotify }?.let { provider ->
                            SpotifyRow(sources.any { it.kind == com.luke.tracks.sources.SourceKind.Spotify }, provider.iconRes, provider.addSubtitle)
                        },
                        onAddSpotify = { spotifyOpen = true },
                        onConnectLastFm = {
                            // Last.fm is the one thing that may need to say something later: a sign-in that ran out.
                            if (!notifications) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            graph.lastFm.connect(context)
                        },
                        onCheckLastFm = { graph.lastFm.checkNow() },
                        onNext = ::next,
                        onDone = onDone,
                    )
                }
            }
        }
    }
    editing.Sheets()
    if (spotifyOpen) {
        val spotifyProvider = providers.firstOrNull { it.kind == com.luke.tracks.sources.SourceKind.Spotify }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { spotifyOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) { spotifyProvider?.Page(null, onDone = { spotifyOpen = false }) }
        }
    }
}

/** Spotify's row in Add music, in builds that have it. */
private data class SpotifyRow(val added: Boolean, val iconRes: Int?, val subtitle: String?)

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Pills for the steps; the current one stretches. */
@Composable
private fun Progress(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 28.dp else 8.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "pill")
            val color by androidx.compose.animation.animateColorAsState(
                if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pill color",
            )
            Box(Modifier.width(width).height(8.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun Mark(step: OnboardingStep, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    when {
        step == OnboardingStep.Welcome -> Cassette(Modifier.width(170.dp))
        step == OnboardingStep.Ready -> Cassette(Modifier.width(150.dp))
        done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(88.dp))
        else -> Icon(
            when (step) {
                OnboardingStep.AddMusic -> Icons.Rounded.LibraryMusic
                else -> Icons.Rounded.GraphicEq
            },
            contentDescription = null,
            tint = when (step) {
                OnboardingStep.AddMusic -> scheme.onSecondaryContainer
                else -> scheme.onTertiaryContainer
            },
            modifier = Modifier.size(84.dp),
        )
    }
}

@Composable
private fun StepContent(
    step: OnboardingStep,
    notifications: Boolean,
    music: Boolean,
    lastFm: LastFm.Connection,
    onAskMusic: () -> Unit,
    servers: Int,
    onAddServer: () -> Unit,
    spotify: SpotifyRow?,
    onAddSpotify: () -> Unit,
    onConnectLastFm: () -> Unit,
    onCheckLastFm: () -> Unit,
    onNext: () -> Unit,
    onDone: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (step) {
            OnboardingStep.Welcome -> {
                Words(0, "Welcome to Tracks", "Play your music and keep a record of every song.")
                Actions(2, primary = "Get started", onPrimary = onNext)
            }
            OnboardingStep.AddMusic -> {
                Words(0, "Add music", null)
                Permission(1, Icons.Rounded.PhoneAndroid, "On This Device", music, onAskMusic)
                Spacer(Modifier.height(8.dp))
                Permission(2, Icons.Rounded.Dns, "Server", servers > 0, onAddServer, subtitle = if (servers > 1) "$servers added" else null)
                if (spotify != null) {
                    Spacer(Modifier.height(8.dp))
                    Permission(3, Icons.Rounded.GraphicEq, "Spotify", spotify.added, onAddSpotify, subtitle = spotify.subtitle, iconRes = spotify.iconRes)
                }
                Actions(4, primary = "Continue", onPrimary = onNext)
            }
            OnboardingStep.LastFm -> when (lastFm) {
                LastFm.Connection.NotConfigured -> {
                    Words(0, "Last.fm isn't available", "This build of Tracks has no Last.fm key, so it can't scrobble.")
                    Actions(2, primary = "Continue", onPrimary = onNext)
                }
                is LastFm.Connection.Connected -> {
                    Words(0, "Scrobbling as ${lastFm.username}", null)
                    Actions(2, primary = "Continue", onPrimary = onNext)
                }
                LastFm.Connection.Waiting -> {
                    Words(0, "Waiting for Last.fm", "Allow Tracks on the Last.fm page, then come back here.")
                    Row(Modifier.staggered(1).padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = onCheckLastFm) { Text("I've allowed it") }
                    }
                    Actions(2, primary = null, onPrimary = {}, secondary = "Skip for now", onSecondary = onNext)
                }
                else -> {
                    Words(
                        0,
                        "Scrobble to Last.fm",
                        if (lastFm is LastFm.Connection.Failed) lastFm.message
                        else "You sign in on Last.fm's site, so Tracks never sees your password.",
                    )
                    Actions(2, primary = "Connect Last.fm", onPrimary = onConnectLastFm, secondary = "Skip", onSecondary = onNext)
                }
            }
            OnboardingStep.Ready -> {
                Words(0, "Ready", "A song joins your history once it plays for half its length or four minutes.")
                Actions(2, primary = "Done", onPrimary = { onDone(false) }, secondary = "Try it with sample data", onSecondary = { onDone(true) })
            }
        }
    }
}

@Composable
private fun Words(index: Int, title: String, body: String?) {
    Column(Modifier.staggered(index), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
    }
}

@Composable
private fun Permission(
    index: Int,
    icon: ImageVector,
    title: String,
    allowed: Boolean,
    onAllow: () -> Unit,
    subtitle: String? = null,
    iconRes: Int? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.staggered(index).padding(top = if (index == 1) 20.dp else 0.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                com.luke.tracks.ui.sources.SourceGlyph(com.luke.tracks.sources.SourceKind.Spotify, iconRes = iconRes)
            } else {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(allowed, label = "allowed") { yes ->
                if (yes) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = "Added", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    FilledTonalButton(onClick = onAllow, modifier = Modifier.semantics { contentDescription = "Add $title" }) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun Actions(index: Int, primary: String?, onPrimary: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {}) {
    Column(Modifier.staggered(index).padding(top = 28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (primary != null) {
            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
            ) { Text(primary, style = MaterialTheme.typography.titleMedium) }
        }
        if (secondary != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.padding(top = 4.dp).height(48.dp)) { Text(secondary) }
        } else {
            Spacer(Modifier.height(52.dp))
        }
    }
}
