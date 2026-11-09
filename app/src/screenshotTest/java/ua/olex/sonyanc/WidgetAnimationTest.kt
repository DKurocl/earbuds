package ua.olex.sonyanc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ViewFlipper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The launcher re-applies a widget update to the views it already shows. This does the same:
 * ANC first, then Ambient reapplied in place: the highlight moves to the tapped mode.
 * Frames go to build/screenshots/widget-anim-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h960dp-night-xxhdpi")
class WidgetAnimationTest {
    private fun flippers(v: View): List<ViewFlipper> = when (v) {
        is ViewFlipper -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { flippers(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun modeChangeAnimatesInPlace() {
        assumeTrue(System.getProperty("screenshots") == "true")
        // Hosted in a real window, as the launcher hosts widgets (ViewAnimator animates only when attached).
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val ctx: android.content.Context = activity
        val dir = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        val d = ctx.resources.displayMetrics.density
        val w = (340 * d).toInt(); val h = (96 * d).toInt()
        val host = FrameLayout(ctx)
        val first = ModeWidget.viewsFor(ctx, 340f, 96f, SonyProtocol.Mode.ANC, interactive = false, linked = true)
        val root = first.apply(ctx, host).also { host.addView(it, FrameLayout.LayoutParams(w, h)) }
        activity.setContentView(host)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600)) // let the first-appearance pop finish
        fun frame(name: String) {
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(Color.parseColor("#0B1530")) }.also { host.draw(it) }
            File(dir, "widget-anim-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        frame("00")
        assertEquals(listOf(1, 0, 0), flippers(root).map { it.displayedChild })

        // The update the launcher would receive after tapping Ambient: reapplied, not re-inflated.
        ModeWidget.viewsFor(ctx, 340f, 96f, SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT, interactive = false, linked = true)
            .reapply(ctx, root)
        val f = flippers(root)
        assertEquals(listOf(0, 1, 0), f.map { it.displayedChild })
        frame("01")
        // Robolectric replaces ViewAnimator with a shadow that switches without animating, so
        // the animation itself is asserted on the emulator (androidTest WidgetAnimationUiTest).
    }
}
