package ua.olex.sonyanc

import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders screens and widgets to PNG for visual review on the JVM (no emulator).
 * Run with: gradle :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots=true
 * Output: app/build/screenshots. Skipped in normal test runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h960dp-night-xxhdpi")
class ScreenshotTest {
    // The opt-in check runs before the activity starts (release unit tests have no test activity).
    @get:Rule(order = 0) val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("screenshots") == "true"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val dir = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        // Draw the view tree directly; PixelCopy-based capture does not run under Robolectric.
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { AppTheme(dynamic = false) { content() } }
        compose.waitForIdle()
        save(name)
    }

    @Test fun home() = shoot("home") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78, caseCharging = false), granted = true, hasDevice = true,
            codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            scenes = Scenes.defaults,
        )
    }

    @Test fun homeAmbient() = shoot("home-ambient") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, voice = true, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78, caseCharging = true), granted = true, hasDevice = true,
            codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            scenes = Scenes.defaults,
        )
    }

    @Test fun homeEdgeCases() = shoot("home-edge") {
        HushScreen(
            status = Headphones.Status.DISCONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, level = 12), pending = null,
            fresh = false, error = null, battery = SonyProtocol.Battery(15, 100, null, rightCharging = true), granted = true, hasDevice = true,
            batteryParts = setOf(0), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            lastSeen = System.currentTimeMillis() - 25 * 60_000L, batterySeenAt = System.currentTimeMillis() - 25 * 60_000L,
        )
    }

    private fun gallery(name: String, battery: Boolean) = shoot(name) {
        val sizes = if (battery)
            listOf(252 to 104, 164 to 104, 84 to 104, 340 to 208, 252 to 150, 252 to 80)
        else listOf(424 to 96, 340 to 96, 252 to 96, 164 to 96, 340 to 56, 164 to 208, 300 to 150, 200 to 64)
        Column(
            Modifier.background(Color(0xFF101418)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sizes.forEachIndexed { i, (w, h) ->
                Text("$w × $h dp", color = Color(0xFFCAD1E4), style = MaterialTheme.typography.labelMedium)
                AndroidView(factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val views = if (battery) BatteryWidget.viewsFor(
                            ctx, w.toFloat(), h.toFloat(),
                            if (i == 3) SonyProtocol.Battery(18, 64, null, rightCharging = true) else SonyProtocol.Battery(82, 79, 56, caseCharging = true),
                            System.currentTimeMillis(), linked = i != 4, lastSeen = System.currentTimeMillis() - 3_600_000, interactive = false,
                        ) else ModeWidget.viewsFor(ctx, w.toFloat(), h.toFloat(), SonyProtocol.Mode.entries[i % 3],
                            pending = if (i == 2) SonyProtocol.Mode.ANC else null, interactive = false, linked = i != 6)
                        addView(views.apply(ctx, this), FrameLayout.LayoutParams(-1, -1))
                    }
                }, modifier = Modifier.size(w.dp, h.dp))
            }
        }
    }

    @Config(qualifiers = "w448dp-h960dp-night-xxhdpi")
    @Test fun dashboards() = shoot("widgets-dashboard") {
        val now = System.currentTimeMillis()
        Column(Modifier.background(Color(0xFF0B1530)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple(416, 275, true), Triple(340, 208, false), Triple(416, 230, null)).forEach { (w, h, linked) ->
                Text("$w × $h dp", color = Color(0xFFCAD1E4), style = MaterialTheme.typography.labelMedium)
                AndroidView(factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val views = ModeWidget.viewsFor(
                            ctx, w.toFloat(), h.toFloat(), SonyProtocol.Mode.AMBIENT, interactive = false, linked = linked,
                            battery = (if (linked == false) SonyProtocol.Battery(64, 61, 40) else SonyProtocol.Battery(69, 70, 78, caseCharging = true)) to now,
                            scenes = Scenes.defaults,
                        )
                        addView(views.apply(ctx, this), FrameLayout.LayoutParams(-1, -1))
                    }
                }, modifier = Modifier.size(w.dp, h.dp))
            }
        }
    }

    @Config(qualifiers = "w448dp-h1300dp-night-xxhdpi")
    @Test fun modeWidgets() = gallery("widgets-modes", battery = false)
    @Config(qualifiers = "w448dp-h1100dp-night-xxhdpi")
    @Test fun batteryWidgets() = gallery("widgets-battery", battery = true)
}
