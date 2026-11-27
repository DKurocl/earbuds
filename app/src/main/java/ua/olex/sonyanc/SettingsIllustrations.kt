package ua.olex.sonyanc

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private const val SCENE_WIDTH = 336f
private const val SCENE_HEIGHT = 214f

/** Plays locally while this page is visible. Compose honours the system animator scale. */
@Composable
private fun previewProgress(selection: Any?, duration: Int): () -> Float {
    val animated = ValueAnimator.areAnimatorsEnabled()
    val position = remember(selection, animated) { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(position, duration) {
        if (animated) position.animateTo(1f, tween(duration, easing = LinearEasing))
    }
    return { position.value }
}

private fun phase(time: Float, start: Float, end: Float): Float =
    FastOutSlowInEasing.transform(((time - start) / (end - start)).coerceIn(0f, 1f))

@Composable
private fun Scene(content: @Composable BoxWithConstraintsScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        // Canvas coordinates and the physical left/right earbuds stay aligned in RTL layouts.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                Modifier.widthIn(max = 368.dp).fillMaxWidth().aspectRatio(SCENE_WIDTH / SCENE_HEIGHT),
                content = content,
            )
        }
    }
}

private fun BoxWithConstraintsScope.placed(x: Float, y: Float, width: Float, height: Float = width): Modifier =
    Modifier.offset(maxWidth * (x / SCENE_WIDTH), maxWidth * (y / SCENE_WIDTH))
        .size(maxWidth * (width / SCENE_WIDTH), maxWidth * (height / SCENE_WIDTH))

@Composable
private fun SceneCanvas(draw: DrawScope.() -> Unit) {
    Canvas(Modifier.fillMaxSize()) {
        scale(size.width / SCENE_WIDTH, size.height / SCENE_HEIGHT, pivot = Offset.Zero, block = { draw() })
    }
}

private fun DrawScope.tile(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) {
    drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(radius))
}

private fun DrawScope.line(color: Color, x0: Float, y0: Float, x1: Float, y1: Float, width: Float) {
    drawLine(color, Offset(x0, y0), Offset(x1, y1), width, StrokeCap.Round)
}

@Composable
private fun ProductBud(left: Boolean, modifier: Modifier) {
    Image(painterResource(if (left) R.drawable.device_bud_left else R.drawable.device_bud_right), null, modifier)
}

@Composable
internal fun TouchIllustration(left: Boolean, assignment: Int?) {
    val c = MaterialTheme.colorScheme
    val t = previewProgress(assignment, 1580)
    val active = assignment == 0 || assignment == AdvancedSettings.PLAYBACK
    val target = Offset(if (left) 197f else 139f, 95f)
    Scene {
        SceneCanvas {
            tile(64f, 25f, 208f, 164f, 60f, c.surfaceContainerHighest)
            drawCircle(if (active) c.primaryContainer else c.secondaryContainer, 74f, Offset(168f, 107f))
            drawCircle(c.surfaceContainerHigh, 60f, Offset(168f, 107f), style = Stroke(1.5f))
        }
        ProductBud(left, placed(86f, 27f, 164f).graphicsLayer {
            val time = t()
            val response = phase(time, .14f, .25f) - phase(time, .25f, .43f)
            val repeat = if (assignment == AdvancedSettings.PLAYBACK) phase(time, .44f, .54f) - phase(time, .54f, .73f) else 0f
            val press = (response + repeat) * if (active) 1f else 0f
            scaleX = 1f - .035f * press
            scaleY = scaleX
        })
        SceneCanvas {
            val ink = if (active) c.primary else c.outline
            drawCircle(c.surface, 13f, target)
            drawCircle(ink, 11f, target, style = Stroke(2.5f))
            drawCircle(ink, 4f, target)
            if (active) {
                val time = t()
                for (start in if (assignment == AdvancedSettings.PLAYBACK) listOf(.18f, .49f) else listOf(.18f)) {
                    val pulse = phase(time, start, start + .31f)
                    if (pulse > 0f && pulse < 1f) {
                        drawCircle(ink.copy(alpha = .75f * (1f - pulse)), 13f + pulse * 36f, target, style = Stroke(3f))
                    }
                }
            } else line(ink, target.x - 8f, target.y + 8f, target.x + 8f, target.y - 8f, 2.5f)
        }
    }
}

