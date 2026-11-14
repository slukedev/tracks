package com.luke.tracks.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Redraws the home screen widgets, for a kept play or a new day. */
object WidgetUpdates {
    suspend fun updateAll(context: Context) {
        runCatching { ListeningWidget().updateAll(context) }
        runCatching { LastPlayedWidget().updateAll(context) }
    }
}
