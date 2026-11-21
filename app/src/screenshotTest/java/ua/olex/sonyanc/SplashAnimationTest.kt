package ua.olex.sonyanc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AnimatedVectorDrawable
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The splash icon (docs/icon/brand_icon.py) as Android shows it: the launcher icon in its white circle,
 * frame by frame. A strip (splash-anim.png, compared like every render) and every 20 ms in
 * build/screenshots/splashvid for a video.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h960dp-night-xxhdpi")
class SplashAnimationTest {
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            c = c.superclass
        }
        error("no $name on ${o.javaClass}")
    }

    @Test fun splashFrames() {
        assumeTrue(System.getProperty("screenshots") == "true")
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val avd = ctx.getDrawable(R.drawable.splash_icon) as AnimatedVectorDrawable
        AnimatedVectorDrawable::class.java.getDeclaredMethod("forceAnimationOnUI").apply { isAccessible = true }.invoke(avd)
        avd.start()
        val set = field(field(avd, "mAnimatorSet")!!, "mSet") as android.animation.AnimatorSet
        val cell = 360
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        fun draw(canvas: Canvas, x: Int, at: Long) {
            set.currentPlayTime = at.coerceAtMost(set.totalDuration)
            // The icon's circle is two thirds of its square, as on the splash.
            canvas.drawCircle(x + cell / 2f, cell / 2f, cell / 3f, white)
            avd.setBounds(x, 0, x + cell, cell)
            avd.draw(canvas)
        }
        val dir = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        val times = listOf(0L, 100L, 200L, 260L, 320L, 400L, 470L, 520L, 600L, 650L)
        val strip = Bitmap.createBitmap(cell * times.size, cell, Bitmap.Config.ARGB_8888)
        Canvas(strip).apply { drawColor(Color.parseColor("#1B1B1F")) }.let { c -> times.forEachIndexed { i, t -> draw(c, i * cell, t) } }
        File(dir, "splash-anim.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Goldens.check("splash-anim", strip)
        val vid = File(dir, "splashvid").apply { mkdirs() }
        for (t in 0L..650L step 20L) {
            val b = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
            draw(Canvas(b), 0, t)
            File(vid, "%04d.png".format(t)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
