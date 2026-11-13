package ua.olex.sonyanc

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The opening, once per cold start (1.55 s, a tap skips it). The splash shows the case artwork
 * (drawable-nodpi/art_*, one illustrated parts sheet: closed case, open body, the lid's inside,
 * both earbuds); as the splash lifts, the same case, in the same place, comes alive: a breath and
 * a spring up off its shadow with a glow behind; the lid swings back on its hinge (its inside rises
 * behind the opening, its outside narrows to a band on top); the earbuds rise out of their wells,
 * tilting, and hover over the opening; the status light blooms. Then the earbuds fly on arcs into
 * their battery rings, the lid shuts and the case follows; the rings show the same artwork and
 * each fills as its piece lands, and the rest of the page comes in. Off (the default) elsewhere.
 */
object Intro {
    enum class Phase { OFF, WAITING, PLAYING }

    var phase by mutableStateOf(Phase.OFF)
        private set
    /** Where each ring's picture sits ("Left", "Case", "Right"), in root coordinates. */
    val targets = mutableStateMapOf<String, Rect>()
    private val landed = mutableStateMapOf<String, Boolean>()
    /** The page's own opacity while the intro plays. */
    var content by mutableFloatStateOf(1f)
        private set
    /** The case as the splash drew it (lid top to body bottom), in window (= root) coordinates. */
    internal var from: Rect? = null

    fun isLanded(label: String) = phase == Phase.OFF || landed[label] == true

    /** A fresh start: the page waits under the splash. Never waits more than 2 s. */
    fun prepare() {
        phase = Phase.WAITING; content = 0f; landed.clear()
        Handler(Looper.getMainLooper()).postDelayed({ if (phase == Phase.WAITING) finish() }, 2000)
    }

    fun start(caseOnSplash: Rect?) {
        if (phase != Phase.WAITING) return
        from = caseOnSplash
        phase = Phase.PLAYING
    }

    fun finish() {
        listOf("Left", "Case", "Right").forEach { landed[it] = true }
        content = 1f
        phase = Phase.OFF
    }

    internal fun land(label: String) { landed[label] = true }
    internal fun reveal(alpha: Float) { content = alpha }
}

// ---- motion helpers ------------------------------------------------------------------------------

private const val TOTAL = 1550f

