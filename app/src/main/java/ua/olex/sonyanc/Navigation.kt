package ua.olex.sonyanc

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Page motion, taken from Android's own cross-activity predictive back
 * (WindowManager Shell: CrossActivityBackAnimation, DefaultCrossActivityBackAnimation).
 */
internal object PageMotion {
    /** The swiped page and the page behind both shrink to this scale. */
    const val SCALE = 0.9f
    /** Gap to the screen edge the swiped page moves toward (cross_task_back_vertical_margin). */
    val MARGIN = 8.dp
    /** The page behind starts this far to the left (cross_activity_back_entering_start_offset). */
    val OFFSET = 96.dp
    /** Window corners while pages move. */
    val CORNER = 32.dp
    /** Corners of the card a page grows out of (container transform), as the Home tiles. */
    val CARD_CORNER = 28.dp
    /** Scrim over the page behind: dark and light theme (MAX_SCRIM_ALPHA_DARK / _LIGHT). */
    const val SCRIM_DARK = 0.8f
    const val SCRIM_LIGHT = 0.2f
    /** After letting go, and for the back button and opening a page (POST_COMMIT_DURATION). */
    const val SETTLE_MS = 450
    /** Android's BackGestureInterpolator: the page answers the finger quickly, then settles. */
    val GESTURE = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
    /** Emphasized (fast-out-extra-slow-in), as the system's post-commit phase. */
    val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * Where the two topmost pages are. [swipe]: the back gesture's progress through [PageMotion.GESTURE]
 * (frozen when the finger lets go). [settle]: 0 → 1 over [PageMotion.SETTLE_MS], linear; the pages
 * ease it themselves. [opening]: [settle] brings a new page in rather than taking the top one away.
 * [fromLeft]: the swipe's edge. [dy]: how far the finger moved vertically, px. Read in the draw
 * phase only, so motion never recomposes a page.
 */
internal class PagePose(
    val swipe: () -> Float = { 0f },
    val settle: () -> Float = { 0f },
    val opening: () -> Boolean = { false },
    val fromLeft: () -> Boolean = { true },
    val dy: () -> Float = { 0f },
    /** The card the top page grows out of and returns into (root px), or null for a slide. */
    val origin: () -> Rect? = { null },
)

/**
 * Where the next page grows from. A card records its bounds here just before it opens a page;
 * the page then opens as a container transform out of it and closes back into it.
 */
class PageOrigins {
    internal var next: Rect? = null
    fun mark(bounds: Rect?) { next = bounds?.takeIf { it.width > 0f && it.height > 0f } }
}

val LocalPageOrigins = staticCompositionLocalOf { PageOrigins() }

/**
 * A stack of pages that moves like Android's own screens. Back swipe: the page shrinks toward
 * the far edge and follows the finger up and down, the previous page waits behind it, shrunk,
 * a little to the left, under a scrim. Let go: the page slides off and fades, the previous one
 * grows to fill the screen. Cancel: everything springs back. The back button plays the same
 * ending from the start; opening a page is its mirror. Pages keep their saved state while covered.
 */
@Composable
internal fun PageStack(stack: List<String>, onPop: () -> Unit, content: @Composable (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(stack) }
    var swipe by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableFloatStateOf(0f) }
    var opening by remember { mutableStateOf(false) }
    var fromLeft by remember { mutableStateOf(true) }
    var dy by remember { mutableFloatStateOf(0f) }
    // A finished swipe has already played its ending; the stack change only swaps the pages.
    var swiped by remember { mutableStateOf(false) }
    val saved = rememberSaveableStateHolder()
    val origins = remember { PageOrigins() }
    // Per page: the card it grew out of, so it can return into it.
    val cards = remember { mutableStateMapOf<String, Rect>() }

    suspend fun settleTo() = animate(0f, 1f, animationSpec = tween(PageMotion.SETTLE_MS, easing = LinearEasing)) { v, _ -> settle = v }

    /** Swaps what is shown and resets the motion in one snapshot: no frame ever mixes the two. */
    fun land(next: List<String>) = Snapshot.withMutableSnapshot {
        shown = next; swipe = 0f; settle = 0f; opening = false; dy = 0f; fromLeft = true; swiped = false
    }

