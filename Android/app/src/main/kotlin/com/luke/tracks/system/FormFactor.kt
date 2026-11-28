package com.luke.tracks.system

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What kind of device Tracks runs on, decided once per process. [tv] picks the TV interface
 * and limits sources to Trackside; [lowRam] shrinks caches.
 */
data class FormFactor(val tv: Boolean, val lowRam: Boolean) {
    companion object {
        val Phone = FormFactor(tv = false, lowRam = false)

        fun of(context: Context): FormFactor {
            val uiMode = context.getSystemService(UiModeManager::class.java)
            val configured = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            val television = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                configured == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
            return FormFactor(tv = television, lowRam = lowRam)
        }
    }
}

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }

/**
 * Whether this process runs the TV interface. Shared helpers check it so a TV never opens a
 * browser, share sheet, chooser or output switcher.
 */
val Context.isTv: Boolean
    get() = (applicationContext as? com.luke.tracks.TracksApplication)?.graph?.formFactor?.tv == true
