package com.luke.tracks.ui.nowplaying

import com.luke.tracks.system.isTv
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The color a cover is mostly, to seed Now Playing's scheme from, as Pixel's player does. A
 * song without a cover gets a steady color from its name, the same as its stand-in art.
 */
@Composable
fun rememberCoverSeed(artwork: Any?, name: String): Color {
    val context = LocalContext.current
    val fallback = remember(name) { seedFromName(name) }
    val color by produceState(fallback, artwork, name) {
        value = if (artwork == null) fallback else coverColor(context, artwork) ?: fallback
    }
    return color
}

private suspend fun coverColor(context: Context, artwork: Any): Color? = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context).data(artwork).size(96).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
        val palette = Palette.from(result.image.toBitmap()).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
        swatch?.rgb?.let { Color(it) }
    }.getOrNull()
}

/** A lively color from a name: any hue, never gray. */
fun seedFromName(name: String): Color {
    val hue = Math.floorMod(name.hashCode(), 360).toFloat()
    return Color.hsv(hue, 0.6f, 0.78f)
}

/**
 * Opens the system's output switcher for Tracks (Android 14's media output panel), so the
 * person can move playback to headphones, a speaker or a Cast device. Falls back to the older
 * settings panel, then to Bluetooth settings.
 */
fun openOutputSwitcher(context: Context) {
    if (context.isTv) return
    if (Build.VERSION.SDK_INT >= 34) {
        val shown = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(panel) }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
