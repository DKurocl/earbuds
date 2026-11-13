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
 * The opening, once per cold start (1.55 s, a tap skips it). The splash shows the launcher's case;
 * as it lifts, the same case, at the same place, comes to life as an illustration in the style of
 * Pixel's product art: the light comes on (volume, highlights, rim light, the seam's shadow), it
 * springs up off its shadow, the lid opens in true perspective onto the inside with its two wells,
 * the earbuds rise out on springs, tilting, pearl-glossy, and the status light glows. Then the lid
 * shuts and the three fly on arcs into their battery rings, each ring filling as its piece lands,
 * and the rest of the page comes in. Drawn in code, on one Canvas, from the icon's own geometry
 * and colours, so it starts exactly as the splash ends. Off (the default) everywhere else.
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

@Composable
fun IntroOverlay() {
    if (Intro.phase != Intro.Phase.PLAYING) return
    val glowColor = MaterialTheme.colorScheme.primary
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Without the rings on screen (setup, a page) there is nothing to land in.
        if (Intro.targets.size < 3) { Intro.finish(); return@LaunchedEffect }
        clock.animateTo(TOTAL, tween(TOTAL.toInt(), easing = LinearEasing)) {
            if (value >= 1330f) Intro.land("Left")
            if (value >= 1380f) Intro.land("Case")
            if (value >= 1420f) Intro.land("Right")
            Intro.reveal(decel(span(value, 1250f, TOTAL)))
        }
        Intro.finish()
    }
    Canvas(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { Intro.finish() }) {
        val t = clock.value
        val dp = density
        val start = Intro.from ?: Rect(Offset(size.width / 2, size.height * 0.45f), 0f).let {
            Rect(it.center.x - 100 * dp, it.center.y - 71 * dp, it.center.x + 100 * dp, it.center.y + 71 * dp)
        }
        val w0 = start.width

        // ---- the timeline ----
        val life = decel(span(t, 0f, 280f))                         // the light comes on
        val breath = sin(span(t, 0f, 280f) * PI.toFloat()) * 0.025f    // a small breath as it does
        val rise = spring(span(t, 140f, 700f), 0.3f)                  // up off its shadow
        val open = spring(span(t, 360f, 900f), 0.22f) * (1 - decel(span(t, 1080f, 1260f)))
        val ledGlow = decel(span(t, 620f, 820f)) * (1 - span(t, 1100f, 1300f))
        val fly = span(t, 1120f, 1480f)

        val caseCenter = Offset(start.center.x, start.center.y - rise * 26 * dp)
        val casePiece = fly(Piece(caseCenter, w0 * (1 + breath + 0.06f * rise), 0f, 1f), Intro.targets.getValue("Case"), 480f / 309f, fly)

        // Earbuds: out of their wells, tilting outward, a gentle float, then into their rings.
        fun bud(side: Int, label: String, delay: Float): Piece {
            val up = spring(span(t, 560f + delay, 1060f + delay), 0.38f)
            val float = sin(span(t, 900f, 1400f) * PI.toFloat() * 2) * 3 * dp
            val well = caseCenter + Offset(side * w0 * 0.2f, -w0 * 0.12f)
            // They hover over the opening, against the dark inside of the lid.
            val perch = caseCenter + Offset(side * w0 * 0.23f, -w0 * 0.4f + float)
            val staged = Piece(lerp(well, perch, up), w0 * lerp(0.26f, 0.36f, up), lerp(0f, side * 12f, up), 1f)
            val p = span(t, 1120f + delay * 0.6f, 1460f + delay * 0.6f)
            return fly(staged, Intro.targets.getValue(label), 1f, p).let { if (up <= 0.001f) it.copy(alpha = 0f) else it }
        }
        val left = bud(-1, "Left", 0f)
        val right = bud(1, "Right", 70f)

        // A glow of the theme colour behind, breathing out as the case comes to life.
        val g = span(t, 0f, 900f)
        val glowR = w0 * lerp(0.5f, 1.4f, decel(g))
        drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.30f * (1 - g)), Color.Transparent), caseCenter, glowR), glowR, caseCenter)

        // Contact shadow on the "floor" the case rose from.
        val floor = Offset(start.center.x, start.bottom + 6 * dp)
        val sh = (1 - fly) * life
        drawOval(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.38f * sh * (1 - 0.45f * rise)), Color.Transparent), floor, w0 * 0.55f),
            topLeft = floor - Offset(w0 * (0.5f + 0.08f * rise), w0 * 0.07f), size = Size(w0 * (1f + 0.16f * rise), w0 * 0.14f),
        )

        drawCase(casePiece, life, open * (1 - fly), ledGlow, budsBehind = { if (fly < 0.01f) { drawBud(left, false); drawBud(right, true) } })
        if (fly >= 0.01f) { drawBud(left, false); drawBud(right, true) }
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
    // The ring's own picture fades in as it lands; the drawing fades out over the last stretch.
    return Piece(lerp(staged.center, ring.center, e) + arc, lerp(staged.width, fitW, e), lerp(staged.rotation, 0f, e), 1 - span(p, 0.7f, 1f))
}

