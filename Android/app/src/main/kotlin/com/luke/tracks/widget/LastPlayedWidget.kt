package com.luke.tracks.widget

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.luke.tracks.R
import com.luke.tracks.graph

/** The last song Tracks kept: its cover, title, artist and when. Tap to open Tracks. */
class LastPlayedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 50.dp), DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.lastPlayed(context, context.graph)
        provideContent { GlanceTheme { LastPlayedContent(data, System.currentTimeMillis()) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = LastPlayedData("Saltwater", "Mara Solis", System.currentTimeMillis() - 12 * 60_000, null, null)
        provideContent { GlanceTheme { LastPlayedContent(sample, System.currentTimeMillis()) } }
    }
}

class LastPlayedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LastPlayedWidget()
}

@Composable
fun LastPlayedContent(data: LastPlayedData?, now: Long) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(actionStartActivity(WidgetData.open(context, "tracks://history")))
            .padding(12.dp),
    ) {
        if (data == null) {
            Empty()
        } else when {
            size.height < 100.dp -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Cover(data, 40.dp)
                Spacer(GlanceModifier.width(10.dp))
                Words(data, now, compact = true)
            }
            size.width >= 240.dp -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Cover(data, (size.height - 24.dp).coerceAtMost(140.dp))
                Spacer(GlanceModifier.width(14.dp))
                Words(data, now, compact = false)
            }
            else -> Column(GlanceModifier.fillMaxSize()) {
                Cover(data, (size.height - 70.dp).coerceIn(36.dp, 120.dp))
                Spacer(GlanceModifier.defaultWeight())
                Words(data, now, compact = true)
            }
        }
    }
}

@Composable
private fun Cover(data: LastPlayedData, side: androidx.compose.ui.unit.Dp) {
    val cover = data.cover
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.size(side).cornerRadius(14.dp),
        )
    } else {
        Box(
            GlanceModifier.size(side).cornerRadius(14.dp).background(GlanceTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_headphones),
                contentDescription = null,
                modifier = GlanceModifier.size(side / 2.6f),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
    }
}

@Composable
private fun Words(data: LastPlayedData, now: Long, compact: Boolean) {
    val whenText = DateUtils.getRelativeTimeSpanString(data.playedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    Column(GlanceModifier.fillMaxWidth()) {
        if (!compact) {
            Text(
                "Last played",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        Text(
            data.title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 14.sp else 18.sp, fontWeight = FontWeight.Bold),
            maxLines = if (compact) 1 else 2,
        )
        Text(data.artist, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
        Text(
            listOfNotNull(data.station, whenText).joinToString(" · "),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun Empty() {
    Column(GlanceModifier.fillMaxSize().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_headphones),
            contentDescription = null,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            "No songs yet",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            maxLines = 2,
        )
    }
}
