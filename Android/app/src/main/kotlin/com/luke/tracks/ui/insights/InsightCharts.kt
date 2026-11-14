package com.luke.tracks.ui.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** One bar: its value, the label under it (or null), and what a screen reader says. */
data class BarPoint(val value: Float, val label: String?, val detail: String)

/** How a chart's axis counts: minutes of listening or plays. */
enum class AxisKind { Minutes, Count }

/**
 * Progress from 0 to 1 that springs in when [key] first shows or changes, for charts that
 * grow into place. Settles and stops: nothing animates while idle.
 */
@Composable
fun rememberEntrance(key: Any?): Float {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    return progress.value
}

/** A bar's share of the entrance, so bars rise one after another. */
private fun staggered(progress: Float, index: Int, count: Int, spread: Float = 0.5f): Float {
    if (count <= 1) return progress
    val start = spread * index / (count - 1)
    return ((progress - start) / (1f - spread)).coerceIn(0f, 1.15f)
}

private fun niceStep(top: Float, axis: AxisKind): Float {
    if (top <= 0f) return 1f
    if (axis == AxisKind.Minutes) {
        val steps = listOf(5f, 10f, 15f, 30f, 60f, 120f, 180f, 240f, 360f, 600f, 1200f, 1800f, 3000f, 6000f, 12000f, 30000f, 60000f)
        return steps.firstOrNull { top / it <= 3f } ?: steps.last()
    }
    val raw = top / 3f
    val magnitude = 10f.pow(floor(log10(raw)))
    return listOf(1f, 2f, 2.5f, 5f, 10f).map { it * magnitude }.first { it >= raw }.coerceAtLeast(1f)
}

fun axisLabel(value: Float, axis: AxisKind): String = when (axis) {
    AxisKind.Count -> Format.number(value.toLong())
    AxisKind.Minutes -> if (value == 0f) "0" else if (value >= 60f && value % 60f == 0f) "${(value / 60).toInt()}h" else Format.compactDuration((value * 60_000).toLong())
}

/**
 * Bars with a dashed average, an axis on the right, and scrubbing: touch and slide to read
 * each bar, with a tick as you cross each one.
 */
@Composable
fun InsightBarChart(
    points: List<BarPoint>,
    modifier: Modifier = Modifier,
    axis: AxisKind = AxisKind.Minutes,
    average: Float? = null,
    height: Dp = 184.dp,
    summary: String,
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMediumEmphasized.copy(color = scheme.inverseOnSurface)
    val progress = rememberEntrance(points)
    val haptics = LocalHapticFeedback.current
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val count = points.size
    val axisWidth = 36.dp

    fun indexAt(x: Float, width: Float, axisPx: Float): Int? {
        if (count == 0) return null
        val slot = (width - axisPx) / count
        return (x / slot).toInt().coerceIn(0, count - 1)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = summary }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val i = indexAt(offset.x, size.width.toFloat(), axisWidth.toPx())
                    selected = if (selected == i) null else i
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragEnd = {},
                ) { change, _ ->
                    val i = indexAt(change.position.x, size.width.toFloat(), axisWidth.toPx())
                    if (i != selected) {
                        selected = i
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                }
            },
    ) {
        val axisPx = axisWidth.toPx()
        val labelHeight = 22.dp.toPx()
        val top = 26.dp.toPx()
        val chartBottom = size.height - labelHeight
        val chartHeight = chartBottom - top
        val plotWidth = size.width - axisPx
        val maxValue = max(points.maxOfOrNull { it.value } ?: 0f, average ?: 0f)
        val step = niceStep(maxValue, axis)
        val ceiling = max(step, (kotlin.math.ceil(maxValue / step) * step))
        fun y(v: Float) = chartBottom - chartHeight * (v / ceiling)

        // Grid and axis.
        var gridValue = 0f
        while (gridValue <= ceiling + 0.001f) {
            val gy = y(gridValue)
            drawLine(
                scheme.outlineVariant.copy(alpha = 0.6f), Offset(0f, gy), Offset(plotWidth, gy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (gridValue == 0f) null else PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
            )
            val text = measurer.measure(axisLabel(gridValue, axis), labelStyle)
            drawText(text, topLeft = Offset(plotWidth + 8.dp.toPx(), gy - text.size.height / 2f))
            gridValue += step
        }

        if (count == 0) return@Canvas
        val slot = plotWidth / count
        val barWidth = min(slot * 0.62f, 28.dp.toPx())
        val radius = min(barWidth / 2f, 8.dp.toPx())
        points.forEachIndexed { i, point ->
            val cx = slot * i + slot / 2f
            val grow = staggered(progress, i, count)
            val h = (chartHeight * (point.value / ceiling) * grow).coerceAtLeast(0f)
            val isSelected = selected == i
            val color = when {
                selected == null || isSelected -> scheme.primary
                else -> scheme.primary.copy(alpha = 0.38f)
            }
            if (h > 0.5f) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx - barWidth / 2f, chartBottom - max(h, radius * 2f).coerceAtMost(chartHeight * 1.1f)),
                    size = Size(barWidth, max(h, radius * 2f).coerceAtMost(chartHeight * 1.1f)),
                    cornerRadius = CornerRadius(radius),
                )
            }
            point.label?.let { label ->
                val text = measurer.measure(label, labelStyle)
                val x = (cx - text.size.width / 2f).coerceIn(0f, plotWidth - text.size.width)
                drawText(text, topLeft = Offset(x, chartBottom + 6.dp.toPx()))
            }
        }

        // The average, dashed, in the tertiary colour so it reads apart from the bars.
        if (average != null && average > 0f) {
            val ay = y(average)
            drawLine(
                scheme.tertiary, Offset(0f, ay), Offset(plotWidth, ay),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            val text = measurer.measure("avg", labelStyle.copy(color = scheme.tertiary))
            drawText(text, topLeft = Offset(plotWidth - text.size.width - 2.dp.toPx(), ay - text.size.height - 2.dp.toPx()))
        }

        selected?.let { i -> drawTip(measurer, tipStyle, points[i].detail, slot * i + slot / 2f, plotWidth, scheme.inverseSurface) }
    }
}

