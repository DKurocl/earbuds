package ua.olex.sonyanc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import ua.olex.sonyanc.SonyProtocol.Mode
import kotlin.math.PI
import kotlin.math.sin

/** A one-shot response to a mode request. The glyph and its label retain the same layout bounds. */
@Composable
internal fun ModeGlyph(mode: Mode, request: Int?, selected: Boolean, color: Color) {
    val phase = remember { Animatable(1f) }
    val motionEnabled = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor != 0f
    LaunchedEffect(request, motionEnabled) {
        when {
            !motionEnabled -> phase.snapTo(1f)
            request == null -> phase.animateTo(1f, tween(72, easing = LinearEasing))
            else -> {
                phase.snapTo(0f)
                phase.animateTo(1f, tween(320, easing = LinearEasing))
            }
        }
    }
    val weight = animateFloatAsState(if (selected) 1f else 0f, tween(140), label = "Mode icon weight")
    val glyph = remember(mode) { GlyphContours(mode) }
    Canvas(Modifier.size(24.dp)) {
        val unit = size.minDimension / 960f
        withTransform({ translate(0f, size.height); scale(unit, -unit, Offset.Zero) }) {
            glyph.draw(this, phase.value, weight.value, color)
        }
    }
}

private fun impulse(time: Float, start: Float, finish: Float): Float {
    val position = ((time - start) / (finish - start)).coerceIn(0f, 1f)
    val amplitude = sin(PI * position).toFloat()
    return amplitude * amplitude
}

private class GlyphContours(private val mode: Mode) {
    private val regular = parse(when (mode) {
        Mode.ANC -> ModeGlyphPaths.ancRegular
        Mode.AMBIENT -> ModeGlyphPaths.ambientRegular
        Mode.OFF -> ModeGlyphPaths.offRegular
    })
    private val bold = parse(when (mode) {
        Mode.ANC -> ModeGlyphPaths.ancBold
        Mode.AMBIENT -> ModeGlyphPaths.ambientBold
        Mode.OFF -> ModeGlyphPaths.offBold
    })
    private val interpolated = List(regular.size) { Path() }
    private val ring = Path()
    private var previousWeight = -1f
    private var paths = regular

    private fun parse(contours: List<String>): List<Path> =
        contours.map { PathParser().parsePathString(it).toPath() }

    private fun updateWeight(weight: Float) {
        if (weight == previousWeight) return
        previousWeight = weight
        paths = when (weight) {
            0f -> regular
            1f -> bold
            else -> interpolated.also { output ->
                regular.indices.forEach { index ->
                    val start = regular[index].asAndroidPath()
                    val end = bold[index].asAndroidPath()
                    val result = output[index].asAndroidPath()
                    if (!start.interpolate(end, 1f - weight, result)) {
                        result.set(if (weight < .5f) start else end)
                    }
                }
            }
        }
        if (mode == Mode.ANC) {
            ring.reset()
            ring.addPath(paths[0])
            ring.addPath(paths[1])
        }
    }

    fun draw(scope: DrawScope, time: Float, weight: Float, color: Color) = with(scope) {
        updateWeight(weight)
        when (mode) {
            Mode.ANC -> {
                val aperture = 1f - .145f * impulse(time, .08f, .62f) + .024f * impulse(time, .62f, .87f)
                scale(aperture, aperture, Offset(480f, 480f)) { drawPath(ring, color) }
                drawPath(paths[2], color)
                val dot = 1f - .23f * impulse(time, .25f, .72f)
                scale(dot, dot, Offset(543f, 460f)) { drawPath(paths[3], color) }
            }
            Mode.AMBIENT -> {
                drawPath(paths[2], color)
                val dot = 1f + .25f * impulse(time, .1f, .45f)
                scale(dot, dot, Offset(387f, 597f)) { drawPath(paths[1], color) }
                val wave = impulse(time, .22f, .85f)
                translate(left = 62f * wave, top = 28f * wave) {
                    rotate(-7f * wave, pivot = Offset(710f, 600f)) { drawPath(paths[0], color) }
                }
            }
            Mode.OFF -> {
                drawPath(paths[4], color)
                drawPath(paths[5], color)
                val vertical = 53f * impulse(time, .1f, .68f)
                val horizontal = 61f * impulse(time, .18f, .81f)
                translate(top = -vertical) { drawPath(paths[0], color) }
                translate(left = -horizontal) { drawPath(paths[1], color) }
                translate(left = horizontal) { drawPath(paths[2], color) }
                translate(top = vertical) { drawPath(paths[3], color) }
            }
        }
    }
}
