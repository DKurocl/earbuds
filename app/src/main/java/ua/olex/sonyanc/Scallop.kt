package ua.olex.sonyanc

import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Expressive's soft cookie shape: a circle with [lobes] scallops [depth] deep. Used
 * for accents only (the equalizer's held band); rows keep Android's plain icon shapes.
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
}