private fun DrawScope.drawTip(measurer: TextMeasurer, style: TextStyle, text: String, cx: Float, width: Float, color: Color) {
    val measured = measurer.measure(text, style)
    val padX = 10.dp.toPx()
    val padY = 4.dp.toPx()
    val w = measured.size.width + padX * 2
    val h = measured.size.height + padY * 2
    val left = (cx - w / 2f).coerceIn(0f, width - w)
    drawRoundRect(color, Offset(left, 0f), Size(w, h), CornerRadius(h / 2f))
    drawText(measured, topLeft = Offset(left + padX, padY))
}

/**
 * The 24-hour listening clock: a bar for every hour around a dial, midnight at the top, the
 * busiest hour picked out.
 */
@Composable
fun ListeningClock(hours: List<Long>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val progress = rememberEntrance(hours)
    val max = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
    val summary = if (hours.sum() == 0L) "No listening yet" else "Listening by hour of day. Most around ${Format.hour(peak)}."
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().semantics { contentDescription = summary }) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val inner = r * 0.42f
            val outer = r * 0.76f
            val stroke = (2f * PI.toFloat() * inner / 24f) * 0.58f
            for (h in 0 until 24) {
                val angle = (h / 24f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val dir = Offset(cos(angle), sin(angle))
                val start = center + dir * (inner + stroke / 2f)
                val trackEnd = center + dir * (outer - stroke / 2f)
                drawLine(scheme.surfaceContainerHighest, start, trackEnd, strokeWidth = stroke, cap = StrokeCap.Round)
                val fraction = (hours[h].toFloat() / max) * staggered(progress, h, 24, 0.6f)
                if (hours[h] > 0) {
                    val length = (outer - inner - stroke) * fraction
                    val end = start + dir * length.coerceAtLeast(0.01f)
                    drawLine(
                        if (h == peak) scheme.tertiary else scheme.primary,
                        start, end, strokeWidth = stroke, cap = StrokeCap.Round,
                    )
                }
            }
            listOf(0 to "12AM", 6 to "6AM", 12 to "12PM", 18 to "6PM").forEach { (h, label) ->
                val angle = (h / 24f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val text = measurer.measure(label, labelStyle)
                val at = center + Offset(cos(angle), sin(angle)) * (r * 0.93f)
                drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
            }
        }
        if (hours.sum() > 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.hour(peak), style = MaterialTheme.typography.titleLargeEmphasized, color = scheme.onSurface)
                Text("busiest", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Weekdays in the reader's own order, as Sunday-first indices. */
fun localWeekdayOrder(locale: Locale = Locale.getDefault()): List<Int> {
    val first = WeekFields.of(locale).firstDayOfWeek
    val firstIndex = if (first == DayOfWeek.SUNDAY) 0 else first.value
    return List(7) { (firstIndex + it) % 7 }
}

/** A bar for each day of the week, the biggest day picked out. */
@Composable
fun WeekdayChart(weekdays: List<Long>, modifier: Modifier = Modifier, height: Dp = 132.dp) {
    val scheme = MaterialTheme.colorScheme
    val order = remember { localWeekdayOrder() }
    val progress = rememberEntrance(weekdays)
    val max = weekdays.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = weekdays.indices.maxByOrNull { weekdays[it] } ?: 0
    val summary = if (weekdays.sum() == 0L) "No listening yet" else "Plays by day of the week. Most on ${Format.weekdayName(peak)}."
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = summary },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        order.forEachIndexed { position, day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(Modifier.height(height).width(24.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.matchParentSize().background(scheme.surfaceContainerHighest, RoundedCornerShape(12.dp)))
                    val f = (weekdays[day].toFloat() / max) * staggered(progress, position, 7)
                    if (weekdays[day] > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(f.coerceIn(0.1f, 1f))
                                .background(if (day == peak) scheme.tertiary else scheme.primary, RoundedCornerShape(12.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    Format.weekdayName(day, java.time.format.TextStyle.NARROW),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (day == peak) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The week by hour: a cell for every hour of every day, deeper for more listening. */
@Composable
fun WeekHeatMap(heat: List<Long>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val order = remember { localWeekdayOrder() }
    val progress = rememberEntrance(heat)
    val max = heat.maxOrNull()?.coerceAtLeast(1) ?: 1
    val busiest = heat.indices.maxByOrNull { heat[it] } ?: 0
    val summary = if (heat.sum() == 0L) "No listening yet" else
        "Listening by day and hour. Most on ${Format.weekdayName(busiest / 24)}s around ${Format.hour(busiest % 24)}."
    Canvas(modifier.fillMaxWidth().aspectRatio(24f / 8.2f).semantics { contentDescription = summary }) {
        val labelWidth = 18.dp.toPx()
        val labelHeight = 18.dp.toPx()
        val gap = 2.dp.toPx()
        val cell = min((size.width - labelWidth) / 24f, (size.height - labelHeight) / 7f)
        val gridWidth = cell * 24f
        val left = labelWidth + (size.width - labelWidth - gridWidth) / 2f
        order.forEachIndexed { row, day ->
            val text = measurer.measure(Format.weekdayName(day, java.time.format.TextStyle.NARROW), labelStyle)
            drawText(text, topLeft = Offset(left - labelWidth, row * cell + (cell - text.size.height) / 2f))
            for (h in 0 until 24) {
                val v = heat[day * 24 + h].toFloat() / max
                val shown = (v * staggered(progress, h, 24, 0.7f)).coerceIn(0f, 1f)
                val color = if (v == 0f) scheme.surfaceContainerHighest else lerp(scheme.primaryContainer, scheme.primary, shown)
                drawRoundRect(
                    color,
                    Offset(left + h * cell + gap / 2f, row * cell + gap / 2f),
                    Size(cell - gap, cell - gap),
                    CornerRadius((cell - gap) * 0.3f),
                )
            }
        }
        listOf(0 to "12AM", 6 to "6AM", 12 to "12PM", 18 to "6PM").forEach { (h, label) ->
            val text = measurer.measure(label, labelStyle)
            drawText(text, topLeft = Offset(left + h * cell, 7 * cell + 4.dp.toPx()))
        }
    }
}

/** One part of a proportion bar. */
data class Share(val label: String, val value: Long, val color: Color)

/** A proportion bar in rounded segments with a legend underneath. */
@Composable
fun ShareBar(shares: List<Share>, modifier: Modifier = Modifier) {
    val visible = shares.filter { it.value > 0 }
    val total = visible.sumOf { it.value }.coerceAtLeast(1)
    val progress = rememberEntrance(shares)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val gap = 4.dp.toPx()
            val available = size.width - gap * (visible.size - 1).coerceAtLeast(0)
            var x = 0f
            visible.forEach { share ->
                val w = available * share.value / total * progress.coerceAtMost(1f)
                drawRoundRect(share.color, Offset(x, 0f), Size(max(w, 0f), size.height), CornerRadius(min(size.height / 2f, w / 2f)))
                x += w + gap
            }
        }
        Spacer(Modifier.height(16.dp))
        visible.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                pair.forEach { share ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(share.color, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(share.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${(share.value * 100 / total)}%",
                            style = MaterialTheme.typography.bodyMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