    LaunchedEffect(stack) {
        val old = shown
        when {
            stack == old -> Unit
            stack.size == old.size + 1 && stack.dropLast(1) == old -> {
                // The new page's first frame is already its starting pose.
                val card = origins.next.also { origins.next = null }
                Snapshot.withMutableSnapshot {
                    if (card != null) cards[stack.last()] = card else cards.remove(stack.last())
                    opening = true; settle = 0f; swipe = 0f; shown = stack
                }
                settleTo()
                land(stack)
            }
            stack.size < old.size && old.take(stack.size) == stack -> {
                if (!swiped) settleTo()
                old.drop(stack.size).forEach(saved::removeState)
                land(stack)
                old.drop(stack.size).forEach(cards::remove)
            }
            else -> land(stack)
        }
    }

    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        var startY = Float.NaN
        try {
            events.collect { e ->
                fromLeft = e.swipeEdge != BackEventCompat.EDGE_RIGHT
                if (startY.isNaN()) startY = e.touchY
                dy = e.touchY - startY
                swipe = PageMotion.GESTURE.transform(e.progress)
            }
            // Let go (or the 3-button Back): the system's ending, from wherever the finger was.
            swiped = true
            settleTo()
            onPop()
        } catch (e: CancellationException) {
            // A cancelled gesture also cancels this coroutine; spring back from outside it.
            if (!swiped) scope.launch {
                val from = swipe; val fromDy = dy
                animate(1f, 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { v, _ ->
                    swipe = from * v; dy = fromDy * v
                }
            }
            throw e
        }
    }

    CompositionLocalProvider(LocalPageOrigins provides origins) {
        PageLayers(shown, PagePose({ swipe }, { settle }, { opening }, { fromLeft }, { dy }, { cards[shown.last()] })) { page ->
            saved.SaveableStateProvider(page) { content(page) }
        }
    }
}

/** Draws the top page over the one beneath it. Stateless, so tests can pose it at any moment. */
@Composable
internal fun PageLayers(shown: List<String>, pose: PagePose, content: @Composable (String) -> Unit) {
    val top = shown.last()
    val under = shown.getOrNull(shown.size - 2)
    val background = MaterialTheme.colorScheme.background
    val scrimMax = if (isSystemInDarkTheme()) PageMotion.SCRIM_DARK else PageMotion.SCRIM_LIGHT
    // Cards report root coordinates; the layers draw relative to this box.
    val at = remember { mutableStateOf(Offset.Zero) }
    val local = PagePose(pose.swipe, pose.settle, pose.opening, pose.fromLeft, pose.dy) { pose.origin()?.translate(-at.value) }
    Box(Modifier.fillMaxSize().background(background).onGloballyPositioned { at.value = it.boundsInRoot().topLeft }) {
        // The page beneath stays composed (and saved), so a swipe starts on the very first frame.
        for (page in listOfNotNull(under, top)) key(page) {
            val isTop = page == top
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { if (isTop) closing(local) else behind(local) }
                    .background(background),
            ) { content(page) }
            // The scrim lies between the two pages, as the system's does.
            if (!isTop) Box(Modifier.fillMaxSize().drawBehind {
                val alpha = scrim(local) * scrimMax
                if (alpha > 0f) drawRect(Color.Black, alpha = alpha)
            })
        }
    }
}

private fun moving(pose: PagePose) = pose.swipe() > 0f || pose.settle() > 0f || pose.opening()

private fun scrim(pose: PagePose): Float = when {
    pose.opening() -> pose.settle()
    moving(pose) -> 1f - pose.settle()
    else -> 0f
}

/** The swipe's pose: scale, left and top of a page, before letting go. */
private class Pose(val scale: Float, val left: Float, val top: Float)

