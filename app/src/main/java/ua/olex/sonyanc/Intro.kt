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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The opening, once per cold start: the splash shows the real case (drawable/splash_case.xml);
 * as it lifts, the case comes alive where it stood (a spring up with its soft shadow, a glow
 * behind, light running across it), both earbuds pop out from behind it, tilting, and the
 * three fly into their battery rings on Home, each ring filling as its piece lands. The rest of
 * the page fades in last. A tap anywhere skips to the end. 1.2 s in all.
 *
 * Drawn on one Canvas over the page from the product photos Home itself uses, so the pieces
 * land as exactly the pictures in the rings. Off (the default) everywhere else, renders included.
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
    /** Where the splash showed the case, in window (= root) coordinates. */
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

// ---- timing helpers (ms on a 1200 ms timeline) -------------------------------------------------

private fun span(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

/** Material's emphasized decelerate, as a cubic approximation. */
private fun decel(x: Float): Float { val u = 1 - x; return 1 - u * u * u * u }

/** Material emphasized (in-out) for travel. */
private fun emphasized(x: Float) = EmphasizedEasing.transform(x)

/** A damped spring from 0 to 1 over x in 0..1: overshoots by about [bounce] at most, settled by 1. */
private fun spring(x: Float, bounce: Float = 0.35f): Float {
    if (x >= 1f) return 1f
    val zeta = (1f - bounce).coerceIn(0.2f, 0.95f)
    val w = 6f / zeta // envelope e^(-zeta w x) is ~0.25 % at x = 1
    val wd = w * kotlin.math.sqrt(1 - zeta * zeta)
    return 1 - exp(-zeta * w * x) * (cos(wd * x) + zeta * w / wd * kotlin.math.sin(wd * x))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/** One piece of the picture: where it is, how big (width), turned how far, how visible. */
private class Piece(val center: Offset, val width: Float, val rotation: Float, val alpha: Float, val shadow: Float)

@Composable
fun IntroOverlay() {
    if (Intro.phase != Intro.Phase.PLAYING) return
    val case = ImageBitmap.imageResource(R.drawable.wf1000xm5_case)
    val bud = ImageBitmap.imageResource(R.drawable.wf1000xm5_bud)
    val glow = MaterialTheme.colorScheme.primary
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Without the rings on screen (setup, a page) there is nothing to land in.
        if (Intro.targets.size < 3) { Intro.finish(); return@LaunchedEffect }
        clock.animateTo(TOTAL, tween(TOTAL.roundToInt(), easing = LinearEasing)) {
            val t = value
            if (t >= 1010f) Intro.land("Left")
            if (t >= 1050f) Intro.land("Case")
            if (t >= 1090f) Intro.land("Right")
            Intro.reveal(decel(span(t, 900f, 1200f)))
        }
        Intro.finish()
    }
    Canvas(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { Intro.finish() }) {
        val t = clock.value
        val dp = density
        // Where the splash left the case (or the screen's centre, a little high).
        val start = Intro.from ?: Rect(Offset(size.width / 2, size.height * 0.45f), 0f).inflate(100 * dp)
        val stage = start.center
        val caseW = start.width
        val ringOf = { label: String -> Intro.targets.getValue(label) }

        // ---- the case: alive in place, then into its ring ----
        // Picked up exactly where the splash drew it: a small dip (anticipation), then a spring up.
        val dip = kotlin.math.sin(span(t, 0f, 140f) * PI.toFloat())
        val rise = spring(span(t, 110f, 640f), 0.3f)
        val caseStaged = Piece(
            stage + Offset(0f, dip * 5 * dp - rise * 22 * dp), caseW * (1 - 0.035f * dip + 0.07f * rise), 0f, 1f, 0.6f + 0.4f * rise,
        )
        val casePiece = fly(caseStaged, ringOf("Case"), case, span(t, 740f, 1100f))

        // ---- the earbuds: out from behind the case, then into their rings ----
        fun budPiece(side: Int, label: String, flyStart: Float): Piece {
            val out = spring(span(t, 300f + (if (side > 0) 50f else 0f), 760f), 0.4f)
            val home = stage + Offset(0f, -caseW * 0.12f - rise * 22 * dp)
            val perch = stage + Offset(side * caseW * 0.62f, -caseW * 0.42f)
            val staged = Piece(
                lerp(home, perch, out), caseW * lerp(0.32f, 0.5f, out), lerp(-side * 28f, side * 8f, out),
                decel(span(t, 300f, 420f)), out,
            )
            return fly(staged, ringOf(label), bud, span(t, flyStart, flyStart + 360f))
        }
        val left = budPiece(-1, "Left", 700f)
        val right = budPiece(1, "Right", 780f)

        // A soft glow of the theme's colour behind everything, breathing out as the case arrives.
        val g = span(t, 0f, 700f)
        drawCircle(
            Brush.radialGradient(listOf(glow.copy(alpha = 0.28f * (1 - g)), Color.Transparent), stage, caseW * lerp(0.4f, 1.3f, decel(g))),
            caseW * lerp(0.4f, 1.3f, decel(g)), stage,
        )

        // Back to front: earbuds (behind the case until they are out), then the case.
        drawPiece(left, bud, mirrored = false)
        drawPiece(right, bud, mirrored = true)
        drawPiece(casePiece, case, mirrored = false, sweep = span(t, 160f, 700f))
    }
}

private const val TOTAL = 1200f

/** From where a piece stands on the stage into its ring, on an arc, shrinking to the ring's picture. */
private fun fly(staged: Piece, ring: Rect, image: ImageBitmap, p: Float): Piece {
    if (p <= 0f) return staged
    val e = emphasized(p)
    // The ring's picture is Fit into a square: its width is the square's, or less for a tall image.
    val fitW = minOf(ring.width, ring.height * image.width / image.height)
    val arc = Offset(0f, -kotlin.math.sin(e * PI.toFloat()) * 40f)
    return Piece(
        lerp(staged.center, ring.center, e) + arc, lerp(staged.width, fitW, e), lerp(staged.rotation, 0f, e),
        staged.alpha, staged.shadow * (1 - e),
    )
}

private val photoPaint = Paint().apply { filterQuality = androidx.compose.ui.graphics.FilterQuality.High }

private fun DrawScope.drawPiece(piece: Piece, image: ImageBitmap, mirrored: Boolean, sweep: Float = -1f) {
    if (piece.alpha <= 0f || piece.width <= 0f) return
    val w = piece.width
    val h = w * image.height / image.width
    val topLeft = piece.center - Offset(w / 2, h / 2)
    // Contact shadow: a soft ellipse under the piece, fading as it lifts off into its ring.
    if (piece.shadow > 0.01f) {
        val c = piece.center + Offset(0f, h * 0.55f)
        drawOval(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.32f * piece.shadow * piece.alpha), Color.Transparent), c, w * 0.5f),
            topLeft = c - Offset(w * 0.5f, h * 0.12f), size = Size(w, h * 0.24f),
        )
    }
    withTransform({
        rotate(piece.rotation, piece.center)
        if (mirrored) scale(-1f, 1f, piece.center)
    }) {
        drawIntoCanvas { canvas ->
            val bounds = Rect(topLeft, Size(w, h))
            canvas.saveLayer(bounds, Paint())
            photoPaint.alpha = piece.alpha
            canvas.drawImageRect(image, IntOffset.Zero, IntSize(image.width, image.height),
                IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()), IntSize(w.roundToInt(), h.roundToInt()), photoPaint)
            // Light running across the surface, only where the photo is (SrcAtop).
            if (sweep in 0f..1f) {
                val x = lerp(topLeft.x - w * 0.4f, topLeft.x + w * 1.4f, decel(sweep))
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                        start = Offset(x - w * 0.18f, topLeft.y), end = Offset(x + w * 0.18f, topLeft.y + h * 0.4f),
                    ),
                    topLeft, Size(w, h), blendMode = BlendMode.SrcAtop,
                )
            }
            canvas.restore()
        }
    }
}