private fun span(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun decel(x: Float): Float { val u = 1 - x; return 1 - u * u * u * u }
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/** A damped spring from 0 to 1 over x in 0..1: overshoots by about [bounce], settled by 1. */
private fun spring(x: Float, bounce: Float = 0.35f): Float {
    if (x >= 1f) return 1f
    if (x <= 0f) return 0f
    val zeta = (1f - bounce).coerceIn(0.2f, 0.95f)
    val w = 6f / zeta
    val wd = w * sqrt(1 - zeta * zeta)
    return 1 - exp(-zeta * w * x) * (cos(wd * x) + zeta * w / wd * sin(wd * x))
}

// ---- the overlay -------------------------------------------------------------------------------

// Geometry of the artwork, as fractions of the closed case (562 x 387 px, all parts at one scale).
private const val SEAM = 0.468f        // lid / body line on the closed case
private const val BODY_TOP = 0.233f    // the open body's back rim, where the lid is hinged
private const val FRONT_RIM = 0.47f    // the opening's front rim
private const val WELL_Y = 0.362f      // the wells' centres
private const val WELL_X = 0.276f      // ... at 0.5 -/+ this
private const val LED_X = 0.5f
private const val LED_Y = 0.556f

@Composable
fun IntroOverlay() {
    if (Intro.phase != Intro.Phase.PLAYING) return
    val glowColor = MaterialTheme.colorScheme.primary
    val closed = ImageBitmap.imageResource(R.drawable.art_case_closed)
    val bodyArt = ImageBitmap.imageResource(R.drawable.art_case_body)
    val lidArt = ImageBitmap.imageResource(R.drawable.art_lid_inside)
    val budL = ImageBitmap.imageResource(R.drawable.art_bud_left)
    val budR = ImageBitmap.imageResource(R.drawable.art_bud_right)
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Without the rings on screen (setup, a page) there is nothing to land in.
        if (Intro.targets.size < 3) { Intro.finish(); return@LaunchedEffect }
        clock.animateTo(TOTAL, tween(TOTAL.toInt(), easing = LinearEasing)) {
            if (value >= 1290f) Intro.land("Left")
            if (value >= 1350f) Intro.land("Right")
            if (value >= 1470f) Intro.land("Case")
            Intro.reveal(decel(span(value, 1200f, TOTAL)))
        }
        Intro.finish()
    }
    Canvas(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { Intro.finish() }) {
        // No rings to land in (setup, permission, another page): draw nothing; the effect ends it.
        if (Intro.targets.size < 3) return@Canvas
        val t = clock.value
        val dp = density
        val aspect = closed.width.toFloat() / closed.height
        val start = Intro.from ?: Rect(Offset(size.width / 2, size.height * 0.45f), 0f).let {
            Rect(it.center.x - 100 * dp, it.center.y - 100 / aspect * dp, it.center.x + 100 * dp, it.center.y + 100 / aspect * dp)
        }
        val w0 = start.width

        // ---- the timeline ----
        val breath = sin(span(t, 0f, 260f) * PI.toFloat()) * 0.025f
        val rise = spring(span(t, 120f, 680f), 0.3f)
        // The lid shuts once the earbuds are on their way, then the case follows them.
        val open = spring(span(t, 360f, 900f), 0.2f) * (1 - decel(span(t, 1080f, 1250f)))
        val ledGlow = decel(span(t, 620f, 820f)) * (1 - span(t, 1100f, 1300f))
        val fly = span(t, 1190f, 1500f)

        val stage = Offset(start.center.x, start.center.y - rise * 26 * dp)
        val casePiece = fly(Piece(stage, w0 * (1 + breath + 0.05f * rise), 0f, 1f), Intro.targets.getValue("Case"), aspect, fly)

        // Earbuds: out of their wells, tilting outward, a gentle float, then into their rings.
        val caseH = w0 / aspect
        fun bud(side: Int, label: String, delay: Float, art: ImageBitmap): Pair<Piece, Float> {
            val up = spring(span(t, 560f + delay, 1060f + delay), 0.38f)
            val float = sin(span(t, 900f, 1400f) * PI.toFloat() * 2) * 3 * dp
            val caseTop = stage.y - caseH / 2
            val well = Offset(stage.x + side * w0 * WELL_X, caseTop + caseH * WELL_Y + w0 * 0.12f)
            val perch = Offset(stage.x + side * w0 * 0.25f, caseTop - w0 * 0.02f + float)
            val staged = Piece(lerp(well, perch, up), w0 * 0.36f, lerp(0f, side * 10f, up), if (up <= 0.001f) 0f else 1f)
            val p = span(t, 1000f + delay * 0.6f, 1300f + delay * 0.6f)
            return fly(staged, Intro.targets.getValue(label), art.width.toFloat() / art.height, p) to up
        }
        val (left, upL) = bud(-1, "Left", 0f, budL)
        val (right, upR) = bud(1, "Right", 70f, budR)

        // A glow of the theme colour behind, breathing out as the case comes to life.
        val g = span(t, 0f, 900f)
        val glowR = w0 * lerp(0.5f, 1.3f, decel(g))
        drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.30f * (1 - g)), Color.Transparent), stage, glowR), glowR, stage)
        // Contact shadow on the "floor" the case rose from.
        val floor = Offset(start.center.x, start.bottom + 4 * dp)
        val sh = 1 - fly
        drawOval(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.42f * sh * (1 - 0.45f * rise)), Color.Transparent), floor, w0 * 0.55f),
            topLeft = floor - Offset(w0 * (0.48f + 0.08f * rise), w0 * 0.06f), size = Size(w0 * (0.96f + 0.16f * rise), w0 * 0.12f),
        )

        val budsAway = t >= 1000f
        drawCase(casePiece, open * (1 - fly), ledGlow, closed, bodyArt, lidArt) { frontRimY ->
            // Out of the opening: hidden below its front rim until they rise.
            if (!budsAway) clipRect(bottom = frontRimY) { drawBud(left, budL); drawBud(right, budR) }
        }
        if (budsAway) { drawBud(left, budL); drawBud(right, budR) }
    }
}