private fun GraphicsLayerScope.gesturePose(pose: PagePose, behind: Boolean): Pose {
    val w = size.width; val h = size.height
    val p = pose.swipe()
    val scale = lerp(1f, PageMotion.SCALE, p)
    val margin = PageMotion.MARGIN.toPx()
    // Closing page: centered, then (left-edge swipe) pushed to the right edge minus the margin.
    val centeredLeft = (1f - PageMotion.SCALE) / 2f * w
    val closingLeft = if (pose.fromLeft()) w - margin - PageMotion.SCALE * w else centeredLeft
    val left = if (behind) -PageMotion.OFFSET.toPx() + centeredLeft * p else closingLeft * p
    // Both follow the finger vertically, decelerating, never past the margin.
    val ratio = min(h / 2f, abs(pose.dy())) / (h / 2f)
    val decelerated = 1f - (1f - ratio) * (1f - ratio)
    val yRoom = max(0f, (h - h * scale) / 2f - margin)
    val top = (1f - PageMotion.SCALE) / 2f * h * p + yRoom * decelerated * Math.signum(pose.dy())
    return Pose(scale, left, top)
}

private fun GraphicsLayerScope.place(scale: Float, left: Float, top: Float, rounded: Boolean) {
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = scale; scaleY = scale
    translationX = left; translationY = top
    shape = RoundedCornerShape(PageMotion.CORNER)
    clip = rounded
}

/** The top page: the one being swiped or closed, or the one opening. */
private fun GraphicsLayerScope.closing(pose: PagePose) {
    if (!moving(pose)) return
    val t = pose.settle()
    val e = PageMotion.EMPHASIZED.transform(t)
    val offset = PageMotion.OFFSET.toPx()
    val card = pose.origin()
    val full = Rect(0f, 0f, size.width, size.height)
    val cardCorner = PageMotion.CARD_CORNER.toPx()
    if (pose.opening()) {
        if (card != null) {
            // Container transform: the page grows out of the card, appearing over the first tenth.
            alpha = min(1f, t * 10f)
            container(lerp(card, full, e), lerp(cardCorner, 0f, e))
            return
        }
        // Mirror of closing: in from the right, appearing over the first fifth.
        alpha = min(1f, t * 5f)
        place(1f, lerp(offset, 0f, e), 0f, rounded = t < 1f)
        return
    }
    val start = gesturePose(pose, behind = false)
    if (card != null) {
        // Back into the card, from wherever the swipe left the page; gone over the last tenth.
        val from = Rect(start.left, start.top, start.left + start.scale * size.width, start.top + start.scale * size.height)
        alpha = min(1f, (1f - t) * 10f)
        container(lerp(from, card, e), lerp(PageMotion.CORNER.toPx() * (if (pose.swipe() > 0f) 1f else 0f), cardCorner, e))
        return
    }
    // Let go: grows back while sliding off to the right, gone after the first fifth.
    alpha = max(0f, 1f - t * 5f)
    place(lerp(start.scale, 1f, e), lerp(start.left, start.left + offset, e), lerp(start.top, 0f, e), rounded = true)
}

/** The page beneath: shrunk and to the left under a swipe, growing back as the top page leaves. */
private fun GraphicsLayerScope.behind(pose: PagePose) {
    if (!moving(pose)) { alpha = 0f; return }
    val t = pose.settle()
    val e = PageMotion.EMPHASIZED.transform(t)
    val offset = PageMotion.OFFSET.toPx()
    if (pose.opening()) {
        // Under a card that grows, the page stays where it is; otherwise it moves aside.
        place(1f, if (pose.origin() != null) 0f else lerp(0f, -offset, e), 0f, rounded = true)
        return
    }
    if (pose.origin() != null && pose.swipe() == 0f) { place(1f, 0f, 0f, rounded = false); return }
    val start = gesturePose(pose, behind = true)
    place(lerp(start.scale, 1f, e), lerp(start.left, 0f, e), lerp(start.top, 0f, e), rounded = t < 1f)
}

private fun lerp(a: Rect, b: Rect, t: Float) =
    Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

/**
 * Draws the page inside [rect] (layer px) the way a container transform does: scaled to the
 * card's width, cut to its height, with [corner] px corners.
 */
private fun GraphicsLayerScope.container(rect: Rect, corner: Float) {
    val s = (rect.width / size.width).coerceAtLeast(0.01f)
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = s; scaleY = s
    translationX = rect.left; translationY = rect.top
    val height = rect.height / s
    val radius = corner / s
    shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
            Outline.Rounded(RoundRect(0f, 0f, size.width, height, CornerRadius(radius)))
    }
    clip = true
}
