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
 * The opening, once per cold start, the way Google's own apps do it: one short gesture of the icon,
 * then its hero takes its place in the page (0.76 s; a tap skips it). The splash shows the closed
 * case (drawable-nodpi/art_*, one illustrated parts sheet with every layer aligned to its own
 * assembled open case). As the splash lifts, the same case in the same place pops its lid open on a
 * spring, the earbuds showing in their wells and the status light blinking; then the lid shuts as
 * the case shrinks into Home's case ring (which shows the same artwork), the earbuds' rings come in
 * and the page fades up. Off (the default) everywhere else.
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

private const val TOTAL = 760f

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

/**
 * The layers' places on one canvas, in px of the parts sheet's assembled open case (686 x 598),
 * found by matching each part against it: x, y, width, height.
 */
private object Sheet {
    val CLOSED = floatArrayOf(7f, 171f, 664f, 428f)
    val BODY = floatArrayOf(0f, 210f, 687f, 389f)
    val LID = floatArrayOf(10f, 4f, 668f, 270f)
    val BUD_L = floatArrayOf(71f, 184f, 232f, 273f)
    val BUD_R = floatArrayOf(378f, 183f, 232f, 272f)
    const val SEAM = 355f   // bottom of the closed lid
    const val RIM = 344f    // the opening's front rim: what is below it is inside the case
    const val LED_X = 339f
    const val LED_Y = 406f
}

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
            // The earbuds' rings simply come in; the case lands in its own.
            if (value >= 430f) Intro.land("Left")
            if (value >= 480f) Intro.land("Right")
            if (value >= 730f) Intro.land("Case")
            Intro.reveal(decel(span(value, 380f, TOTAL)))
        }
        Intro.finish()
    }
    Canvas(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { Intro.finish() }) {
        // No rings to land in (setup, permission, another page): draw nothing; the effect ends it.
        if (Intro.targets.size < 3) return@Canvas
        val t = clock.value
        val dp = density
        val aspect = Sheet.CLOSED[2] / Sheet.CLOSED[3]
        val start = Intro.from ?: Rect(Offset(size.width / 2, size.height * 0.45f), 0f).let {
            Rect(it.center.x - 100 * dp, it.center.y - 100 / aspect * dp, it.center.x + 100 * dp, it.center.y + 100 / aspect * dp)
        }
        val w0 = start.width

        // ---- the timeline: one gesture, then the case takes its place in the page ----
        val breath = sin(span(t, 0f, 300f) * PI.toFloat()) * 0.03f
        // The lid pops open on a spring and shuts again as the case moves off.
        val open = spring(span(t, 0f, 420f), 0.25f) * (1 - decel(span(t, 400f, 560f)))
        val ledGlow = sin(span(t, 150f, 520f) * PI.toFloat())
        val fly = span(t, 400f, 740f)

        val stage = start.center
        val casePiece = fly(Piece(stage, w0 * (1 + breath), 0f, 1f), Intro.targets.getValue("Case"), aspect, fly)
        // The earbuds stay in their wells: they show while the lid is open.
        val k0 = casePiece.width / Sheet.CLOSED[2]
        fun seat(box: FloatArray) = Piece(
            Offset(casePiece.center.x + (box[0] + box[2] / 2 - (Sheet.CLOSED[0] + Sheet.CLOSED[2] / 2)) * k0,
                casePiece.center.y + (box[1] + box[3] / 2 - (Sheet.CLOSED[1] + Sheet.CLOSED[3] / 2)) * k0),
            box[2] * k0, 0f, casePiece.alpha,
        )
        val left = seat(Sheet.BUD_L)
        val right = seat(Sheet.BUD_R)

        // A glow of the theme colour behind, breathing out as the case comes to life.
        val g = span(t, 0f, 520f)
        val glowR = w0 * lerp(0.5f, 1.2f, decel(g))
        drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.22f * (1 - g)), Color.Transparent), stage, glowR), glowR, stage)
        // Contact shadow on the "floor" the case rose from.
        val floor = Offset(start.center.x, start.bottom + 4 * dp)
        drawOval(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.32f * (1 - fly)), Color.Transparent), floor, w0 * 0.55f),
            topLeft = floor - Offset(w0 * 0.48f, w0 * 0.06f), size = Size(w0 * 0.96f, w0 * 0.12f),
        )

        drawCase(casePiece, open, ledGlow, closed, bodyArt, lidArt) { rimY ->
            // In their wells: only what is above the opening's front rim shows.
            clipRect(bottom = rimY) { drawBud(left, budL); drawBud(right, budR) }
        }
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
    if (alpha <= 0f || dst.width <= 0.5f || dst.height <= 0.5f) return
    drawImage(img, IntOffset(src.left, src.top), IntSize(src.width, src.height),
        IntOffset(dst.left.roundToInt(), dst.top.roundToInt()), IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
        alpha = alpha, filterQuality = FilterQuality.High)
}

