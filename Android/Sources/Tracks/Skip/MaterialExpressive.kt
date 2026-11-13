package tracks.module

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Material 3 Expressive, as Tracks uses it. Compose Material 3 1.4 keeps most of Expressive
// (its shapes, motion scheme, loading indicator and wavy progress) internal or in alpha, so
// these are built from the same parts: `graphics-shapes` polygons and morphs, and springs with
// Expressive's values.
//
// Everything Swift calls takes Double, Int, String and Boolean, which is what the transpiled
// `#if SKIP` code passes.

// MARK: - Shapes

private val polygons = HashMap<String, RoundedPolygon>()

/// One of Material's expressive shapes, normalised to a unit square. Artists are scalloped
/// (`cookie9`), a playing record is `cookie12`, and the rest give containers some character.
fun expressivePolygon(name: String): RoundedPolygon = synchronized(polygons) {
    polygons.getOrPut(name) { makePolygon(name).normalized() }
}

private fun makePolygon(name: String): RoundedPolygon = when (name) {
    "square" -> RoundedPolygon.rectangle(width = 2f, height = 2f, rounding = CornerRounding(0.6f))
    "cookie4" -> RoundedPolygon.star(4, innerRadius = 0.72f, rounding = CornerRounding(0.5f))
    "cookie6" -> RoundedPolygon.star(6, innerRadius = 0.78f, rounding = CornerRounding(0.5f))
    "cookie9" -> RoundedPolygon.star(9, innerRadius = 0.84f, rounding = CornerRounding(0.5f))
    "cookie12" -> RoundedPolygon.star(12, innerRadius = 0.87f, rounding = CornerRounding(0.5f))
    "sunny" -> RoundedPolygon.star(8, innerRadius = 0.8f, rounding = CornerRounding(0.15f))
    "softBurst" -> RoundedPolygon.star(10, innerRadius = 0.72f, rounding = CornerRounding(0.2f), innerRounding = CornerRounding(0.1f))
    "clover4" -> RoundedPolygon.star(4, innerRadius = 0.4f, rounding = CornerRounding(1f), innerRounding = CornerRounding(0.15f))
    "clover8" -> RoundedPolygon.star(8, innerRadius = 0.62f, rounding = CornerRounding(0.6f), innerRounding = CornerRounding(0.1f))
    "flower" -> RoundedPolygon.star(5, innerRadius = 0.55f, rounding = CornerRounding(0.8f), innerRounding = CornerRounding(0.2f))
    "pentagon" -> RoundedPolygon(5, rounding = CornerRounding(0.3f))
    "gem" -> RoundedPolygon(6, rounding = CornerRounding(0.35f))
    "pill" -> RoundedPolygon.rectangle(width = 2f, height = 1.25f, rounding = CornerRounding(0.62f))
    else -> RoundedPolygon.circle(numVertices = 12)
}

/// Adds cubics in the unit square to a path the size of `size`, turned `degrees` about the
/// centre.
private fun Path.addCubics(cubics: List<Cubic>, size: Size, degrees: Float) {
    val radians = degrees * PI.toFloat() / 180f
    val c = cos(radians)
    val s = sin(radians)
    fun x(px: Float, py: Float) = ((px - 0.5f) * c - (py - 0.5f) * s + 0.5f) * size.width
    fun y(px: Float, py: Float) = ((px - 0.5f) * s + (py - 0.5f) * c + 0.5f) * size.height
    val first = cubics.firstOrNull() ?: return
    moveTo(x(first.anchor0X, first.anchor0Y), y(first.anchor0X, first.anchor0Y))
    for (cubic in cubics) {
        cubicTo(
            x(cubic.control0X, cubic.control0Y), y(cubic.control0X, cubic.control0Y),
            x(cubic.control1X, cubic.control1Y), y(cubic.control1X, cubic.control1Y),
            x(cubic.anchor1X, cubic.anchor1Y), y(cubic.anchor1X, cubic.anchor1Y)
        )
    }
    close()
}

