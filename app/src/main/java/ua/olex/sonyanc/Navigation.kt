package ua.olex.sonyanc

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CancellationException

/** Page navigation shared by the app and its tests. */
internal object PageMotion {
    /** How far the page underneath sits to the left, as a share of the width. */
    const val PARALLAX = 4
    /** Scrim over the page underneath at the start of a back swipe. */
    const val DIM = 0.32f
    val EDGE = 14.dp
    const val SWIPE_MS = 300
    const val TAP_MS = 400
    /** Material 3 emphasized decelerate, for taps (a swipe follows the finger linearly). */
    val EMPHASIZED: Easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

/**
 * A stack of pages with Chrome-style predictive back: while the finger moves, the page slides
 * right exactly with it and the previous page comes out from underneath (shifted left and dimmed,
 * already live). Letting go finishes the move; cancelling slides the page back. Opening a page is
 * the mirror: the new page slides in from the right over the old one.
 */
@Composable
internal fun PageStack(stack: List<String>, onPop: () -> Unit, content: @Composable (String) -> Unit) {
    val nav = remember { SeekableTransitionState(stack) }
    var swiping by remember { mutableStateOf(false) }
    LaunchedEffect(stack) {
        if (nav.currentState != stack) nav.animateTo(stack)
        swiping = false
    }
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        val previous = stack.dropLast(1)
        swiping = true
        try {
            events.collect { nav.seekTo(it.progress, previous) }
            onPop() // the stack change finishes the transition from where the finger left it
        } catch (e: CancellationException) {
            nav.animateTo(nav.currentState)
            swiping = false
        }
    }
    PageTransition(rememberTransition(nav, label = "pages"), swiping, content)
}

/** The page transition itself; [swiping] makes it follow the finger linearly. */
@Composable
internal fun PageTransition(transition: Transition<List<String>>, swiping: Boolean, content: @Composable (String) -> Unit) {
    val ms = if (swiping) PageMotion.SWIPE_MS else PageMotion.TAP_MS
    val easing = if (swiping) LinearEasing else PageMotion.EMPHASIZED
    transition.AnimatedContent(
        contentKey = { it.last() },
        transitionSpec = {
            val slide = tween<IntOffset>(ms, easing = easing)
            val back = targetState.size < initialState.size
            val move = if (back)
                slideInHorizontally(slide) { -it / PageMotion.PARALLAX } togetherWith slideOutHorizontally(slide) { it }
            else
                slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / PageMotion.PARALLAX }
            // Deeper pages always draw on top, whichever way the move goes.
            move.apply { targetContentZIndex = targetState.size.toFloat() }
        },
    ) { pages ->
        // The page underneath (revealed by back, covered by a new page) is dimmed while it moves.
        val under = pages.size < maxOf(transition.currentState.size, transition.targetState.size)
        val dim = this.transition.animateFloat(transitionSpec = { tween(ms, easing = easing) }, label = "dim") {
            if (it == EnterExitState.Visible || !under) 0f else PageMotion.DIM
        }
        val moving = transition.currentState != transition.targetState
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                // Read in the draw phase only: the gesture never recomposes the page.
                .drawWithContent {
                    drawContent()
                    if (dim.value > 0f) drawRect(Color.Black, alpha = dim.value)
                    // The moving page casts a soft edge on the one underneath, so dark pages stay apart.
                    if (!under && moving) {
                        val edge = PageMotion.EDGE.toPx()
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), startX = -edge, endX = 0f),
                            topLeft = Offset(-edge, 0f), size = Size(edge, size.height),
                        )
                    }
                },
        ) { content(pages.last()) }
    }
}