/**
 * The case, [piece] being its closed outline. Closed it is the closed artwork itself; opening, the
 * empty body, the earbuds in it ([inside], given the front rim), and the lid flipping back over its
 * hinge: first its outside turns away (shrinking up towards the hinge, darkening), then its inside
 * comes up behind the opening (growing from the hinge, brightening).
 */
private fun DrawScope.drawCase(
    piece: Piece, open: Float, ledGlow: Float, closed: ImageBitmap, bodyArt: ImageBitmap, lidArt: ImageBitmap,
    inside: DrawScope.(rimY: Float) -> Unit,
) {
    if (piece.alpha <= 0f) return
    val k = piece.width / Sheet.CLOSED[2]
    val ox = piece.center.x - (Sheet.CLOSED[0] + Sheet.CLOSED[2] / 2) * k
    val oy = piece.center.y - (Sheet.CLOSED[1] + Sheet.CLOSED[3] / 2) * k
    fun box(b: FloatArray) = Rect(ox + b[0] * k, oy + b[1] * k, ox + (b[0] + b[2]) * k, oy + (b[1] + b[3]) * k)
    val a = piece.alpha
    if (open <= 0.004f) {
        image(closed, box(Sheet.CLOSED), a)
    } else {
        val o = open.coerceIn(0f, 1.1f)
        // The lid's inside, rising from the hinge behind the opening once the lid is past upright.
        val up = ((o - 0.5f) / 0.5f).coerceIn(0f, 1.1f)
        if (up > 0f) {
            val full = box(Sheet.LID)
            val r = Rect(full.left, full.bottom - full.height * up, full.right, full.bottom)
            image(lidArt, r, a)
            drawRect(Color.Black.copy(alpha = 0.3f * (1 - up.coerceAtMost(1f)) * a), r.topLeft, r.size)
        }
        image(bodyArt, box(Sheet.BODY), a)
        inside(oy + Sheet.RIM * k)
        // The lid's outside: the closed artwork above the seam, turning away towards the hinge.
        val away = (o / 0.5f).coerceIn(0f, 1f)
        if (away < 1f) {
            val c = box(Sheet.CLOSED)
            val seamPx = ((Sheet.SEAM - Sheet.CLOSED[1]) / Sheet.CLOSED[3] * closed.height).roundToInt()
            val h = (Sheet.SEAM - Sheet.CLOSED[1]) * k * (1 - away)
            val r = Rect(c.left, c.top - 8 * k * away, c.right, c.top - 8 * k * away + h)
            image(closed, r, a, IntRect(0, 0, closed.width, seamPx))
            drawRect(Color.Black.copy(alpha = 0.35f * away * a), r.topLeft, r.size)
        }
    }
    // Status light blooming.
    if (ledGlow > 0.01f) {
        val led = Offset(ox + Sheet.LED_X * k, oy + Sheet.LED_Y * k)
        val r = piece.width * 0.05f * (1 + ledGlow)
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
