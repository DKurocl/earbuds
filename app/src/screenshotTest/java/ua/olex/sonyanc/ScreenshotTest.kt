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
    private companion object { const val NOW = 1_762_700_000_000L } // a Sunday evening, fixed for stable renders

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
            speakToChat = true,
        )
    }

    /** Predictive back posed mid-gesture: the battery page shrinks away, Home waits underneath. */
    private fun backSwipe(name: String, pose: PagePose) = shoot(name) {
        PageLayers(listOf("HOME", "BATTERY"), pose) { page ->
            if (page == "HOME") HushScreen(
                status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC, level = 12), pending = null,
                fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78), granted = true, hasDevice = true,
                codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            ) else BatteryContent(weekOfUse(), NOW)
        }
    }

    @Test fun backSwipe30() = backSwipe("back-swipe-30", PagePose(swipe = { 0.3f }, dy = { 120f }))
    @Test fun backSwipe100() = backSwipe("back-swipe-100", PagePose(swipe = { 1f }, dy = { 400f }))
    /** Let go at 70 %: a quarter into the ending. */
    @Test fun backCommit() = backSwipe("back-commit", PagePose(swipe = { 0.7f }, settle = { 0.25f }))
    /** Opening a page, 40 % in. */
    @Test fun pageOpen() = backSwipe("page-open", PagePose(settle = { 0.4f }, opening = { true }))

    /** Accessibility: the largest font sizes Android offers must not clip or overlap. */
    @Config(fontScale = 2.0f)
    @Test fun homeLargeFont() = shoot("home-font-200") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78, caseCharging = true), granted = true, hasDevice = true,
            codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            speakToChat = true,
        )
    }

    @Config(fontScale = 2.0f)
    @Test fun settingsLargeFont() = shoot("page-settings-font-200") {
        EarbudSettingsContent(mapOf(), mapOf(DeviceSettingsProtocol.Feature.DSEE to true, DeviceSettingsProtocol.Feature.AUTO_PAUSE to false))
    }

    @Config(fontScale = 1.5f)
    @Test fun equalizerLargeFont() = shoot("page-equalizer-font-150") {
        EqualizerContent(EqualizerProtocol.State(0xA0, listOf(3, 4, 1, -2, 2, 6)), busy = false, unavailable = false)
    }

    @Test fun homeAmbient() = shoot("home-ambient") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, voice = true, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78, caseCharging = true), granted = true, hasDevice = true,
            codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            ambientUntil = System.currentTimeMillis() + 252_000L,
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

    private val settingsFixture = mapOf(
        AdvancedSettings.Key.CHAT to listOf(0, 1), AdvancedSettings.Key.CHAT_CONFIG to listOf(0, 1),
        AdvancedSettings.Key.SIDETONE to listOf(0, 7, 9), AdvancedSettings.Key.TOUCH to listOf(2, 0, 32),
        AdvancedSettings.Key.CYCLE to listOf(1, 53, 1, 0, 1), AdvancedSettings.Key.GESTURES to listOf(0),
        AdvancedSettings.Key.POWER to listOf(16, 0), AdvancedSettings.Key.QUALITY to listOf(0),
        AdvancedSettings.Key.GUIDANCE to listOf(0, 1), AdvancedSettings.Key.GUIDANCE_VOLUME to listOf(0),
    )

    @Config(qualifiers = "w448dp-h1500dp-night-xxhdpi")
    @Test fun earbudSettings() = shoot("page-settings") {
        EarbudSettingsContent(settingsFixture, DeviceSettingsProtocol.Feature.entries.associateWith { true })
    }

    @Config(qualifiers = "w448dp-h1100dp-night-xxhdpi")
    @Test fun equalizer() = shoot("page-equalizer") {
        EqualizerContent(EqualizerProtocol.State(0xA0, listOf(3, 4, 1, -2, 2, 6)), busy = false, unavailable = false)
    }

    @Config(qualifiers = "w448dp-h960dp-night-xxhdpi")
    @Test fun batteryPage() = shoot("page-battery") {
        BatteryContent(weekOfUse(), NOW, health = listOf(WeekHealth(2800, 430, 9), WeekHealth(2801, 425, 12), WeekHealth(2820, 372, 11)))
    }

    @Config(qualifiers = "w448dp-h960dp-notnight-xxhdpi")
    @Test fun batteryPageLight() = shoot("page-battery-light") { BatteryContent(weekOfUse(), NOW) }

    @Test fun batteryPageEmpty() = shoot("page-battery-empty") { BatteryContent(emptyList(), NOW) }

    /** Fixture: seven days, two or three out-of-case sessions a day, the case slowly emptying. */
    private fun weekOfUse(): List<BatterySample> {
        val min = 60_000L
        val out = mutableListOf<BatterySample>()
        var session = 0
        var case = 95
        for (day in 6 downTo 0) {
            for ((startH, hours) in listOf(8 to 2, 13 to 1, 19 to 3).take(if (day % 2 == 0) 3 else 2)) {
                session++
                val start = NOW - day * 24 * 60 * min - (24 - startH) * 60 * min
                var level = 100
                for (m in 0..hours * 60 step 15) {
                    out += BatterySample(start + m * min, level.coerceAtLeast(5), false, SonyProtocol.Mode.ANC, session, case)
                    level -= 4
                }
                case = (case - 9).coerceAtLeast(12)
            }
            if (day == 3) case = 100 // case charged
        }
        return out.filter { it.at <= NOW }.sortedBy { it.at }
    }

    @Test fun deviceInfo() = shoot("page-info") {
        DeviceInfoContent("WF-1000XM5", Headphones.Status.CONNECTED, CodecProtocol.State(0x02), "2.0.1", refreshing = false, ldacBlocked = true)
    }

    @Config(qualifiers = "w448dp-h1200dp-night-xxhdpi")
    @Test fun appSettings() = shoot("page-app") {
        AppSettingsContent(
            listOf(PairedDevice("00:11", "WF-1000XM5")), "00:11", "WF-1000XM5", dynamicColor = true, haptics = true,
            canAddTile = true, canPinWidget = true, version = "1.2.0",
        )
    }

    @Test fun diagnostics() = shoot("page-diagnostics") {
        DiagnosticsContent(listOf("✓ WF-1000XM5 · secure V2", "→ [0C] 66 17", "← [0C] 67 17 01 01 00 00 14", "→ [0C] 12 02", "← [0C] 13 02 10"))
    }

    @Test fun permission() = shoot("home-permission") {
        HushScreen(
            status = Headphones.Status.DISCONNECTED, nc = null, pending = null, fresh = false, error = null, battery = null,
            granted = false, hasDevice = false, onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
        )
    }

    @Config(qualifiers = "w448dp-h960dp-notnight-xxhdpi")
    @Test fun homeLight() = shoot("home-light") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 18, caseCharging = false), granted = true, hasDevice = true,
            codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
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