/// A Compose shape from an expressive polygon or a point along a morph.
class PolygonShape(private val cubics: List<Cubic>, private val degrees: Float = 0f) : Shape {
    constructor(name: String, rotation: Double) : this(expressivePolygon(name).cubics, rotation.toFloat())

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply { addCubics(cubics, size, degrees) })

    override fun equals(other: Any?): Boolean =
        other is PolygonShape && other.cubics == cubics && other.degrees == degrees

    override fun hashCode(): Int = cubics.hashCode() * 31 + degrees.hashCode()
}

/// Clips to a named expressive shape, for `View.expressiveClip` in Swift.
fun Modifier.expressiveClip(name: String, rotation: Double): Modifier = clip(PolygonShape(name, rotation))

// MARK: - Colour from a cover

private val seeds = LruCache<String, Int>(96)
private const val NO_SEED = 0

/// The colour a cover is built around: the hue with the most colourful pixels, as Android picks
/// a wallpaper's. Null for a black-and-white cover, which keeps the app's own colours.
suspend fun coverSeed(context: Context, url: String): Int? {
    seeds.get(url)?.let { return if (it == NO_SEED) null else it }
    val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request)
    val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    val seed = withContext(Dispatchers.Default) { seedColor(bitmap) }
    seeds.put(url, seed ?: NO_SEED)
    return seed
}

private fun seedColor(bitmap: Bitmap): Int? {
    val small = if (bitmap.width > 64 || bitmap.height > 64) Bitmap.createScaledBitmap(bitmap, 48, 48, true) else bitmap
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    val weight = FloatArray(36)
    val hueX = FloatArray(36)
    val hueY = FloatArray(36)
    val chroma = FloatArray(36)
    val hct = FloatArray(3)
    for (pixel in pixels) {
        if (android.graphics.Color.alpha(pixel) < 200) continue
        ColorUtils.colorToM3HCT(pixel, hct)
        if (hct[1] < 10f || hct[2] < 12f || hct[2] > 94f) continue
        val bucket = (hct[0] / 10f).toInt().coerceIn(0, 35)
        val radians = hct[0] * PI.toFloat() / 180f
        weight[bucket] += hct[1]
        hueX[bucket] += cos(radians) * hct[1]
        hueY[bucket] += sin(radians) * hct[1]
        chroma[bucket] += hct[1] * hct[1]
    }
    // Neighbouring buckets together, so a hue on a boundary isn't split in two.
    var best = -1
    var bestWeight = 0f
    for (bucket in 0 until 36) {
        val around = weight[(bucket + 35) % 36] + weight[bucket] + weight[(bucket + 1) % 36]
        if (around > bestWeight) { bestWeight = around; best = bucket }
    }
    // Too little colour to build on: a monochrome cover.
    if (best < 0 || bestWeight < pixels.size * 1.5f) return null
    var x = 0f; var y = 0f; var w = 0f; var c = 0f
    for (bucket in intArrayOf((best + 35) % 36, best, (best + 1) % 36)) {
        x += hueX[bucket]; y += hueY[bucket]; w += weight[bucket]; c += chroma[bucket]
    }
    val hue = ((atan2(y, x) * 180f / PI.toFloat()) + 360f) % 360f
    return ColorUtils.M3HCTToColor(hue, c / w, 50f)
}

private fun tone(hue: Float, chroma: Float, tone: Float) = Color(ColorUtils.M3HCTToColor(hue, chroma, tone))

