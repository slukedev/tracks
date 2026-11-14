package com.luke.tracks.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Notification access, and the music apps Tracks has seen, for Settings and onboarding. */
object CaptureAccess {
    fun component(context: Context) = ComponentName(context, CaptureListenerService::class.java)

    /** Whether the person has let Tracks see what other apps play. */
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** The system page for Tracks' own listener, or the list of them on phones without it. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun prefs(context: Context) = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    /** Remembers an app that played something, so it can be left out later. */
    fun noteSeen(context: Context, packageName: String) {
        val prefs = prefs(context)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (packageName !in seen) prefs.edit { putStringSet("seen", seen + packageName) }
    }

    fun seen(context: Context): Set<String> = prefs(context).getStringSet("seen", emptySet()).orEmpty()

    data class MusicApp(val packageName: String, val label: String)

    /**
     * Apps that can play music: the ones seen playing, and the installed ones that offer
     * their music to the system (Android Auto, Assistant), which every music app does.
     */
    suspend fun musicApps(context: Context, extra: Set<String> = emptySet()): List<MusicApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val offered = runCatching {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), PackageManager.MATCH_ALL)
                .map { it.serviceInfo.packageName }
        }.getOrDefault(emptyList())
        (seen(context) + offered + extra)
            .filter { it != context.packageName && it !in NotMusicApps.packages && it !in PodcastApps.packages }
            .mapNotNull { pkg -> label(pm, pkg)?.let { MusicApp(pkg, it) } }
            .sortedBy { it.label.lowercase() }
    }

    fun label(pm: PackageManager, packageName: String): String? = runCatching {
        val info: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrNull()
}
