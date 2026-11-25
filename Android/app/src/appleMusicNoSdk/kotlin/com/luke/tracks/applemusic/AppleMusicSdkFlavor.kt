package com.luke.tracks.applemusic

import android.content.Context

/** Built without Apple's MusicKit AARs: no sign-in, no player, and Apple Music stays out of Sources. */
object AppleMusicSdkFlavor {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): AppleMusicSdk? = null
}
