package ua.olex.sonyanc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * The launcher re-applies every widget update to the views it already shows. This does the same:
 * an identical update must not move the knob; tapping Ambient slides it from the first third of
 * the track to the middle one (the knob row's layout transition). Frames go to
 * build/screenshots/widget-anim-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h960dp-night-xxhdpi")
class WidgetAnimationTest {
    @Test fun theKnobSlidesOnlyOnARealModeChange() {
        assumeTrue(System.getProperty("screenshots") == "true")
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val ctx: android.content.Context = activity
        val dir = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        val d = ctx.resources.displayMetrics.density
        val w = (340 * d).toInt(); val h = (96 * d).toInt()
        val host = FrameLayout(ctx)
        val looper = shadowOf(Looper.getMainLooper())
        fun views(current: SonyProtocol.Mode, pending: SonyProtocol.Mode? = null) =
            ModeWidget.viewsFor(ctx, 340f, 96f, current, pending = pending, interactive = false, linked = true)
        val root = views(SonyProtocol.Mode.ANC).apply(ctx, host).also { host.addView(it, FrameLayout.LayoutParams(w, h)) }
        activity.setContentView(host)
        looper.idleFor(Duration.ofMillis(600))
        val row = root.findViewById<ViewGroup>(R.id.w_knob_row)
        val knob = root.findViewById<View>(R.id.w_knob_slot)
        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
        }
        fun frame(name: String) {
            layout()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(Color.parseColor("#0B1530")) }.also { host.draw(it) }
            File(dir, "widget-anim-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Goldens.check("widget-anim-$name", b)
        }
        layout()
        assertTrue("the knob row slides its children", row.layoutTransition != null)
        val third = row.width / 3f
        assertEquals("ANC: the knob fills the first third", 0f, knob.left.toFloat(), 2f)
        frame("0-anc")

        // The same update again (battery arrived, a refresh): nothing moves.
        views(SonyProtocol.Mode.ANC).reapply(ctx, root)
        assertFalse("an identical update starts no transition", row.layoutTransition.isRunning)

        // Tapping Ambient: the optimistic update moves the knob to the middle third.
        views(SonyProtocol.Mode.ANC, pending = SonyProtocol.Mode.AMBIENT).reapply(ctx, root)
        looper.idleFor(Duration.ofMillis(600))
        layout()
        assertEquals("Ambient: the knob sits in the middle third", third, knob.left.toFloat(), 2f)
        frame("1-ambient")

        // The earbuds confirm: the same state arrives again and nothing replays.
        views(SonyProtocol.Mode.AMBIENT).reapply(ctx, root)
        assertFalse("confirmation of the same mode starts no transition", row.layoutTransition.isRunning)
        frame("2-ambient")
    }
}