@Composable
internal fun NoiseCycleIllustration(included: Set<Int>) {
    val c = MaterialTheme.colorScheme
    val t = previewProgress(included, 1720)
    val icons = listOf(Glyph.QUIET, Glyph.AMBIENT, Glyph.OFF)
    val enabled = included.sorted()
    val positions = listOf(Offset(66f, 104f), Offset(168f, 77f), Offset(270f, 104f))
    Scene {
        SceneCanvas {
            val route = Path().apply {
                moveTo(66f, 104f)
                cubicTo(92f, 69f, 121f, 77f, 168f, 77f)
                cubicTo(215f, 77f, 244f, 69f, 270f, 104f)
            }
            drawPath(route, c.surfaceContainerHighest, style = Stroke(20f, cap = StrokeCap.Round))
            if (enabled.size >= 2) {
                val start = positions[enabled.first()]
                val end = positions[enabled.last()]
                val loop = Path().apply {
                    moveTo(start.x, start.y + 25f)
                    cubicTo(start.x, 187f, end.x, 187f, end.x, end.y + 25f)
                }
                drawPath(loop, c.secondaryContainer, style = Stroke(9f, cap = StrokeCap.Round))
                val time = t()
                if (time in .5f..0.88f) {
                    val path = PathMeasure().apply { setPath(loop, false) }
                    val travel = 1f - phase(time, .5f, .88f)
                    drawCircle(c.primary, 6f, path.getPosition(path.length * travel))
                }
            }
            positions.forEachIndexed { index, at ->
                val selected = index in included
                val order = enabled.indexOf(index)
                val time = t()
                val beat = if (order < 0) 0f else {
                    val start = .075f + order * .15f
                    phase(time, start, start + .055f) - phase(time, start + .055f, start + .14f)
                }
                drawCircle(if (selected) c.primaryContainer else c.surfaceContainerHigh, 37f + beat * 4f, at)
                if (beat > 0f) drawCircle(c.primary.copy(alpha = beat), 40f + beat * 6f, at, style = Stroke(3f))
            }
        }
        positions.forEachIndexed { index, point ->
            val selected = index in included
            HushIcon(
                icons[index], placed(point.x - 19f, point.y - 19f, 38f),
                if (selected) c.onPrimaryContainer else c.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BluetoothQualityIllustration(stable: Boolean) {
    val c = MaterialTheme.colorScheme
    val t = previewProgress(stable, 1550)
    Scene {
        SceneCanvas {
            tile(42f, 27f, 86f, 154f, 25f, c.outline)
            tile(45f, 30f, 80f, 148f, 22f, c.surfaceContainerHighest)
            tile(53f, 38f, 64f, 132f, 17f, c.primaryContainer)
            tile(76f, 45f, 18f, 4f, 2f, c.onPrimaryContainer)
            drawCircle(c.secondaryContainer, 55f, Offset(256f, 105f))
            val path = Path().apply {
                moveTo(126f, 106f)
                if (stable) lineTo(211f, 106f)
                else cubicTo(161f, 106f, 169f, 68f, 211f, 87f)
            }
            drawPath(path, c.primaryContainer, style = Stroke(14f, cap = StrokeCap.Round))
            drawPath(path, c.primary, style = Stroke(2.5f, cap = StrokeCap.Round))
            val measure = PathMeasure().apply { setPath(path, false) }
            val time = t()
            for (i in 0..2) {
                val delay = .11f * i + .12f
                val travel = phase(time, delay, delay + .49f)
                if (travel in .01f..0.99f) {
                    drawCircle(c.primary, if (i == 0) 5f else 3f, measure.getPosition(measure.length * travel))
                }
            }
            drawCircle(c.primary, 4f, measure.getPosition(measure.length * .54f))
        }
        HushIcon(Glyph.BLUETOOTH, placed(67f, 87f, 36f), c.onPrimaryContainer)
        ProductBud(false, placed(205f, 54f, 102f).graphicsLayer {
            val time = t()
            val receipt = phase(time, .61f, .72f) - phase(time, .72f, .89f)
            scaleX = 1f + receipt * .055f
            scaleY = scaleX
        })
    }
}

@Composable
internal fun FitPlacementIllustration() {
    val c = MaterialTheme.colorScheme
    val t = previewProgress(Unit, 1480)
    Scene {
        SceneCanvas {
            tile(24f, 35f, 288f, 144f, 58f, c.surfaceContainerHigh)
            drawCircle(c.secondaryContainer, 57f, Offset(100f, 107f))
            drawCircle(c.secondaryContainer, 57f, Offset(236f, 107f))
            for (left in listOf(true, false)) {
                val direction = if (left) -1f else 1f
                val x = if (left) 100f else 236f
                val time = t()
                val turn = phase(time, .33f, .72f)
                val arc = if (left) 158f else 22f
                drawArc(
                    c.primary, if (left) arc - 74f * turn else arc, 74f * turn, false,
                    Offset(x - 58f, 49f), Size(116f, 116f),
                    style = Stroke(3f, cap = StrokeCap.Round),
                )
                if (turn > .9f) {
                    val angle = (arc + direction * 74f) * PI / 180
                    val at = Offset(x + kotlin.math.cos(angle).toFloat() * 58f, 107f + sin(angle).toFloat() * 58f)
                    drawCircle(c.primary, 4.5f, at)
                }
            }
        }
        for (left in listOf(true, false)) {
            val direction = if (left) -1f else 1f
            ProductBud(left, placed(if (left) 44f else 180f, 51f, 112f).graphicsLayer {
                val move = phase(t(), .04f, .44f)
                val turn = phase(t(), .30f, .73f)
                translationX = (14.dp).toPx() * direction * (1f - move)
                rotationZ = direction * 18f * (1f - turn)
            })
        }
    }
}

internal enum class SettingDiagram { VOICE_DETECTION, MUSIC_RETURN, AUTO_POWER, PROMPT_VOLUME }

@Composable
internal fun SettingIllustration(diagram: SettingDiagram, value: Int?) {
    val c = MaterialTheme.colorScheme
    val t = previewProgress(diagram to value, when (diagram) {
        SettingDiagram.VOICE_DETECTION, SettingDiagram.MUSIC_RETURN -> 1680
        else -> 1370
    })
    Scene {
        when (diagram) {
            SettingDiagram.VOICE_DETECTION -> {
                SceneCanvas { voiceDetection(c, value, t()) }
                HushIcon(Glyph.MIC, placed(151f, 30f, 34f), c.onPrimaryContainer)
            }
            SettingDiagram.MUSIC_RETURN -> SceneCanvas { musicReturn(c, value, t()) }
            SettingDiagram.AUTO_POWER -> {
                SceneCanvas { autoPower(c, value == 0x10, t()) }
                ProductBud(false, placed(47f, 62f, 112f))
                HushIcon(Glyph.POWER, placed(230f, 52f, 29f), c.onSecondaryContainer)
            }
            SettingDiagram.PROMPT_VOLUME -> {
                SceneCanvas { promptVolume(c, value, t()) }
                HushIcon(Glyph.CAMPAIGN, placed(59f, 85f, 46f), c.onPrimaryContainer)
            }
        }
    }
}

private fun DrawScope.voiceDetection(c: ColorScheme, value: Int?, t: Float) {
    tile(27f, 58f, 282f, 121f, 42f, c.surfaceContainerHighest)
    drawCircle(c.primaryContainer, 28f, Offset(168f, 47f))
    val heights = listOf(13f, 20f, 27f, 18f, 38f, 24f, 54f, 44f, 33f, 44f, 24f, 36f, 16f, 24f, 19f)
    val threshold = when (value) { 1 -> 119f; 2 -> 94f; else -> 106f }
    val thresholdY = 124f + (threshold - 124f) * phase(t, .36f, .72f)
    heights.forEachIndexed { i, h ->
        val x = 51f + i * 16.7f
        val build = .40f + .60f * phase(t, i * .022f, .34f + i * .022f)
        val half = h * build * .70f
        line(if (132f - half <= thresholdY) c.primary else c.outline, x, 132f - half, x, 132f + half, 5f)
    }
    line(c.tertiary, 44f, thresholdY, 291f, thresholdY, 2f)
    drawCircle(c.tertiary, 5f, Offset(291f, thresholdY))
}

private fun DrawScope.musicReturn(c: ColorScheme, value: Int?, t: Float) {
    tile(32f, 49f, 272f, 118f, 36f, c.surfaceContainerHigh)
    tile(47f, 62f, 91f, 91f, 26f, c.primaryContainer)
    val resumed = if (value == 3) 1f else phase(t, .65f, .84f)
    val pause = 1f - resumed
    if (pause > 0f) {
        line(c.onPrimaryContainer.copy(alpha = pause), 84f, 96f, 84f, 121f, 6f)
        line(c.onPrimaryContainer.copy(alpha = pause), 102f, 96f, 102f, 121f, 6f)
    }
    if (resumed > 0f) drawPath(Path().apply {
        moveTo(86f, 93f); lineTo(107f, 108f); lineTo(86f, 123f); close()
    }, c.onPrimaryContainer.copy(alpha = resumed))
    line(c.outline, 155f, 108f, 280f, 108f, 4f)
    val automatic = value != 3
    val end = when (value) { 0 -> 205f; 2 -> 266f; 3 -> 155f; else -> 237f }
    val x = 155f + (end - 155f) * phase(t, .17f, .70f)
    if (automatic) line(c.primary, 155f, 108f, x, 108f, 4f)
    drawCircle(if (automatic) c.primary else c.tertiary, 7f, Offset(x, 108f))
}

private fun DrawScope.autoPower(c: ColorScheme, automatic: Boolean, t: Float) {
    drawCircle(c.surfaceContainerHigh, 66f, Offset(103f, 118f))
    val clock = Offset(218f, 106f)
    drawCircle(c.primaryContainer, 65f, clock)
    drawCircle(c.onPrimaryContainer.copy(alpha = .26f), 50f, clock, style = Stroke(3f))
    val sweep = if (automatic) 265f * phase(t, .07f, .73f) else 0f
    if (automatic) {
        drawArc(c.onPrimaryContainer, -90f, sweep, false, clock - Offset(50f, 50f), Size(100f, 100f), style = Stroke(5f, cap = StrokeCap.Round))
        line(c.onPrimaryContainer, clock.x, clock.y, clock.x, clock.y - 24f, 5f)
        line(c.onPrimaryContainer, clock.x, clock.y, clock.x + 16f, clock.y + 7f, 5f)
    } else line(c.onPrimaryContainer, clock.x - 15f, clock.y, clock.x + 15f, clock.y, 5f)
    drawCircle(c.secondaryContainer, 25f, Offset(244.5f, 66.5f))
}

private fun DrawScope.promptVolume(c: ColorScheme, value: Int?, t: Float) {
    tile(41f, 46f, 253f, 124f, 41f, c.surfaceContainerHigh)
    drawCircle(c.primaryContainer, 39f, Offset(82f, 108f))
    val level = when (value) { 254 -> 1; 255 -> 2; 1 -> 4; 2 -> 5; else -> 3 }
    repeat(5) { index ->
        val x = 137f + index * 29f
        val h = 27f + index * 16f
        val top = 153f - h
        val build = phase(t, .10f + index * .07f, .42f + index * .07f)
        tile(x, top, 17f, h, 8.5f, c.surfaceContainerHighest)
        if (index < level && build > 0f) {
            val fill = h * build
            tile(x, 153f - fill, 17f, fill, minOf(8.5f, fill / 2f), c.primary)
        }
    }
}
