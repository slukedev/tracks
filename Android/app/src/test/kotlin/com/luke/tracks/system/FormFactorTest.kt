package com.luke.tracks.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A TV is told apart by its UI mode or its leanback feature; low RAM by the system's word. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FormFactorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun aPhoneIsNotATv() {
        assertEquals(FormFactor(tv = false, lowRam = false), FormFactor.of(context))
    }

    @Test @Config(qualifiers = "television")
    fun televisionUiModeIsATv() {
        assertTrue(FormFactor.of(context).tv)
    }

    @Test fun leanbackFeatureIsATv() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertTrue(FormFactor.of(context).tv)
    }

    @Test fun lowRamComesFromTheSystem() {
        shadowOf(context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
        val form = FormFactor.of(context)
        assertTrue(form.lowRam)
        assertFalse(form.tv)
    }

    @Test fun composeDefaultsToAPhone() {
        assertEquals(FormFactor.Phone, FormFactor(tv = false, lowRam = false))
    }
}