/// A Material colour scheme around a seed: primary keeps the cover's own hue and much of its
/// colourfulness, as Material's content-based colour does, and the surfaces carry a hint of it.
fun schemeFromSeed(seed: Int, dark: Boolean, base: ColorScheme): ColorScheme {
    val hct = FloatArray(3)
    ColorUtils.colorToM3HCT(seed, hct)
    val hue = hct[0]
    val primaryChroma = hct[1].coerceIn(28f, 64f)
    val secondaryChroma = max(primaryChroma - 32f, primaryChroma * 0.5f)
    val tertiaryHue = (hue + 45f) % 360f
    val tertiaryChroma = max(24f, primaryChroma * 0.7f)
    val neutral = (primaryChroma / 8f).coerceIn(4f, 10f)
    val neutralVariant = neutral + 4f
    fun p(t: Float) = tone(hue, primaryChroma, t)
    fun s(t: Float) = tone(hue, secondaryChroma, t)
    fun t(t: Float) = tone(tertiaryHue, tertiaryChroma, t)
    fun n(t: Float) = tone(hue, neutral, t)
    fun nv(t: Float) = tone(hue, neutralVariant, t)
    return if (dark) base.copy(
        primary = p(80f), onPrimary = p(20f), primaryContainer = p(30f), onPrimaryContainer = p(90f), inversePrimary = p(40f),
        secondary = s(80f), onSecondary = s(20f), secondaryContainer = s(30f), onSecondaryContainer = s(90f),
        tertiary = t(80f), onTertiary = t(20f), tertiaryContainer = t(30f), onTertiaryContainer = t(90f),
        background = n(6f), onBackground = n(90f), surface = n(6f), onSurface = n(90f),
        surfaceVariant = nv(30f), onSurfaceVariant = nv(80f), surfaceTint = p(80f),
        inverseSurface = n(90f), inverseOnSurface = n(20f), outline = nv(60f), outlineVariant = nv(30f),
        surfaceBright = n(24f), surfaceDim = n(6f), surfaceContainerLowest = n(4f), surfaceContainerLow = n(10f),
        surfaceContainer = n(12f), surfaceContainerHigh = n(17f), surfaceContainerHighest = n(22f)
    ) else base.copy(
        primary = p(40f), onPrimary = p(100f), primaryContainer = p(90f), onPrimaryContainer = p(10f), inversePrimary = p(80f),
        secondary = s(40f), onSecondary = s(100f), secondaryContainer = s(90f), onSecondaryContainer = s(10f),
        tertiary = t(40f), onTertiary = t(100f), tertiaryContainer = t(90f), onTertiaryContainer = t(10f),
        background = n(98f), onBackground = n(10f), surface = n(98f), onSurface = n(10f),
        surfaceVariant = nv(90f), onSurfaceVariant = nv(30f), surfaceTint = p(40f),
        inverseSurface = n(20f), inverseOnSurface = n(95f), outline = nv(50f), outlineVariant = nv(80f),
        surfaceBright = n(98f), surfaceDim = n(87f), surfaceContainerLowest = n(100f), surfaceContainerLow = n(96f),
        surfaceContainer = n(94f), surfaceContainerHigh = n(92f), surfaceContainerHighest = n(90f)
    )
}

/// The scheme for a cover, easing from the last one while the next is worked out, so the page
/// changes colour with the song rather than flashing.
@Composable
fun rememberCoverScheme(url: String, base: ColorScheme, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    var seed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(url) {
        seed = if (url.isEmpty()) null else coverSeed(context, url)
    }
    val target = remember(seed, dark, base) { seed?.let { schemeFromSeed(it, dark, base) } ?: base }
    return animateScheme(target)
}

@Composable
private fun animateScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 600)
    @Composable fun a(color: Color) = animateColorAsState(color, spec, label = "coverColor").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        secondary = a(target.secondary), secondaryContainer = a(target.secondaryContainer),
        onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary), tertiaryContainer = a(target.tertiaryContainer),
        onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background), surface = a(target.surface), onSurface = a(target.onSurface),
        onSurfaceVariant = a(target.onSurfaceVariant), outlineVariant = a(target.outlineVariant),
        surfaceContainerLowest = a(target.surfaceContainerLowest), surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainer = a(target.surfaceContainer), surfaceContainerHigh = a(target.surfaceContainerHigh),
        surfaceContainerHighest = a(target.surfaceContainerHighest)
    )
}

// MARK: - Frame clock

/// Degrees turned so far at `degreesPerSecond`, advancing only while `running`.
@Composable
private fun rememberTurning(running: Boolean, degreesPerSecond: Float): Float {
    var degrees by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) degrees = (degrees + (now - last) / 1_000_000_000f * degreesPerSecond) % 3600f
                last = now
            }
        }
    }
    return degrees
}

// MARK: - Covers

