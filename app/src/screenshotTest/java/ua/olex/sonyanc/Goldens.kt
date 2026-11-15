package ua.olex.sonyanc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.fail
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Every render is compared with its golden (app/src/screenshotTest/goldens) at a quarter of
 * its size, so a moved, resized or recolored element fails the run while antialiasing does not.
 * New or intentionally changed screens: run once with -PrecordGoldens=true and commit the goldens.
 */
object Goldens {
    /** A pixel differs when a channel moves more than this (0 to 255). */
    private const val CHANNEL = 24
    /** A render fails when more than this share of its pixels differs. */
    private const val SHARE = 0.002

    fun check(name: String, render: Bitmap) {
        val dir = System.getProperty("goldenDir")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return
        val small = Bitmap.createScaledBitmap(render, max(1, render.width / 4), max(1, render.height / 4), true)
        val file = File(dir, "$name.png")
        if (System.getProperty("recordGoldens") == "true") {
            dir.mkdirs()
            file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        if (!file.exists()) fail("No golden for $name. Record it with -PrecordGoldens=true and commit it.")
        val golden = BitmapFactory.decodeFile(file.path)
        if (golden.width != small.width || golden.height != small.height)
            fail("$name is ${small.width}×${small.height}, its golden ${golden.width}×${golden.height}.")
        val a = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        val b = IntArray(a.size).also { golden.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
        val diff = IntArray(a.size)
        var differing = 0
        for (i in a.indices) {
            val d = maxOf(channel(a[i], 16, b[i]), channel(a[i], 8, b[i]), channel(a[i], 0, b[i]), channel(a[i], 24, b[i]))
            if (d > CHANNEL) { differing++; diff[i] = 0xFFFF00FF.toInt() } else diff[i] = (a[i] and 0x00FFFFFF) or 0x40000000
        }
        val share = differing.toDouble() / a.size
        if (share > SHARE) {
            val out = File(System.getProperty("screenshotDir")!!, "diff-$name.png")
            Bitmap.createBitmap(diff, small.width, small.height, Bitmap.Config.ARGB_8888)
                .let { bmp -> out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            fail("$name differs from its golden in %.2f %% of pixels (allowed %.2f %%); see ${out.name}.".format(share * 100, SHARE * 100))
        }
    }

    private fun channel(x: Int, shift: Int, y: Int) = abs(((x shr shift) and 255) - ((y shr shift) and 255))
}
