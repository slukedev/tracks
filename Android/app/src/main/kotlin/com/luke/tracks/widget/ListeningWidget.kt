package com.luke.tracks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Listening: the last seven days as bars, today's time and the streak. Small sizes keep just
 * today and the streak; wider ones add the week.
 */
class ListeningWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, SQUARE, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.listening(context.graph)
        provideContent { GlanceTheme { ListeningContent(data) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { ListeningContent(ListeningData.sample) } }
    }

    companion object {
        val SMALL = DpSize(110.dp, 50.dp)
        val SQUARE = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
    }
}

class ListeningWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListeningWidget()
}

@Composable
fun ListeningContent(data: ListeningData) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(actionStartActivity(WidgetData.open(context, "tracks://summary")))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        when {
            size.height < 100.dp -> Compact(data)
            size.width >= 240.dp -> Wide(data)
            else -> Square(data)
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_headphones),
            contentDescription = null,
            modifier = GlanceModifier.size(16.dp),
            colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.primary),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text("Listening", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun BigTime(ms: Long, caption: String) {
    Column {
        Text(
            WidgetData.duration(ms),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(caption, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
    }
}

@Composable
private fun StreakChip(streak: Int) {
    if (streak < 2) return
    Row(
        GlanceModifier
            .background(GlanceTheme.colors.tertiaryContainer)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_streak),
            contentDescription = null,
            modifier = GlanceModifier.size(13.dp),
            colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onTertiaryContainer),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            "$streak days in a row",
            style = TextStyle(color = GlanceTheme.colors.onTertiaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun Bars(data: ListeningData, height: androidx.compose.ui.unit.Dp, labels: Boolean) {
    val most = data.days.maxOf { it.listenMs }.coerceAtLeast(1)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        data.days.forEachIndexed { index, day ->
            if (index > 0) Spacer(GlanceModifier.width(5.dp))
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                val fraction = day.listenMs.toFloat() / most
                val barHeight = (height.value * fraction).coerceAtLeast(6f).dp
                Box(GlanceModifier.fillMaxWidth().height(height), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        GlanceModifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .cornerRadius(6.dp)
                            .background(if (day.isToday) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer),
                    ) {}
                }
                if (labels) {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        day.label,
                        style = TextStyle(
                            color = if (day.isToday) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Compact(data: ListeningData) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                WidgetData.duration(data.todayMs),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                if (data.streak > 1) "today, ${data.streak} days in a row" else "today",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Square(data: ListeningData) {
    Column(GlanceModifier.fillMaxSize()) {
        Header()
        Spacer(GlanceModifier.height(6.dp))
        BigTime(data.todayMs, "today")
        Spacer(GlanceModifier.defaultWeight())
        Bars(data, height = 36.dp, labels = false)
    }
}

@Composable
private fun Wide(data: ListeningData) {
    Row(GlanceModifier.fillMaxSize()) {
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            Header()
            Spacer(GlanceModifier.defaultWeight())
            BigTime(data.todayMs, "today, ${WidgetData.duration(data.weekMs)} this week")
            Spacer(GlanceModifier.height(8.dp))
            StreakChip(data.streak)
        }
        Spacer(GlanceModifier.width(16.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.Bottom) {
            Bars(data, height = 64.dp, labels = true)
        }
    }
}