/// Now Playing's cover. Playing, it's a scalloped disc whose edge turns slowly, like a record;
/// paused, it settles into a rounded square and steps back a little. Swipe it for the next or
/// previous song.
@Composable
fun ExpressiveMorphingCover(url: String, playing: Boolean, onNext: () -> Unit, onPrevious: () -> Unit, modifier: Modifier) {
    val morph = remember { Morph(expressivePolygon("square"), expressivePolygon("cookie9")) }
    val progress by animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 1f, stiffness = 260f), label = "coverShape")
    val scale by animateFloatAsState(if (playing) 1f else 0.9f, spring(dampingRatio = 0.55f, stiffness = 300f), label = "coverScale")
    // The scallops repeat every 40°, so the edge only needs to turn that far and can ease back
    // to square without spinning.
    val turning = rememberTurning(playing, 8f)
    val shape = PolygonShape(morph.asCubics(progress), (turning % 40f) * progress)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    Box(
        modifier
            .pointerInput(Unit) {
                val width = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val travelled = offset.value
                            if (abs(travelled) > width * 0.22f) {
                                val forward = travelled < 0
                                offset.animateTo(if (forward) -width else width, tween(160))
                                if (forward) next() else previous()
                                offset.snapTo(if (forward) width * 0.4f else -width * 0.4f)
                            }
                            offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } }
                ) { change, amount ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + amount) }
                }
            }
            .graphicsLayer {
                translationX = offset.value
                scaleX = scale
                scaleY = scale
                alpha = 1f - min(abs(offset.value) / (size.width * 1.4f), 0.6f)
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics(mergeDescendants = true) {
                contentDescription = "Cover"
                customActions = listOf(
                    CustomAccessibilityAction("Next song") { next(); true },
                    CustomAccessibilityAction("Previous song") { previous(); true }
                )
            }
    ) {
        CoverImage(url)
    }
}

/// The mini player's cover: a scalloped record that turns while the music plays.
@Composable
fun ExpressiveSpinningCover(url: String, playing: Boolean, modifier: Modifier) {
    val degrees = rememberTurning(playing, 36f)
    Box(
        modifier
            .graphicsLayer { rotationZ = degrees }
            .clip(PolygonShape("cookie12", 0.0))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        CoverImage(url)
    }
}

