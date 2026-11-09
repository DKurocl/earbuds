package ua.olex.sonyanc

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Page motion, after Android's own predictive back between screens and Material 3. */
internal object PageMotion {
    /** The swiped page shrinks to this scale. */
    const val SCALE = 0.9f
    /** Gap between the shrunk page and the screen edge it moves toward. */
    val MARGIN = 8.dp
    /** Corner radius the page reaches early in the swipe (Pixel screens are about this round). */
    val CORNER = 32.dp
    val ELEVATION = 16.dp
    /** How far the page underneath sits to the left while covered, as a share of the width. */
    const val PARALLAX = 0.25f
    /** Scrim over the covered page; a swipe lifts some of it as it reveals the page. */
    const val DIM = 0.32f
    /** The swiped page follows the finger vertically by this share of the movement, at most [LIFT] of the height. */
    const val FOLLOW = 0.12f
    const val LIFT = 0.03f
    const val OPEN_MS = 400
    const val CLOSE_MS = 300
    /** Material 3 emphasized decelerate: pages arrive fast and settle. */
    val EMPHASIZED = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

/**
 * Where the two topmost pages are. [swipe] is the back gesture's progress (0 at rest, 1 fully
 * pulled); [slide] puts the top page in place (1) or gone past the edge (0), which is how pages
 * open, close by tap, and finish a swipe. [fromLeft] is the swipe's edge; [dy] how far the finger
 * moved vertically. All are read in the draw phase only, so motion never recomposes a page.
 */
internal class PagePose(
    val swipe: () -> Float = { 0f },
    val slide: () -> Float = { 1f },
    val fromLeft: () -> Boolean = { true },
    val dy: () -> Float = { 0f },
)

/**
 * A stack of pages with Android-style predictive back. While the finger moves, the page shrinks
 * toward the far edge with rounded corners and a shadow, following the finger a little up and
 * down, and the previous page waits underneath, dimmed and already live. Letting go sends the
 * page away and the previous one settles in; cancelling springs it back. Opening a page slides it
 * in from the right over the old one, and the back arrow does the reverse.
 */
@Composable
internal fun PageStack(stack: List<String>, onPop: () -> Unit, content: @Composable (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // What is drawn: the stack, plus a page that is still leaving.
    var shown by remember { mutableStateOf(stack) }
    val swipe = remember { Animatable(0f) }
    val slide = remember { Animatable(1f) }
    var fromLeft by remember { mutableStateOf(true) }
    var dy by remember { mutableFloatStateOf(0f) }
    // A finished swipe has already moved the page away; the stack change must not animate it again.
    var swiped by remember { mutableStateOf(false) }
    val saved = rememberSaveableStateHolder()

    LaunchedEffect(stack) {
        val old = shown
        when {
            stack == old -> Unit
            stack.size == old.size + 1 && stack.dropLast(1) == old -> {
                fromLeft = true
                shown = stack
                slide.snapTo(0f)
                slide.animateTo(1f, tween(PageMotion.OPEN_MS, easing = PageMotion.EMPHASIZED))
            }
            stack.size < old.size && old.take(stack.size) == stack -> {
                if (!swiped) slide.animateTo(0f, tween(PageMotion.CLOSE_MS, easing = PageMotion.EMPHASIZED))
                old.drop(stack.size).forEach(saved::removeState) // a closed page starts fresh next time
                shown = stack
                slide.snapTo(1f); swipe.snapTo(0f); dy = 0f; fromLeft = true; swiped = false
            }
            else -> { shown = stack; slide.snapTo(1f); swipe.snapTo(0f); swiped = false }
        }
    }

    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        var startY = Float.NaN
        try {
            events.collect { e ->
                fromLeft = e.swipeEdge != BackEventCompat.EDGE_RIGHT
                if (startY.isNaN()) startY = e.touchY
                dy = e.touchY - startY
                swipe.snapTo(e.progress)
            }
            // Let go: the page leaves toward the edge it was pulled to (also the 3-button Back).
            swiped = true
            slide.animateTo(0f, tween(PageMotion.CLOSE_MS, easing = PageMotion.EMPHASIZED))
            onPop()
        } catch (e: CancellationException) {
            // A cancelled gesture also cancels this coroutine; spring back from outside it.
            if (!swiped) scope.launch {
                launch { swipe.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) }
                val y = Animatable(dy)
                y.animateTo(0f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { dy = value }
            }
            throw e
        }
    }

    PageLayers(shown, PagePose({ swipe.value }, { slide.value }, { fromLeft }, { dy })) { page ->
        saved.SaveableStateProvider(page) { content(page) }
    }
}

/** Draws the top page and, while it moves, the page underneath. Stateless, so tests can pose it. */
@Composable
internal fun PageLayers(shown: List<String>, pose: PagePose, content: @Composable (String) -> Unit) {
    val top = shown.last()
    val under = shown.getOrNull(shown.size - 2)
    // Composed only while it can be seen; flips once per move, not every frame.
    val underVisible by remember(under) { derivedStateOf { under != null && (pose.swipe() > 0f || pose.slide() < 1f) } }
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background)) {
        for (page in listOfNotNull(under.takeIf { underVisible }, top)) key(page) {
            val isTop = page == top
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { if (isTop) top(pose) else under(pose) }
                    .background(background)
                    .drawWithContent {
                        drawContent()
                        if (!isTop) {
                            val dim = PageMotion.DIM * pose.slide() * (1f - 0.4f * pose.swipe())
                            if (dim > 0f) drawRect(Color.Black, alpha = dim)
                        }
                    },
            ) { content(page) }
        }
    }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.top(pose: PagePose) {
    val p = pose.swipe(); val s = pose.slide()
    if (p == 0f && s == 1f) return
    val w = size.width; val h = size.height
    val dir = if (pose.fromLeft()) 1f else -1f
    val scale = lerp(1f, PageMotion.SCALE, p)
    // Shrinks toward the far edge, keeping MARGIN to it; then leaves past that edge.
    translationX = dir * ((1f - scale) / 2f * w - PageMotion.MARGIN.toPx() * p + (1f - s) * w)
    translationY = (pose.dy() * PageMotion.FOLLOW).coerceIn(-h * PageMotion.LIFT, h * PageMotion.LIFT) * p
    scaleX = scale; scaleY = scale
    shape = RoundedCornerShape(PageMotion.CORNER * (p * 4f).coerceAtMost(1f))
    clip = p > 0f
    shadowElevation = PageMotion.ELEVATION.toPx() * maxOf(p * 4f, 1f - s).coerceAtMost(1f)
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.under(pose: PagePose) {
    // Covered by an opening or closing page: a quarter to the left. Under a swipe it waits in
    // place, seen around the shrinking page, so no edge of it ever shows.
    translationX = if (pose.swipe() > 0f) 0f else -PageMotion.PARALLAX * size.width * pose.slide()
}
