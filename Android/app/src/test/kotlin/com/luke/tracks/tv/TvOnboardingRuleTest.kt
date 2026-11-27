package com.luke.tracks.tv

import com.luke.tracks.data.AppSettings
import com.luke.tracks.ui.link.normalized
import com.luke.tracks.ui.tv.settings.TvSettingsPage
import com.luke.tracks.ui.tv.onboarding.needsOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the TV's first run, Settings links and the phone's code entry follow. */
class TvOnboardingRuleTest {
    @Test fun firstRunOnlyForANewTracks() {
        assertTrue(needsOnboarding(AppSettings(), plays = 0, sampleData = false))
        assertFalse(needsOnboarding(AppSettings(onboarded = true), plays = 0, sampleData = false))
        assertFalse(needsOnboarding(AppSettings(), plays = 1, sampleData = false))
        assertFalse(needsOnboarding(AppSettings(), plays = 0, sampleData = true))
    }

    @Test fun settingsLinksLandOnTheirTvPage() {
        assertEquals(TvSettingsPage.Radio, TvSettingsPage.of("radio-genres"))
        assertEquals(TvSettingsPage.Trackside, TvSettingsPage.of("servers"))
        assertEquals(TvSettingsPage.About, TvSettingsPage.of("licenses"))
        assertEquals(TvSettingsPage.Hidden, TvSettingsPage.of("hidden"))
        assertEquals(TvSettingsPage.Trackside, TvSettingsPage.of("lastfm"))
        assertEquals(TvSettingsPage.Trackside, TvSettingsPage.of(null))
    }

    @Test fun typedCodesTakeTracksidesShape() {
        assertEquals("Q7RM-4X2K", normalized("q7rm4x2k"))
        assertEquals("Q7RM-4X2K", normalized("Q7RM-4X2K"))
        assertEquals("Q7RM-4X2K", normalized(" q7 rm-4x 2k "))
        assertNull(normalized("Q7RM-4X2"))
        assertNull(normalized("Q7RM-4X2KK"))
    }
}
