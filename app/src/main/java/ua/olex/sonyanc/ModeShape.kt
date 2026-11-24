package ua.olex.sonyanc

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import ua.olex.sonyanc.SonyProtocol.Mode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Each mode's own shape, the same numbers as the widget's (docs/widget/connected.py): ANC a
 * tight nine-scallop cookie (sealed in), Ambient a soft six-petal flower (open), Off a plain
 * circle. A change morphs one into the other on Material's bouncy spatial spring and turns the
 * mode's container color, so the state shows before any word is read. Drawn only in the draw
 * phase; nothing runs while the mode stays the same.
 */
internal object ModeShape {
    const val COOKIE_LOBES = 9
    const val COOKIE_DEPTH = 0.07f
    const val FLOWER_LOBES = 6
    const val FLOWER_DEPTH = 0.11f

    /** Radius at angle [t] (radians) for the blend of cookie [a] and flower [b], 0..1 each. */
    fun radius(t: Float, a: Float, b: Float) =
        1f + a * COOKIE_DEPTH * cos(COOKIE_LOBES * t) + b * FLOWER_DEPTH * cos(FLOWER_LOBES * t)
}

@Composable
internal fun ModeHalo(mode: Mode?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val cookie by animateFloatAsState(if (mode == Mode.ANC) 1f else 0f, spring, label = "cookie")
    val flower by animateFloatAsState(if (mode == Mode.AMBIENT) 1f else 0f, spring, label = "flower")
    val turn by animateFloatAsState(when (mode) { Mode.ANC -> 0f; Mode.AMBIENT -> 30f; else -> 15f }, spring, label = "turn")
    val color by animateColorAsState(
        when (mode) {
            Mode.ANC -> cs.primaryContainer
            Mode.AMBIENT -> cs.tertiaryContainer
            else -> Color.Transparent
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "halo color",
    )
    Box(modifier.drawBehind {
        if (color.alpha == 0f) return@drawBehind
        val r = size.minDimension / 2f / (1f + ModeShape.FLOWER_DEPTH)
        val cx = size.width / 2f; val cy = size.height / 2f
        val rot = Math.toRadians(turn.toDouble()).toFloat()
        val path = Path()
        val n = 144
        for (i in 0 until n) {
            val t = (2 * Math.PI * i / n).toFloat()
            val rr = r * ModeShape.radius(t, cookie, flower)
            val x = cx + rr * sin(t + rot); val y = cy - rr * cos(t + rot)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    })
}
