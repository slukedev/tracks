package com.luke.tracks.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.ui.tv.navigation.tvDesignDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV Mode picks the layout over the hardware, is stored for good, and the TV layout fits any window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvModeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("tv-mode-test", Context.MODE_PRIVATE)

    @Test fun theChoiceWinsAndAutomaticFollowsTheHardware() {
        for (hardware in listOf(false, true)) {
            assertEquals(hardware, tvLayout(hardware, TvModeChoice.Automatic))
            assertTrue(tvLayout(hardware, TvModeChoice.On))
            assertFalse(tvLayout(hardware, TvModeChoice.Off))
        }
    }

    @Test fun theSwitchStoresAutomaticWhereItMatchesTheHardware() {
        assertEquals(TvModeChoice.On, tvModeChoice(hardwareTv = false, on = true))
        assertEquals(TvModeChoice.Automatic, tvModeChoice(hardwareTv = false, on = false))
        assertEquals(TvModeChoice.Off, tvModeChoice(hardwareTv = true, on = false))
        assertEquals(TvModeChoice.Automatic, tvModeChoice(hardwareTv = true, on = true))
    }

    @Test fun aPhoneTurnedOnStaysOnInTheNextProcess() {
        val mode = TvMode(prefs, hardwareTv = false)
        assertFalse(mode.layout.value)
        mode.set(on = true)
        assertTrue(mode.layout.value)
        assertEquals(TvModeChoice.On, mode.choice.value)

        val relaunched = TvMode(context.getSharedPreferences("tv-mode-test", Context.MODE_PRIVATE), hardwareTv = false)
        assertTrue(relaunched.layout.value)

        relaunched.set(on = false)
        assertFalse(relaunched.layout.value)
        assertFalse(prefs.contains("tvMode"))
        assertFalse(TvMode(prefs, hardwareTv = false).layout.value)
    }

    @Test fun aTvTurnedOffStaysOffAndComesBack() {
        val mode = TvMode(prefs, hardwareTv = true)
        assertTrue(mode.layout.value)
        mode.set(on = false)
        assertFalse(TvMode(prefs, hardwareTv = true).layout.value)
        mode.set(on = true)
        assertEquals(TvModeChoice.Automatic, TvMode(prefs, hardwareTv = true).choice.value)
        assertTrue(TvMode(prefs, hardwareTv = true).layout.value)
    }

    @Test fun anUnknownStoredValueIsAutomatic() {
        prefs.edit().putString("tvMode", "Sideways").commit()
        assertEquals(TvModeChoice.Automatic, TvMode(prefs, hardwareTv = false).choice.value)
    }

    @Test fun theTvLayoutFitsTheWindowByItsShorterSide() {
        // A TV at 1080p and at 4K is the design already.
        assertNull(tvDesignDensity(1920, 1080, 2f))
        assertNull(tvDesignDensity(3840, 2160, 4f))
        // A phone on its side (891 by 411dp at xxhdpi): the height sets the scale, so the
        // layout is 540dp tall and wider than the design, never cut off at the bottom.
        val phone = tvDesignDensity(2339, 1079, 2.625f)!!
        assertEquals(540f, 1079 / phone, 0.5f)
        assertTrue(2339 / phone > 960f)
        // A 16:9 window on a phone's density lands on the design exactly.
        val hdmi = tvDesignDensity(1920, 1080, 2.625f)!!
        assertEquals(960f, 1920 / hdmi, 0.5f)
        assertEquals(540f, 1080 / hdmi, 0.5f)
        // A large tablet scales up; within 2% of the design nothing changes.
        assertTrue(tvDesignDensity(2560, 1600, 2f)!! > 2f)
        assertNull(tvDesignDensity(1900, 1080, 2f))
        assertNull(tvDesignDensity(0, 1080, 2f))
    }
}