@Composable
private fun CoverImage(url: String) {
    if (url.isEmpty()) {
        Icon(
            MaterialIcons.vector("Album", false),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize().padding(24.dp)
        )
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

// MARK: - Wavy progress

/// Android 16's media seek bar: the played part is a wave that travels while the music plays and
/// flattens when it stops, then a straight track and a stop dot. Drag or tap to seek.
///
/// `interactive` false is the mini player's line: thinner, a shallower wave, no handle.
@Composable
fun ExpressiveWavySeekBar(
    position: Double,
    duration: Double,
    playing: Boolean,
    interactive: Boolean,
    valueLabel: String,
    onSeek: (Double) -> Unit,
    onScrub: (Double) -> Unit,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Where a seek was asked for, until the player catches up, so the handle doesn't jump back.
    var held by remember { mutableStateOf<Float?>(null) }
    val reported = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    LaunchedEffect(held) {
        if (held != null) { delay(1500); held = null }
    }
    LaunchedEffect(reported) {
        held?.let { if (abs(it - reported) < 0.02f) held = null }
    }
    // The service reports twice a second; the handle glides between.
    val glided by animateFloatAsState(reported, tween(durationMillis = 500, easing = LinearEasing), label = "position")
    val fraction = dragging ?: held ?: glided
    val amplitude by animateFloatAsState(
        if (playing && dragging == null) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow), label = "wave"
    )
    val phase = rememberTurning(playing, 360f / 1.6f)
    val height = if (interactive) 40.dp else 10.dp

    fun fractionAt(x: Float, width: Float, inset: Float) = ((x - inset) / (width - inset * 2)).coerceIn(0f, 1f)

    var gestures: Modifier = Modifier
    if (interactive) {
        gestures = Modifier
            .pointerInput(duration) {
                val inset = 12.dp.toPx()
                detectTapGestures { offset ->
                    val target = fractionAt(offset.x, size.width.toFloat(), inset)
                    held = target
                    seek(target * duration)
                }
            }
            .pointerInput(duration) {
                val inset = 12.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = fractionAt(offset.x, size.width.toFloat(), inset)
                        scrub(dragging!! * duration)
                    },
                    onDragEnd = {
                        dragging?.let { target -> held = target; seek(target * duration) }
                        dragging = null
                        scrub(-1.0)
                    },
                    onDragCancel = { dragging = null; scrub(-1.0) }
                ) { change, _ ->
                    change.consume()
                    dragging = fractionAt(change.position.x, size.width.toFloat(), inset)
                    scrub(dragging!! * duration)
                }
            }
            .semantics {
                contentDescription = "Position"
                stateDescription = valueLabel
                progressBarRangeInfo = ProgressBarRangeInfo(fraction * duration.toFloat(), 0f..max(duration.toFloat(), 1f))
                setProgress { value -> seek(value.toDouble()); true }
            }
    } else {
        gestures = Modifier.clearAndSetSemantics { }
    }

    Canvas(modifier.fillMaxWidth().height(height).then(gestures)) {
        val stroke = (if (interactive) 5.dp else 3.dp).toPx()
        val inset = if (interactive) 12.dp.toPx() else stroke / 2
        val width = size.width - inset * 2
        val cy = size.height / 2
        val headX = inset + width * fraction
        val gap = if (interactive) 6.dp.toPx() else 3.dp.toPx()
        val wavelength = (if (interactive) 36.dp else 20.dp).toPx()
        val waveHeight = (if (interactive) 3.5.dp else 1.6.dp).toPx() * amplitude
        val phaseRadians = phase * PI.toFloat() / 180f

        // The played part: a wave, fading in from flat over its first wavelength.
        val activeEnd = headX - if (interactive) gap + 2.dp.toPx() else 0f
        if (activeEnd > inset) {
            val wave = Path()
            var x = inset
            wave.moveTo(x, cy)
            while (x < activeEnd) {
                x = min(x + 2f, activeEnd)
                val ramp = min((x - inset) / wavelength, 1f)
                wave.lineTo(x, cy + waveHeight * ramp * sin((x - inset) / wavelength * 2f * PI.toFloat() - phaseRadians))
            }
            drawPath(wave, colors.primary, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        // What's left to play, and the dot where it stops.
        val restStart = headX + gap + if (interactive) 2.dp.toPx() else 0f
        val end = inset + width
        if (restStart < end) {
            val rest = if (interactive) colors.secondaryContainer else colors.primary.copy(alpha = 0.22f)
            drawLine(rest, Offset(restStart, cy), Offset(end, cy), strokeWidth = stroke, cap = StrokeCap.Round)
            if (interactive) drawCircle(colors.primary, radius = 2.dp.toPx(), center = Offset(end, cy))
        }
        // The handle: a rounded upright bar, taller while held.
        if (interactive) {
            val handleHeight = (if (dragging != null) 30.dp else 22.dp).toPx()
            drawLine(
                colors.primary,
                Offset(headX, cy - handleHeight / 2), Offset(headX, cy + handleHeight / 2),
                strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round
            )
        }
    }
}

// MARK: - Loading

private val loadingShapes = listOf("softBurst", "cookie9", "pentagon", "pill", "sunny", "cookie4", "circle")

/// Material 3 Expressive's loading indicator: one shape turning and morphing into the next.
@Composable
fun ExpressiveLoadingIndicator(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val morphs = remember {
        loadingShapes.indices.map { Morph(expressivePolygon(loadingShapes[it]), expressivePolygon(loadingShapes[(it + 1) % loadingShapes.size])) }
    }
    val transition = rememberInfiniteTransition(label = "loading")
    val step by transition.animateFloat(
        0f, morphs.size.toFloat(),
        infiniteRepeatable(tween(durationMillis = 650 * morphs.size, easing = LinearEasing), RepeatMode.Restart),
        label = "step"
    )
    val index = step.toInt().coerceIn(0, morphs.size - 1)
    // Each morph happens in the first two thirds of its step, then holds, with a spring's ease.
    val local = ((step - index) / 0.66f).coerceIn(0f, 1f)
    val eased = 1f - (1f - local) * (1f - local) * (1f - local)
    val path = remember { Path() }
    Canvas(modifier.size(48.dp).semantics { contentDescription = "Loading"; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val side = 38.dp.toPx()
        val origin = (size.width - side) / 2
        path.rewind()
        path.addCubics(morphs[index].asCubics(eased), Size(side, side), step * 140f)
        path.translate(Offset(origin, origin))
        drawPath(path, color)
    }
}

// MARK: - Buttons

/// A Material 3 Expressive button: a pill that squares off while pressed, on a spring.
/// `emphasis` is "filled", "tonal", "tertiary" or "surface" (for a button on a coloured card).
@Composable
fun ExpressivePillButton(label: String, icon: String, emphasis: String, height: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) (height / 4).dp else (height / 2).dp,
        spring(dampingRatio = 0.5f, stiffness = 900f), label = "buttonShape"
    )
    val (container, content) = when (emphasis) {
        "filled" -> colors.primary to colors.onPrimary
        "tertiary" -> colors.tertiaryContainer to colors.onTertiaryContainer
        "surface" -> colors.surfaceContainerLowest to colors.primary
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
        interactionSource = interaction
    ) {
        Row(
            Modifier.padding(horizontal = if (height >= 56) 24.dp else 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon.isNotEmpty()) {
                Icon(MaterialIcons.vector(icon, true), contentDescription = null, modifier = Modifier.size(if (height >= 56) 24.dp else 20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = if (height >= 56) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/// Now Playing's play and pause: round while paused and a softened square while playing, as
/// Android 16's media controls are, squeezing further when pressed.
@Composable
fun ExpressivePlayPause(playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(
        when { pressed -> 22f; playing -> 30f; else -> 50f },
        spring(dampingRatio = 0.5f, stiffness = 700f), label = "playShape"
    )
    val width by animateDpAsState(if (playing) 112.dp else 88.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "playWidth")
    Surface(
        onClick = onClick,
        modifier = modifier.width(width).height(88.dp).semantics { contentDescription = if (playing) "Pause" else "Play" },
        shape = RoundedCornerShape(percent = corner.toInt()),
        color = colors.primary,
        contentColor = colors.onPrimary,
        interactionSource = interaction
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(MaterialIcons.vector(if (playing) "Pause" else "PlayArrow", true), contentDescription = null, modifier = Modifier.size(40.dp))
        }
    }
}

// MARK: - Carousel

/// Albums as Material's hero carousel, as Google Photos and the Play Store show collections:
/// one large, the next smaller, and a sliver of what's after. Names fade in as an item grows.
/// `json` is `[{url, title, subtitle}]`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveCarousel(json: String, onOpen: (Int) -> Unit, modifier: Modifier) {
    val items = remember(json) {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Triple(item.optString("url"), item.optString("title"), item.optString("subtitle"))
        }
    }
    val open by rememberUpdatedState(onOpen)
    val state = rememberCarouselState { items.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 228.dp,
        modifier = modifier.fillMaxWidth().height(228.dp),
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) { index ->
        val (url, title, subtitle) = items[index]
        val info = carouselItemDrawInfo
        // Names only on an item that's nearly full size, so they're never cut by the mask.
        val grown = if (info.maxSize > info.minSize) ((info.size - info.minSize) / (info.maxSize - info.minSize)).coerceIn(0f, 1f) else 1f
        val shown = ((grown - 0.75f) / 0.25f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClickLabel = "Open") { open(index) }
                .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" }
        ) {
            CoverImage(url)
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = shown }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))))
                    .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 14.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// MARK: - Playing bars

/// Three bars that bounce while the song in this row plays, and rest when it's paused, in
/// place of its number.
@Composable
fun ExpressivePlayingBars(playing: Boolean, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "bars")
    val speeds = intArrayOf(520, 380, 610)
    val heights = speeds.map { speed ->
        transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(speed), RepeatMode.Reverse), label = "bar").value
    }
    val rest = floatArrayOf(0.45f, 0.75f, 0.35f)
    Canvas(modifier.size(20.dp).clearAndSetSemantics { }) {
        val barWidth = size.width / 5
        for (index in 0 until 3) {
            val fraction = if (playing) heights[index] else rest[index]
            val barHeight = size.height * fraction
            val x = barWidth / 2 + index * barWidth * 2
            drawLine(color, Offset(x, size.height - barWidth / 2), Offset(x, size.height - barHeight + barWidth / 2), strokeWidth = barWidth, cap = StrokeCap.Round)
        }
    }
}
