package com.luke.tracks.system

import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.onboarding.OnboardingFlow
import com.luke.tracks.ui.onboarding.OnboardingStep
import org.junit.Test

/** Each step of the first run, once its entrance has settled. */
class OnboardingScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun step(step: OnboardingStep) = snap("onboarding-${step.name.lowercase()}", settleMs = 2500) {
        CompositionLocalProvider(LocalNavigator provides Navigator()) {
            OnboardingFlow(onDone = {}, initialStep = step)
        }
    }

    @Test fun welcome() = step(OnboardingStep.Welcome)

    @Test fun addMusic() = step(OnboardingStep.AddMusic)

    @Test fun lastFm() = step(OnboardingStep.LastFm)

    @Test fun ready() = step(OnboardingStep.Ready)
}