/** One piece of the picture: centre, width, rotation, opacity. */
private data class Piece(val center: Offset, val width: Float, val rotation: Float, val alpha: Float)

/** From the stage into its ring on an arc, shrinking to the ring's picture and handing over to it. */
private fun fly(staged: Piece, ring: Rect, aspect: Float, p: Float): Piece {
    if (p <= 0f) return staged
    val e = EmphasizedEasing.transform(p)
    val fitW = minOf(ring.width, ring.height * aspect)
    val arc = Offset(0f, -sin(e * PI.toFloat()) * staged.width * 0.25f)
    // The ring shows the same artwork: the flight hands over to it in the last stretch.
    return Piece(lerp(staged.center, ring.center, e) + arc, lerp(staged.width, fitW, e), lerp(staged.rotation, 0f, e), staged.alpha * (1 - span(p, 0.75f, 1f)))
}

private fun DrawScope.image(img: ImageBitmap, dst: Rect, alpha: Float, src: IntRect = IntRect(0, 0, img.width, img.height)) {
    if (alpha <= 0f || dst.width <= 0f || dst.height <= 0f) return
    drawImage(img, IntOffset(src.left, src.top), IntSize(src.width, src.height),
        IntOffset(dst.left.roundToInt(), dst.top.roundToInt()), IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
        alpha = alpha, filterQuality = FilterQuality.High)
}

/**
 * The case. Closed it is the closed artwork itself. Opening, it is three parts: the lid's inside
 * rising from the hinge behind the opening, the open body, and the lid's outside (the closed
 * artwork above the seam) narrowing to a band on top of the inside. [inside] draws what comes
 * out of the opening, given the opening's front rim.
 */
private fun DrawScope.drawCase(
    piece: Piece, open: Float, ledGlow: Float, closed: ImageBitmap, bodyArt: ImageBitmap, lidArt: ImageBitmap,
    inside: DrawScope.(frontRimY: Float) -> Unit,
) {
    if (piece.alpha <= 0f) return
    val w = piece.width
    val h = w * closed.height / closed.width
    val left = piece.center.x - w / 2
    val top = piece.center.y - h / 2
    val a = piece.alpha
    val frontRim = top + h * FRONT_RIM
    if (open <= 0.004f) {
        image(closed, Rect(left, top, left + w, top + h), a)
        inside(frontRim)
    } else {
        val o = open.coerceIn(0f, 1.15f)
        val hinge = top + h * BODY_TOP
        // The lid's inside, standing up from the hinge.
        val lidH = w * lidArt.height / lidArt.width * o
        image(lidArt, Rect(left, hinge - lidH + h * 0.015f, left + w, hinge + h * 0.015f), a)
        // The open body.
        val bodyH = w * bodyArt.height / bodyArt.width
        image(bodyArt, Rect(left, top + h - bodyH, left + w, top + h), a)
        // The lid's outside: the closed artwork above the seam, narrowing to a band on top.
        val seamPx = (closed.height * SEAM).roundToInt()
        val bandH = lerp(h * SEAM, h * 0.07f, o.coerceIn(0f, 1f))
        val bandBottom = lerp(top + h * SEAM, hinge - lidH + h * 0.03f, o.coerceIn(0f, 1f))
        image(closed, Rect(left, bandBottom - bandH, left + w, bandBottom), a, IntRect(0, 0, closed.width, seamPx))
        inside(frontRim)
    }
    // Status light blooming.
    if (ledGlow > 0.01f) {
        val led = Offset(left + w * LED_X, top + h * LED_Y)
        val r = w * 0.06f * (1 + ledGlow)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF34A853).copy(alpha = 0.6f * ledGlow * a), Color.Transparent), led, r), r, led)
    }
}

private fun DrawScope.drawBud(piece: Piece, art: ImageBitmap) {
    if (piece.alpha <= 0f || piece.width <= 0f) return
    val w = piece.width
    val h = w * art.height / art.width
    withTransform({ rotate(piece.rotation, piece.center) }) {
        image(art, Rect(piece.center.x - w / 2, piece.center.y - h / 2, piece.center.x + w / 2, piece.center.y + h / 2), piece.alpha)
    }
}