// ---- the case ------------------------------------------------------------------------------------

private val LID_LIGHT = Color(0xFFC3D3FB)
private val LID_DEEP = Color(0xFF8AB4F8)
private val BODY_A = Color(0xFF2F7BF5)
private val BODY_B = Color(0xFF4A86F4)
private val BODY_C = Color(0xFFA497F2)
private val INSIDE_TOP = Color(0xFF16336E)
private val INSIDE_DEEP = Color(0xFF081634)
private val LED = Color(0xFF34A853)

/**
 * The case, in the launcher icon's proportions (48 x 34 units: lid 13.5, seam 1.6, body 18.9),
 * lit like product art. [life] 0 is the flat icon, 1 the lit illustration; [open] 0..1 swings the
 * lid back on its hinge; [budsBehind] draws what comes out of the wells, in front of the opening
 * and behind the case's front.
 */
private fun DrawScope.drawCase(
    piece: Piece, life: Float, open: Float, ledGlow: Float, budsBehind: DrawScope.() -> Unit,
) {
    if (piece.alpha <= 0f) return
    val w = piece.width
    val u = w / 48f
    val left = piece.center.x - w / 2
    val top = piece.center.y - 17 * u
    val seam = top + 13.5f * u
    val bodyTop = seam + 1.6f * u
    val bottom = top + 34 * u
    val a = piece.alpha

    // Opening, the lid swings back on its hinge at the back of the case: its inside rises as a
    // panel standing up behind the opening, and its outside front narrows to a band along the
    // panel's top edge. The opening itself (the body's top face, two wells) shows in front of
    // the panel's foot, seen a little from above.
    val o = open.coerceIn(0f, 1.1f)
    val depth = 24 * u * o
    val inset = 1.2f * u * o
    if (o > 0.01f) {
        val panel = RoundRect(Rect(left + inset, seam - depth, left + w - inset, seam + 0.5f * u),
            topLeft = CornerRadius(9 * u), topRight = CornerRadius(9 * u))
        val panelPath = Path().apply { addRoundRect(panel) }
        drawPath(panelPath, Brush.verticalGradient(listOf(Color(0xFF2E5AAE), Color(0xFF1B3B7E), INSIDE_DEEP), panel.top, panel.bottom), alpha = a)
        // Its inner rim and a soft light on the upper inside.
        drawPath(panelPath, Color.White.copy(alpha = 0.16f * a), style = Stroke(0.7f * u))
        clipPath(panelPath) {
            drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), Offset(left + w * 0.35f, seam - depth * 0.7f), w * 0.4f),
                topLeft = Offset(left, seam - depth), size = Size(w * 0.8f, depth * 0.7f), alpha = a)
        }
    }
    // The outside of the lid: full height closed, a narrow band on top of the panel when open.
    val band = lerp(13.5f * u, 4f * u, o.coerceIn(0f, 1f))
    val lidBottom = seam - depth
    val lidRect = Rect(left + inset, lidBottom - band, left + w - inset, lidBottom)
    val lid = Path().apply {
        val r = minOf(11 * u, band)
        addRoundRect(RoundRect(lidRect, topLeft = CornerRadius(r), topRight = CornerRadius(r),
            bottomLeft = CornerRadius(2 * u * o), bottomRight = CornerRadius(2 * u * o)))
    }
    drawPath(lid, Brush.linearGradient(listOf(LID_DEEP, LID_LIGHT), Offset(lidRect.right, lidRect.top), Offset(lidRect.left, lidRect.bottom)), alpha = a)
    drawPath(lid, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f * life), Color.Transparent), lidRect.top, lidRect.top + 6 * u), alpha = a)
    drawPath(lid, Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF1A3A80).copy(alpha = 0.28f * life * (1 - o.coerceIn(0f, 1f)))), lidRect.bottom - 4 * u, lidRect.bottom), alpha = a)
    drawRim(lid, left, w, life * a)

    if (o > 0.01f) {
        val faceH = 7 * u * o.coerceIn(0f, 1f)
        val face = RoundRect(Rect(left + 1.2f * u, bodyTop - faceH, left + w - 1.2f * u, bodyTop + 0.8f * u), CornerRadius(5 * u, 3 * u))
        drawPath(Path().apply { addRoundRect(face) }, Brush.verticalGradient(listOf(INSIDE_TOP, Color(0xFF24488F)), face.top, face.bottom), alpha = a)
        for (side in listOf(-1, 1)) {
            val cx = piece.center.x + side * 10 * u
            val well = Rect(cx - 7.5f * u, bodyTop - faceH * 0.8f, cx + 7.5f * u, bodyTop - faceH * 0.1f)
            drawOval(Brush.radialGradient(listOf(Color.Black, INSIDE_DEEP), well.center, well.width / 2), well.topLeft, well.size, alpha = a)
            drawOval(Color.White.copy(alpha = 0.12f * a), well.topLeft, well.size, style = Stroke(0.5f * u))
        }
        drawLine(Color.White.copy(alpha = 0.35f * a), Offset(left + 3 * u, bodyTop + 0.3f * u), Offset(left + w - 3 * u, bodyTop + 0.3f * u), strokeWidth = 0.6f * u)
    }
    budsBehind()

    // The body.
    val body = Path().apply {
        addRoundRect(RoundRect(Rect(left, bodyTop, left + w, bottom), bottomLeft = CornerRadius(12.5f * u), bottomRight = CornerRadius(12.5f * u)))
    }
    drawPath(body, Brush.linearGradient(listOf(BODY_A, BODY_B, BODY_C), Offset(left + w, bodyTop), Offset(left, bottom)), alpha = a)
    // Roundness: lighter under the seam, darker towards the floor; a specular band; rim light.
    drawPath(body, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f * life), Color.Transparent), bodyTop, bodyTop + 7 * u), alpha = a)
    drawPath(body, Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0B1F5C).copy(alpha = 0.35f * life)), bodyTop + 8 * u, bottom), alpha = a)
    clipPath(body) {
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f * life), Color.Transparent), Offset(left + w * 0.3f, bodyTop + 4 * u), w * 0.35f),
            topLeft = Offset(left + w * 0.02f, bodyTop + 1 * u), size = Size(w * 0.56f, 7 * u), alpha = a,
        )
    }
    drawRim(body, left, w, life * a)
    // The seam's shadow just under the lid.
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1F5C).copy(alpha = 0.45f * life), Color.Transparent), bodyTop, bodyTop + 1.6f * u),
        Offset(left + 1 * u, bodyTop), Size(w - 2 * u, 1.6f * u), alpha = a)

    // Status light: the icon's green dot, blooming when the earbuds come out.
    val led = Offset(piece.center.x, top + 23.5f * u)
    if (ledGlow > 0.01f) {
        val r = 2.6f * u * (2.5f + 2.5f * ledGlow)
        drawCircle(Brush.radialGradient(listOf(LED.copy(alpha = 0.55f * ledGlow * a), Color.Transparent), led, r), r, led)
    }
    drawCircle(LED, 2.6f * u, led, alpha = a)
    drawCircle(Color.White.copy(alpha = (0.25f + 0.45f * ledGlow) * life * a), 0.9f * u, led - Offset(0.7f * u, 0.7f * u))
}

