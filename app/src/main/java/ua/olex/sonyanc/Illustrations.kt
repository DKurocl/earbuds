@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.graphics.Paint
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * What a setting does, shown rather than told, as Pixel's settings pages do with their animated
 * illustrations: Material's expressive shapes, the app's symbols and the earbud itself, moving
 * through the setting in one short loop. Each loop is [LOOP] seconds.
 */
enum class Topic(val title: String, val line: String) {
    CHAT("Speak-to-Chat", "Start talking and the music pauses. It comes back when you're done."),
    DSEE("DSEE", "Rebuilds the fine detail that compression takes out of music."),
    SIDETONE("Hear my voice in calls", "Your own voice comes back through the earbuds, so you don't talk too loud."),
    TOUCH("Touch controls", "Tap the outer side of an earbud to switch modes or control playback."),
    CYCLE("Noise control cycle", "Each press steps through the modes you choose here."),
    GESTURES("Head gestures", "Nod to answer a call. Shake your head to decline it."),
    AUTO_PAUSE("Pause when removed", "Take an earbud out and the music pauses. Put it back to resume."),
    POWER("Automatic power off", "The earbuds turn themselves off after a while unworn."),
    QUALITY("Bluetooth quality", "Choose richer sound, or a connection that holds in busy places."),
    GUIDANCE("Voice guidance", "Short spoken prompts tell you the battery level and the mode."),
}

private const val LOOP = 4.8f

