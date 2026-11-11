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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
)

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
                Snapshot.withMutableSnapshot { opening = true; settle = 0f; swipe = 0f; shown = stack }
                settleTo()
                land(stack)
            }
            stack.size < old.size && old.take(stack.size) == stack -> {
                if (!swiped) settleTo()
                old.drop(stack.size).forEach(saved::removeState)
                land(stack)
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

    PageLayers(shown, PagePose({ swipe }, { settle }, { opening }, { fromLeft }, { dy })) { page ->
        saved.SaveableStateProvider(page) { content(page) }
    }
}

/** Draws the top page over the one beneath it. Stateless, so tests can pose it at any moment. */
@Composable
internal fun PageLayers(shown: List<String>, pose: PagePose, content: @Composable (String) -> Unit) {
    val top = shown.last()
    val under = shown.getOrNull(shown.size - 2)
    val background = MaterialTheme.colorScheme.background
    val scrimMax = if (isSystemInDarkTheme()) PageMotion.SCRIM_DARK else PageMotion.SCRIM_LIGHT
    Box(Modifier.fillMaxSize().background(background)) {
        // The page beneath stays composed (and saved), so a swipe starts on the very first frame.
        for (page in listOfNotNull(under, top)) key(page) {
            val isTop = page == top
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { if (isTop) closing(pose) else behind(pose) }
                    .background(background),
            ) { content(page) }
            // The scrim lies between the two pages, as the system's does.
            if (!isTop) Box(Modifier.fillMaxSize().drawBehind {
                val alpha = scrim(pose) * scrimMax
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
    if (pose.opening()) {
        // Mirror of closing: in from the right, appearing over the first fifth.
        alpha = min(1f, t * 5f)
        place(1f, lerp(offset, 0f, e), 0f, rounded = t < 1f)
        return
    }
    val start = gesturePose(pose, behind = false)
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
        place(1f, lerp(0f, -offset, e), 0f, rounded = true)
        return
    }
    val start = gesturePose(pose, behind = true)
    place(lerp(start.scale, 1f, e), lerp(start.left, 0f, e), lerp(start.top, 0f, e), rounded = t < 1f)
}