/** Rim light: a thin bright edge on both sides, as on a lit, rounded object. */
private fun DrawScope.drawRim(shape: Path, left: Float, w: Float, alpha: Float) {
    if (alpha <= 0f) return
    clipPath(shape) {
        drawRect(
            Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.18f)), left, left + w),
            Offset(left, 0f), Size(w, size.height), alpha = alpha,
        )
    }
}

// ---- the earbuds -----------------------------------------------------------------------------------

private val PEARL_HI = Color(0xFFFBFCFD)
private val PEARL = Color(0xFFE3E6EA)
private val PEARL_LO = Color(0xFFB4BAC3)
private val GOLD_HI = Color(0xFFF1DDAA)
private val GOLD_LO = Color(0xFFA9864A)

/**
 * One earbud as product art, seen from the front, a little turned: a pearl bean of a shell with
 * its lighter touch surface, the silicone ear tip on the inner side, and the small mic in its gold
 * ring off-centre, lit from the top left with a glossy highlight.
 */
private fun DrawScope.drawBud(piece: Piece, mirrored: Boolean) {
    if (piece.alpha <= 0f || piece.width <= 0f) return
    val s = piece.width
    val c = piece.center
    val a = piece.alpha
    fun p(x: Float, y: Float) = Offset(c.x + x * s, c.y + y * s)
    withTransform({
        rotate(piece.rotation, c)
        if (mirrored) scale(-1f, 1f, c)
    }) {
        // The ear tip: a soft grey dome on the inner side, behind the shell.
        val tipC = p(-0.34f, 0.24f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB9BEC6), Color(0xFF7D838C), Color(0xFF5A5F67)), tipC - Offset(s * 0.06f, s * 0.07f), s * 0.22f), s * 0.18f, tipC, alpha = a)
        drawCircle(Color.White.copy(alpha = 0.35f * a), s * 0.05f, tipC - Offset(s * 0.07f, s * 0.07f))
        // The shell: a rounded bean, taller than wide, fuller at the top right.
        val shell = Path().apply {
            moveTo(p(0.02f, -0.46f).x, p(0.02f, -0.46f).y)
            cubicTo(p(0.28f, -0.47f).x, p(0.28f, -0.47f).y, p(0.44f, -0.26f).x, p(0.44f, -0.26f).y, p(0.42f, -0.02f).x, p(0.42f, -0.02f).y)
            cubicTo(p(0.40f, 0.24f).x, p(0.40f, 0.24f).y, p(0.22f, 0.44f).x, p(0.22f, 0.44f).y, p(-0.02f, 0.44f).x, p(-0.02f, 0.44f).y)
            cubicTo(p(-0.24f, 0.44f).x, p(-0.24f, 0.44f).y, p(-0.38f, 0.28f).x, p(-0.38f, 0.28f).y, p(-0.38f, 0.04f).x, p(-0.38f, 0.04f).y)
            cubicTo(p(-0.38f, -0.24f).x, p(-0.38f, -0.24f).y, p(-0.24f, -0.45f).x, p(-0.24f, -0.45f).y, p(0.02f, -0.46f).x, p(0.02f, -0.46f).y)
            close()
        }
        drawPath(shell, Brush.radialGradient(listOf(PEARL_HI, PEARL, PEARL_LO), p(-0.12f, -0.2f), s * 0.7f), alpha = a)
        clipPath(shell) {
            // The touch surface: a lighter, flatter face on the outer side, with a fine edge.
            val face = Rect(p(-0.06f, -0.42f), p(0.46f, 0.22f))
            drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), PEARL.copy(alpha = 0.6f)), face.center - Offset(s * 0.08f, s * 0.1f), s * 0.36f),
                face.topLeft, face.size, alpha = a)
            // Shade towards the lower edge, so it reads round.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF6E7681).copy(alpha = 0.35f)), c.y + s * 0.1f, c.y + s * 0.46f),
                p(-0.5f, 0.1f), Size(s, s * 0.4f), alpha = a)
        }
        drawPath(shell, Color.White.copy(alpha = 0.4f * a), style = Stroke(s * 0.012f))
        // The mic in its gold ring, low on the touch surface.
        val mic = p(0.27f, 0.2f)
        drawCircle(Brush.linearGradient(listOf(GOLD_HI, GOLD_LO), mic - Offset(s * 0.05f, s * 0.05f), mic + Offset(s * 0.05f, s * 0.05f)), s * 0.05f, mic,
            alpha = a, style = Stroke(s * 0.022f))
        // A glossy highlight, top left.
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * a), Color.White.copy(alpha = 0f)), p(-0.16f, -0.28f), s * 0.16f),
            topLeft = p(-0.31f, -0.38f), size = Size(s * 0.3f, s * 0.2f),
        )
    }
}
