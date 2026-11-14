package com.luke.tracks.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.SampleData
import com.luke.tracks.graph
import kotlinx.coroutines.launch

/**
 * Shows the first run before the app when Tracks is new: never onboarded and nothing kept
 * yet. Decided once per launch, so a song kept during onboarding doesn't cut it short.
 */
@Composable
fun OnboardingGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle<AppSettings?>(null)
    val count by graph.history.count.collectAsStateWithLifecycle<Int?>(null)
    var showing by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val loaded = settings
    if (showing == null && loaded != null && count != null) {
        showing = !loaded.onboarded && count == 0 && !graph.sampleData
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = showing,
        transitionSpec = { (fadeIn(motion.slowEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f)) togetherWith fadeOut(motion.fastEffectsSpec()) },
        label = "onboarding",
    ) { state ->
        when (state) {
            // The splash screen is still up for the moment this takes.
            null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            true -> OnboardingFlow(onDone = { sample ->
                scope.launch {
                    graph.settings.update { it.copy(onboarded = true, showSampleData = sample) }
                    if (sample) SampleData.switch(context, true) else showing = false
                }
            })
            false -> content()
        }
    }
}
