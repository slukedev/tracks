package com.luke.tracks.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.luke.tracks.MainActivity
import com.luke.tracks.R

/**
 * The notifications Tracks posts itself (playback's own is Media3's). Only one today: when
 * Last.fm stops taking scrobbles because the account needs connecting again.
 */
object Notifications {
    const val LASTFM = "lastfm"
    private const val RECONNECT_ID = 2001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(LASTFM, "Last.fm", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When Last.fm needs you to connect again"
                setShowBadge(false)
            },
        )
    }

    fun lastFmNeedsReconnect(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("tracks://settings/lastfm"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, LASTFM)
            .setSmallIcon(R.drawable.ic_widget_headphones)
            .setContentTitle("Reconnect Last.fm")
            .setContentText("Sign in again to send waiting scrobbles.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(RECONNECT_ID, notification) }
    }
}
