package ua.olex.sonyanc

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Material 3 Expressive's soft shapes (cookie, sunny, clover) as one family: a circle with
 * [lobes] scallops [depth] deep, so a row's icon shape says what kind of setting it is without
 * another container: sound rows a sunny, connection rows a cookie, battery and routine rows a
 * clover. Pure geometry, no state, drawn once per size.
 */
internal object Scallop {
    fun path(cx: Float, cy: Float, radius: Float, lobes: Int, depth: Float, into: Path = Path(), points: Int = 96): Path {
        val r = radius / (1f + depth)
        for (i in 0 until points) {
            val t = (2 * Math.PI * i / points).toFloat()
            val rr = r * (1f + depth * cos(lobes * t))
            val x = cx + rr * sin(t); val y = cy - rr * cos(t)
            if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
        }
        into.close()
        return into
    }

    fun shape(lobes: Int, depth: Float) = GenericShape { size, _ ->
        addPath(path(size.width / 2f, size.height / 2f, min(size.width, size.height) / 2f, lobes, depth))
    }

    val sunny = shape(8, 0.06f)
    val cookie = shape(6, 0.08f)
    val clover = shape(4, 0.14f)
}