/** The illustration on a card with its caption; tapping it shows the next one. */
@Composable
fun IllustrationCard(topic: Topic, modifier: Modifier = Modifier, onLoop: () -> Unit = {}, onTap: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp), color = Surfaces.card,
        modifier = modifier.fillMaxWidth().clickable(onClickLabel = "Next tip", onClick = onTap)
            // One thing to a screen reader: the rows below carry the same titles.
            .clearAndSetSemantics { contentDescription = "${topic.title}. ${topic.line}" },
    ) {
        AnimatedContent(
            topic, label = "illustration",
            transitionSpec = {
                (fadeIn(tween(260, 90)) + scaleIn(tween(360, easing = EmphasizedEasing), initialScale = 0.94f)) togetherWith fadeOut(tween(90))
            },
        ) { shown ->
            Column {
                Illustration(shown, Modifier.fillMaxWidth().height(164.dp), onLoop = onLoop)
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                    Text(shown.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text(shown.line, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * One topic, looping. [pose] fixes the time (seconds into the loop) for renders; with animations
 * turned off in Android's settings it rests on a pose that explains the setting by itself.
 * [onLoop] is called each time the loop comes round.
 */
@Composable
fun Illustration(topic: Topic, modifier: Modifier = Modifier, pose: Float? = null, onLoop: () -> Unit = {}) {
    val ctx = LocalContext.current
    val still = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var time by remember(topic) { mutableFloatStateOf(if (still) topic.rest else 0f) }
    val loop by rememberUpdatedState(onLoop)
    if (pose == null && !still) LaunchedEffect(topic) {
        val start = withInfiniteAnimationFrameMillis { it }
        var laps = 0
        while (true) {
            val ms = withInfiniteAnimationFrameMillis { it } - start
            val t = ms / 1000f
            time = t % LOOP
            if (floor(t / LOOP).toInt() > laps) { laps++; loop() }
        }
    }
    val cs = MaterialTheme.colorScheme
    val ink = Ink(
        primary = cs.primary, onPrimary = cs.onPrimary, primaryContainer = cs.primaryContainer, onPrimaryContainer = cs.onPrimaryContainer,
        secondary = cs.secondary, secondaryContainer = cs.secondaryContainer, onSecondaryContainer = cs.onSecondaryContainer,
        tertiary = cs.tertiary, tertiaryContainer = cs.tertiaryContainer, onTertiaryContainer = cs.onTertiaryContainer,
        quiet = cs.surfaceContainerHighest, onQuiet = cs.onSurfaceVariant, line = cs.outlineVariant,
    )
    val bud = painterResource(R.drawable.device_bud_left)
    val shapes = remember { Shapes() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = SymbolFont.get(ctx); textAlign = Paint.Align.CENTER } }
    // Slow drawings: 30 frames a second look the same and let the panel leave 120 Hz.
    Canvas(modifier.preferredFrameRate(30f).clipToBounds()) {
        val scene = Scene(this, pose ?: time, ink, bud, shapes, paint)
        when (topic) {
            Topic.CHAT -> scene.chat()
            Topic.DSEE -> scene.dsee()
            Topic.SIDETONE -> scene.sidetone()
            Topic.TOUCH -> scene.touch()
            Topic.CYCLE -> scene.cycle()
            Topic.GESTURES -> scene.gestures()
            Topic.AUTO_PAUSE -> scene.autoPause()
            Topic.POWER -> scene.power()
            Topic.QUALITY -> scene.quality()
            Topic.GUIDANCE -> scene.guidance()
        }
    }
}

/** The moment of each loop that tells the story on its own. */
val Topic.rest: Float
    get() = when (this) {
        Topic.CHAT -> 2.2f; Topic.DSEE -> 2.0f; Topic.SIDETONE -> 1.3f; Topic.TOUCH -> 1.1f; Topic.CYCLE -> 2.0f
        Topic.GESTURES -> 1.8f; Topic.AUTO_PAUSE -> 2.0f; Topic.POWER -> 3.6f; Topic.QUALITY -> 1.2f; Topic.GUIDANCE -> 1.6f
    }

private class Ink(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val quiet: Color, val onQuiet: Color, val line: Color,
)

/** Material's expressive shapes as unit paths, made once. */
private class Shapes {
    private val cache = HashMap<RoundedPolygon, Path>()
    private val morphs = HashMap<Pair<RoundedPolygon, RoundedPolygon>, Morph>()
    fun of(p: RoundedPolygon): Path = cache.getOrPut(p) { Morph(p, p).toPath(0f) }
    fun between(a: RoundedPolygon, b: RoundedPolygon, k: Float): Path =
        if (k <= 0f) of(a) else if (k >= 1f) of(b) else morphs.getOrPut(a to b) { Morph(a, b) }.toPath(k)
}

// ---- timing --------------------------------------------------------------------------------

private fun win(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun ease(x: Float) = EmphasizedEasing.transform(x)
/** In over [a]..[b], out over [c]..[d]. */
private fun held(t: Float, a: Float, b: Float, c: Float, d: Float) = ease(win(t, a, b)) * (1 - ease(win(t, c, d)))
/** A spring-like arrival with a little overshoot. */
private fun pop(x: Float): Float {
    if (x <= 0f) return 0f
    val c1 = 1.70158f; val c3 = c1 + 1; val u = x - 1
    return 1 + c3 * u * u * u + c1 * u * u
}
private fun bump(x: Float) = sin(PI.toFloat() * x.coerceIn(0f, 1f))

private class Scene(val d: DrawScope, val t: Float, val ink: Ink, val bud: Painter, val shapes: Shapes, val paint: Paint) {
    val w = d.size.width
    val h = d.size.height
    val dp = d.density

    // ---- pieces ----------------------------------------------------------------------------

    /** The left earbud, its picture [height] tall, centred on [c]. */
    fun earbud(c: Offset, height: Float, alpha: Float = 1f, degrees: Float = 0f, mirror: Boolean = false) {
        val s = bud.intrinsicSize
        val width = height * s.width / s.height
        d.withTransform({
            translate(c.x, c.y)
            rotate(degrees, Offset.Zero)
            if (mirror) scale(-1f, 1f, Offset.Zero)
            translate(-width / 2, -height / 2)
        }) { with(bud) { draw(Size(width, height), alpha) } }
    }

    /** Where the earbud's touch surface is, for a picture [height] tall centred on [c]. */
    fun touchPoint(c: Offset, height: Float): Offset {
        val s = bud.intrinsicSize
        val width = height * s.width / s.height
        return Offset(c.x - width / 2 + width * 0.60f, c.y - height / 2 + height * 0.40f)
    }

    fun shape(p: RoundedPolygon, c: Offset, size: Float, color: Color, degrees: Float = 0f, to: RoundedPolygon? = null, k: Float = 0f, sy: Float = size) {
        if (size <= 0.5f || color.alpha <= 0f) return
        val path = if (to == null) shapes.of(p) else shapes.between(p, to, k)
        d.withTransform({
            translate(c.x - size / 2, c.y - sy / 2)
            scale(size, sy, Offset.Zero)
            rotate(degrees, Offset(0.5f, 0.5f))
        }) { drawPath(path, color) }
    }

    fun glyph(g: Glyph, c: Offset, size: Float, color: Color, fill: Float = 1f) {
        if (size <= 0.5f || color.alpha <= 0f) return
        paint.textSize = size
        paint.color = color.toArgb()
        paint.fontVariationSettings = "'FILL' $fill, 'wght' 500"
        d.drawContext.canvas.nativeCanvas.drawText(g.text, c.x, c.y + size / 2, paint)
    }

    fun pill(c: Offset, width: Float, height: Float, color: Color) {
        d.drawRoundRect(color, Offset(c.x - width / 2, c.y - height / 2), Size(width, height), androidx.compose.ui.geometry.CornerRadius(width / 2))
    }

    /** Music playing: five rounded bars dancing; at [playing] 0 they rest as dots. */
    fun bars(c: Offset, height: Float, playing: Float, color: Color, speed: Float = 1f) {
        val bw = height * 0.16f
        val gap = bw * 0.75f
        for (i in 0 until 5) {
            val x = c.x + (i - 2) * (bw + gap)
            val wave = 0.5f + 0.5f * sin(t * 2 * PI.toFloat() * speed * (0.9f + 0.17f * i) + i * 1.3f)
            val shapeOf = 0.35f + 0.65f * (1 - abs(i - 2) / 3f)
            val bh = bw + (height - bw) * playing * shapeOf * (0.45f + 0.55f * wave)
            pill(Offset(x, c.y), bw, bh, color)
        }
    }

    /** The pause sign on a filled disc. */
    fun pause(c: Offset, size: Float, alpha: Float) {
        if (alpha <= 0f) return
        val s = size * (0.6f + 0.4f * pop(alpha))
        shape(MaterialShapes.Cookie9Sided, c, s, ink.primary.copy(alpha = alpha.coerceAtMost(1f)), degrees = t * 12)
        val bw = s * 0.11f
        pill(Offset(c.x - bw * 1.1f, c.y), bw, s * 0.36f, ink.onPrimary.copy(alpha = alpha.coerceAtMost(1f)))
        pill(Offset(c.x + bw * 1.1f, c.y), bw, s * 0.36f, ink.onPrimary.copy(alpha = alpha.coerceAtMost(1f)))
    }

    /** A symbol on a shape, popping in with [k]. */
    fun badge(g: Glyph, c: Offset, size: Float, k: Float, bg: Color, fg: Color, p: RoundedPolygon = MaterialShapes.Circle, degrees: Float = 0f) {
        if (k <= 0f) return
        val s = size * pop(k)
        val a = k.coerceAtMost(1f)
        shape(p, c, s, bg.copy(alpha = a), degrees)
        glyph(g, c, s * 0.56f, fg.copy(alpha = a))
    }

    // ---- scenes ----------------------------------------------------------------------------

    fun chat() {
        val budAt = Offset(w * 0.74f, h * 0.52f)
        earbud(budAt, h * 0.56f)
        val talk = held(t, 0.8f, 1.25f, 3.2f, 3.6f)
        val playing = 1 - held(t, 1.0f, 1.4f, 3.4f, 3.8f)
        val barsAt = Offset(w * 0.48f, h * 0.52f)
        bars(barsAt, h * 0.38f, playing, ink.secondary)
        pause(barsAt, h * 0.30f, held(t, 1.25f, 1.6f, 3.2f, 3.5f))
        // Someone talks: a bubble with the dots of speech.
        val bubbleAt = Offset(w * 0.22f, h * 0.42f)
        if (talk > 0f) {
            val s = h * 0.46f * pop(win(t, 0.8f, 1.3f)) * (1 - ease(win(t, 3.2f, 3.6f)))
            shape(MaterialShapes.Cookie12Sided, bubbleAt, s, ink.tertiaryContainer, degrees = t * 8)
            for (i in -1..1) {
                val hop = bump(((t * 2.2f) - i * 0.18f) % 1f) * h * 0.035f
                d.drawCircle(ink.onTertiaryContainer.copy(alpha = talk), s * 0.065f, Offset(bubbleAt.x + i * s * 0.2f, bubbleAt.y - hop))
            }
        }
    }

    fun dsee() {
        val left = w * 0.08f
        val right = w * 0.92f
        val reset = ease(win(t, 4.1f, 4.7f))
        val scan = left + (right - left) * win(t, 0.4f, 3.6f).let { it * it * (3 - 2 * it) }
        val amp = h * 0.22f
        val lambda = w * 0.30f
        val mid = h * 0.52f
        fun y(x: Float) = mid + amp * (0.8f * sin(2 * PI.toFloat() * x / lambda + t * 0.8f) + 0.2f * sin(2 * PI.toFloat() * x / (lambda * 0.37f)))
        val step = w * 0.04f
        val level = amp / 2.5f
        // Coarse: as compression leaves it.
        val coarse = Path()
        var x = left
        var started = false
        while (x < right) {
            val qy = mid + Math.round((y(x + step / 2) - mid) / level) * level
            if (!started) { coarse.moveTo(x, qy); started = true } else coarse.lineTo(x, qy)
            coarse.lineTo(x + step, qy)
            x += step
        }
        // Fine: as DSEE gives it back, up to the scanner.
        val fine = Path()
        val end = if (reset > 0f) right else scan
        x = left
        fine.moveTo(x, y(x))
        while (x < end) { x += 2 * dp; fine.lineTo(x.coerceAtMost(end), y(x.coerceAtMost(end))) }
        d.drawPath(coarse, ink.onQuiet.copy(alpha = 0.45f), style = Stroke(3 * dp, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        d.drawPath(fine, ink.primary.copy(alpha = 1 - reset), style = Stroke(5 * dp, cap = StrokeCap.Round))
        if (reset == 0f && t > 0.3f) {
            pill(Offset(scan, mid), 6 * dp, h * 0.74f, ink.primaryContainer)
            shape(MaterialShapes.Sunny, Offset(scan, h * 0.14f), h * 0.18f * (0.85f + 0.15f * sin(t * 6)), ink.tertiary, degrees = t * 90)
        }
    }

    fun sidetone() {
        val budAt = Offset(w * 0.78f, h * 0.54f)
        earbud(budAt, h * 0.54f)
        val voiceAt = Offset(w * 0.22f, h * 0.56f)
        shape(MaterialShapes.Circle, voiceAt, h * 0.48f, ink.secondaryContainer)
        bars(voiceAt, h * 0.24f, 1f, ink.onSecondaryContainer, speed = 1.4f)
        // The voice travels round into the earbud.
        val a = Offset(w * 0.32f, h * 0.34f)
        val c = Offset(w * 0.50f, h * 0.0f)
        val b = Offset(w * 0.68f, h * 0.34f)
        val guide = Path().apply { moveTo(a.x, a.y); quadraticTo(c.x, c.y, b.x, b.y) }
        d.drawPath(guide, ink.line, style = Stroke(2 * dp, cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2 * dp, 6 * dp))))
        for (i in 0 until 5) {
            val p = ((t * 0.55f) + i / 5f) % 1f
            val u = 1 - p
            val pos = Offset(u * u * a.x + 2 * u * p * c.x + p * p * b.x, u * u * a.y + 2 * u * p * c.y + p * p * b.y)
            d.drawCircle(ink.tertiary.copy(alpha = bump(p)), h * (0.03f + 0.015f * bump(p)), pos)
        }
    }

    fun touch() {
        val budAt = Offset(w * 0.44f, h * 0.54f)
        val height = h * 0.70f
        earbud(budAt, height)
        val at = touchPoint(budAt, height)
        for (tap in floatArrayOf(0.7f, 1.0f, 2.7f, 3.0f)) {
            val k = win(t, tap, tap + 0.7f)
            if (k > 0f && k < 1f) d.drawCircle(ink.primary.copy(alpha = (1 - k) * 0.9f), h * 0.06f + h * 0.26f * ease(k), at, style = Stroke(2.5f * dp))
            val press = held(t, tap - 0.12f, tap, tap + 0.1f, tap + 0.3f)
            if (press > 0f) d.drawCircle(ink.primary.copy(alpha = 0.55f * press), h * 0.075f * (0.7f + 0.3f * press), at)
        }
        // The mode the taps switch to.
        val ambient = t in 1.15f..3.15f
        val changed = if (t >= 3.15f) 3.15f else if (t >= 1.15f) 1.15f else -1f
        val k = if (changed < 0) 1f else 0.55f + 0.45f * pop(win(t, changed, changed + 0.45f))
        badge(if (ambient) Glyph.AMBIENT else Glyph.QUIET, Offset(w * 0.78f, h * 0.36f), h * 0.36f, k,
            ink.primaryContainer, ink.onPrimaryContainer, MaterialShapes.Cookie9Sided, degrees = t * 10)
    }

    fun cycle() {
        val glyphs = listOf(Glyph.QUIET, Glyph.AMBIENT, Glyph.OFF)
        val period = LOOP / 3
        val active = (t / period).toInt() % 3
        val previous = (active + 2) % 3
        val k = ease(win(t % period, 0f, 0.5f))
        val xs = listOf(w * 0.25f, w * 0.5f, w * 0.75f)
        val y = h * 0.52f
        // The path the presses take.
        for (i in 0 until 2) for (j in 1..3) {
            val x = xs[i] + (xs[i + 1] - xs[i]) * j / 4f
            d.drawCircle(ink.line, 2.5f * dp, Offset(x, y))
        }
        val from = xs[previous]
        val to = xs[active]
        if (k < 1f && active != 0) d.drawCircle(ink.primary, 4 * dp, Offset(from + (to - from) * k, y))
        glyphs.forEachIndexed { i, g ->
            val a = when (i) { active -> k; previous -> 1 - k; else -> 0f }
            val size = h * 0.40f * (1 + 0.22f * a)
            shape(MaterialShapes.Circle, Offset(xs[i], y), size, lerp(ink.quiet, ink.primary, a), degrees = 40 * a, to = MaterialShapes.Cookie7Sided, k = a)
            glyph(g, Offset(xs[i], y), size * 0.5f, lerp(ink.onQuiet, ink.onPrimary, a), fill = a)
        }
    }

    fun gestures() {
        val nod = win(t, 0.3f, 1.7f)
        val shake = win(t, 2.7f, 4.1f)
        val dy = sin(nod * 4 * PI.toFloat()) * h * 0.07f * bump(nod)
        val dx = sin(shake * 4 * PI.toFloat()) * h * 0.08f * bump(shake)
        val r = h * 0.25f
        val body = Offset(w * 0.40f, h * 0.50f)
        // Shoulders, then the head on them; earbuds at the ears. No face: the movement says it.
        shape(MaterialShapes.Circle, Offset(body.x + dx * 0.25f, h * 1.06f), r * 3.6f, ink.secondaryContainer.copy(alpha = 0.7f), sy = r * 2.2f)
        val head = Offset(body.x + dx, body.y - h * 0.04f + dy)
        shape(MaterialShapes.Circle, head, r * 2, ink.secondaryContainer, sy = r * 2.2f)
        earbud(Offset(head.x - r * 0.98f, head.y + r * 0.08f), h * 0.20f, mirror = true)
        earbud(Offset(head.x + r * 0.98f, head.y + r * 0.08f), h * 0.20f)
        // Motion lines.
        if (nod in 0.05f..0.95f) for (s in listOf(-1, 1)) pill(Offset(body.x + s * r * 1.8f, body.y - h * 0.04f), 3 * dp, h * 0.24f * bump(nod), ink.line)
        if (shake in 0.05f..0.95f) pill(Offset(body.x, body.y - h * 0.04f - r * 1.45f), h * 0.30f * bump(shake), 3 * dp, ink.line)
        val chip = Offset(w * 0.74f, h * 0.36f)
        badge(Glyph.CHECK, chip, h * 0.34f, held(t, 1.4f, 1.8f, 2.4f, 2.7f), ink.primary, ink.onPrimary, MaterialShapes.Cookie9Sided, t * 10)
        badge(Glyph.CLEAR, chip, h * 0.34f, held(t, 3.8f, 4.2f, 4.5f, 4.8f), ink.tertiary, ink.onPrimary, MaterialShapes.Cookie9Sided, t * 10)
    }

    fun autoPause() {
        val lift = held(t, 0.6f, 1.1f, 2.8f, 3.4f)
        val cradle = Offset(w * 0.64f, h * 0.74f)
        shape(MaterialShapes.Circle, cradle, h * 0.66f, ink.secondaryContainer, sy = h * 0.20f)
        earbud(Offset(w * 0.64f, h * 0.54f - lift * h * 0.24f), h * 0.50f, degrees = -14 * lift)
        val barsAt = Offset(w * 0.34f, h * 0.52f)
        val stopped = held(t, 0.9f, 1.25f, 3.2f, 3.55f)
        bars(barsAt, h * 0.38f, 1 - stopped, ink.secondary)
        pause(barsAt, h * 0.30f, held(t, 1.1f, 1.45f, 3.1f, 3.4f))
    }

    fun power() {
        val c = Offset(w * 0.5f, h * 0.52f)
        val r = h * 0.38f
        val off = ease(win(t, 3.0f, 3.4f)) * (1 - ease(win(t, 4.3f, 4.7f)))
        earbud(c, h * 0.44f, alpha = 1 - 0.65f * off)
        val left = 1 - win(t, 0.2f, 3.0f)
        val refill = ease(win(t, 4.3f, 4.8f))
        val sweep = 360f * if (t >= 4.3f) refill else left
        d.drawCircle(ink.line, r, c, style = Stroke(4 * dp))
        if (sweep > 0.5f) d.drawArc(ink.primary, -90f, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(6 * dp, cap = StrokeCap.Round))
        badge(Glyph.POWER, Offset(c.x + r * 0.95f, c.y + r * 0.62f), h * 0.30f, off, ink.tertiaryContainer, ink.onTertiaryContainer, MaterialShapes.Cookie9Sided)
        badge(Glyph.TIMER, Offset(c.x - r * 0.95f, c.y + r * 0.62f), h * 0.26f, 1 - off, ink.quiet, ink.onQuiet)
    }

    fun quality() {
        val phone = Offset(w * 0.18f, h * 0.54f)
        val pw = h * 0.30f
        val ph = h * 0.56f
        d.drawRoundRect(ink.secondaryContainer, Offset(phone.x - pw / 2, phone.y - ph / 2), Size(pw, ph), androidx.compose.ui.geometry.CornerRadius(pw * 0.28f))
        pill(Offset(phone.x, phone.y - ph * 0.40f), pw * 0.3f, 3 * dp, ink.onSecondaryContainer.copy(alpha = 0.5f))
        val budAt = Offset(w * 0.82f, h * 0.54f)
        earbud(budAt, h * 0.48f)
        val steady = ease(win(t, 2.2f, 2.6f)) * (1 - ease(win(t, 4.5f, 4.8f)))
        val x0 = w * 0.27f
        val x1 = w * 0.70f
        val y = h * 0.56f
        for (i in 0 until 9) {
            val p = ((t * 0.9f) + i / 9f) % 1f
            val wobble = sin(p * 5 * PI.toFloat() + i) * h * 0.06f
            d.drawCircle(ink.primary.copy(alpha = (1 - steady) * bump(p)), h * 0.045f, Offset(x0 + (x1 - x0) * p, y + wobble))
        }
        for (i in 0 until 5) {
            val p = ((t * 0.45f) + i / 5f) % 1f
            d.drawCircle(ink.tertiary.copy(alpha = steady * bump(p)), h * 0.035f, Offset(x0 + (x1 - x0) * p, y))
        }
        val chip = Offset(w * 0.475f, h * 0.20f)
        badge(Glyph.EQ, chip, h * 0.28f, 1 - steady, ink.primaryContainer, ink.onPrimaryContainer)
        badge(Glyph.LINK, chip, h * 0.28f, steady, ink.tertiaryContainer, ink.onTertiaryContainer)
    }

    fun guidance() {
        val budAt = Offset(w * 0.76f, h * 0.56f)
        earbud(budAt, h * 0.52f)
        val from = Offset(w * 0.64f, h * 0.52f)
        for (i in 0 until 3) {
            val r = h * (0.12f + 0.09f * i)
            val a = 0.25f + 0.75f * bump(((t * 1.4f) - i * 0.22f) % 1f)
            d.drawArc(ink.primary.copy(alpha = a), 150f, 60f, false, Offset(from.x - r, from.y - r), Size(2 * r, 2 * r), style = Stroke(4 * dp, cap = StrokeCap.Round))
        }
        val bubble = Offset(w * 0.34f, h * 0.44f)
        val shown = held(t, 0.4f, 0.8f, 4.3f, 4.7f)
        val battery = t < 2.5f
        val k = if (battery) shown else 0.6f + 0.4f * pop(win(t, 2.5f, 2.9f)) * shown
        badge(if (battery) Glyph.BATTERY else Glyph.QUIET, bubble, h * 0.44f, k, ink.secondaryContainer, ink.onSecondaryContainer, MaterialShapes.Cookie12Sided, t * 8)
    }
}

/** Which illustration explains a setting. */
fun AdvancedSettings.Key.topic(): Topic = when (this) {
    AdvancedSettings.Key.CHAT, AdvancedSettings.Key.CHAT_CONFIG -> Topic.CHAT
    AdvancedSettings.Key.SIDETONE -> Topic.SIDETONE
    AdvancedSettings.Key.TOUCH -> Topic.TOUCH
    AdvancedSettings.Key.CYCLE -> Topic.CYCLE
    AdvancedSettings.Key.GESTURES -> Topic.GESTURES
    AdvancedSettings.Key.POWER -> Topic.POWER
    AdvancedSettings.Key.QUALITY -> Topic.QUALITY
    AdvancedSettings.Key.GUIDANCE, AdvancedSettings.Key.GUIDANCE_VOLUME -> Topic.GUIDANCE
}

fun DeviceSettingsProtocol.Feature.topic(): Topic = when (this) {
    DeviceSettingsProtocol.Feature.DSEE -> Topic.DSEE
    DeviceSettingsProtocol.Feature.AUTO_PAUSE -> Topic.AUTO_PAUSE
}
