package ua.olex.sonyanc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
        Goldens.check(name, bitmap)
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { AppTheme(dynamic = false) { content() } }
        compose.waitForIdle()
        save(name)
    }

    @Test fun darkPaletteContrast() = checkPaletteContrast()

    @Test
    @Config(qualifiers = "+notnight")
    fun lightPaletteContrast() = checkPaletteContrast()

    private fun checkPaletteContrast() {
        val dynamic = mutableStateOf(false)
        var checkAppColors = {}
        compose.setContent {
            AppTheme(dynamic = dynamic.value) {
                val colors = MaterialTheme.colorScheme
                val battery = batteryIndicator
                SideEffect {
                    checkAppColors = {
                        assertContrast("Page text", colors.onSurface, colors.surface, 4.5f)
                        assertContrast("Setting summary", colors.onSurfaceVariant, colors.surfaceContainerHigh, 4.5f)
                        assertContrast("Section label", colors.primary, colors.surface, 4.5f)
                        assertContrast("Selected control", colors.onPrimary, colors.primary, 4.5f)
                        assertContrast("Timer label", colors.onTertiaryContainer, colors.tertiaryContainer, 4.5f)
                        assertContrast("Battery symbol", battery.second, battery.first, 3f)
                    }
                }
            }
        }
        compose.runOnIdle { checkAppColors(); dynamic.value = true }
        compose.runOnIdle { checkAppColors() }

        val ctx = compose.activity
        assertContrast("Selected widget segment", Color(ctx.getColor(R.color.w_bar_on_text)), Color(ctx.getColor(R.color.w_bar_on)), 4.5f)
        assertContrast("Battery widget value", Color(ctx.getColor(R.color.w_off_text)), Color(ctx.getColor(R.color.w_bg)), 4.5f)
        assertContrast("Battery widget low value", Color(ctx.getColor(R.color.w_low)), Color(ctx.getColor(R.color.w_bg)), 4.5f)
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
        val fg = foreground.luminance()
        val bg = background.luminance()
        val ratio = (maxOf(fg, bg) + 0.05f) / (minOf(fg, bg) + 0.05f)
        assertTrue("$label contrast: $ratio, expected at least $minimum", ratio >= minimum)
    }

    @Test fun batteryFillUpdatesDrawingWithoutRecomposing() {
        fun commits() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dynamic = false) {
                Batteries(SonyProtocol.Battery(100, 80, 90), setOf(0, 1, 2), true, {})
            }
        }
        compose.mainClock.advanceTimeBy(64)
        val before = compose.runOnIdle { commits() }
        compose.mainClock.advanceTimeBy(96)
        assertEquals("Composition changes during fill", before, compose.runOnIdle { commits() })
    }

    @Test fun equalizerChangesTheBandBeforeWritingItOnRelease() {
        val writes = mutableListOf<List<Int>>()
        val live = mutableStateOf(EqualizerProtocol.State(0xA0, List(6) { 0 }))
        compose.setContent {
            AppTheme(dynamic = false) {
                EqualizerContent(
                    state = live.value,
                    busy = false,
                    unavailable = false,
                    onBands = { writes.add(it) },
                )
            }
        }
        val curve = compose.onNodeWithContentDescription("Equalizer curve")
        curve.performTouchInput {
            down(Offset(width * .967f, centerY))
            moveBy(Offset(0f, -height * .28f))
        }
        compose.onNode(SemanticsMatcher("positive 16 kHz gain") {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.startsWith("16 kHz, +") }
        }).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(writes.isEmpty())
            live.value = EqualizerProtocol.State(0xA0, listOf(3, 0, 0, 0, 0, 0))
        }
        curve.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertTrue(writes.single().last() > 0)
            assertEquals(3, writes.single().first())
        }
    }

    @Test fun backgroundReadsDoNotShowTheManualRefreshIndicator() {
        val background = mutableStateOf(false)
        val manual = mutableStateOf(false)
        compose.setContent {
            AppTheme(dynamic = false) {
                HushScreen(
                    status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC),
                    pending = null, fresh = true, error = null, battery = SonyProtocol.Battery(80, 80, 70),
                    granted = true, hasDevice = true, batteryRefreshing = background.value, manualRefreshing = manual.value,
                    onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
                )
            }
        }
        fun topCenterPixels(): IntArray = compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val width = root.width / 4
            val height = root.height / 4
            IntArray(width * height).also {
                bitmap.getPixels(it, 0, width, (root.width - width) / 2, 0, width, height)
                bitmap.recycle()
            }
        }

        val indicator = compose.onNodeWithContentDescription("Refreshing earbuds")
        val idle = topCenterPixels()
        repeat(2) {
            compose.runOnIdle { background.value = true }
            assertTrue("A background read changed the top-center pixels", idle.contentEquals(topCenterPixels()))
            compose.runOnIdle { background.value = false }
            assertTrue("Completing a background read changed the top-center pixels", idle.contentEquals(topCenterPixels()))
        }
        indicator.assertDoesNotExist()
        compose.onNodeWithText("Presets and Clear Bass").assertExists()
        compose.onNodeWithText("Codec and firmware").assertExists()

        compose.runOnIdle { manual.value = true }
        indicator.assertIsDisplayed()
        assertTrue("Manual refresh did not draw in the top center", !idle.contentEquals(topCenterPixels()))

        compose.runOnIdle { manual.value = false }
        indicator.assertDoesNotExist()
        assertTrue("The refresh indicator remained after completion", idle.contentEquals(topCenterPixels()))
    }

    @Test fun modeGlyphsStayOpaqueThroughoutContourMotion() {
        val request = mutableStateOf<Int?>(null)
        compose.setContent {
            AppTheme(dynamic = false) {
                Row {
                    SonyProtocol.Mode.entries.forEach { mode ->
                        ModeGlyph(mode, request.value, selected = true, color = Color.White)
                    }
                }
            }
        }
        fun icons(pose: String? = null): List<IntArray> = compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val side = with(compose.density) { 24.dp.roundToPx() }
            val bitmap = Bitmap.createBitmap(side * 3, side, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            if (pose != null) {
                val directory = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
                File(directory, "mode-glyphs-$pose.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            List(3) { index ->
                IntArray(side * side).also { bitmap.getPixels(it, 0, side, index * side, 0, side, side) }
            }.also { bitmap.recycle() }
        }
        val rest = icons("rest")
        assertTrue(rest.all { pixels -> pixels.any { it == android.graphics.Color.WHITE } })
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { request.value = 1 }
        var attack = rest
        var release = rest
        repeat(24) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val pose = when (frame) {
                10 -> "attack"
                16 -> "release"
                else -> null
            }
            val pixels = icons(pose)
            pixels.forEachIndexed { index, icon ->
                assertTrue("Frame $frame dims ${SonyProtocol.Mode.entries[index]}",
                    icon.any { it == android.graphics.Color.WHITE })
            }
            if (frame == 10) attack = pixels
            if (frame == 16) release = pixels
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        icons()
        val settled = icons("settled")
        SonyProtocol.Mode.entries.forEachIndexed { i, mode ->
            assertTrue("$mode should move", !rest[i].contentEquals(attack[i]))
            assertTrue("$mode should keep moving", !attack[i].contentEquals(release[i]))
            assertTrue("$mode should settle exactly into its original glyph", rest[i].contentEquals(settled[i]))
        }
    }

    @Test fun noiseControlStretchesInwardAndWaitsForConfirmation() {
        val anc = SonyProtocol.NcState(SonyProtocol.Mode.ANC)
        val confirmed = mutableStateOf(anc)
        val pending = mutableStateOf<SonyProtocol.NcState?>(null)
        val requested = mutableListOf<SonyProtocol.NcState>()
        compose.setContent {
            AppTheme(dynamic = false) {
                HushScreen(
                    status = Headphones.Status.CONNECTED, nc = confirmed.value,
                    pending = pending.value, fresh = true, error = null,
                    battery = SonyProtocol.Battery(80, 80, 70), granted = true, hasDevice = true,
                    onMode = { requested.add(it); pending.value = it },
                    onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
                )
            }
        }

        val ancButton = compose.onNodeWithContentDescription(SonyProtocol.Mode.ANC.label)
        val offButton = compose.onNodeWithContentDescription(SonyProtocol.Mode.OFF.label)
        val ambientButton = compose.onNodeWithContentDescription(SonyProtocol.Mode.AMBIENT.label)
        fun dockBounds() = listOf(ancButton, ambientButton, offButton).map { it.getBoundsInRoot() }
        val initialButtons = dockBounds()
        val more = compose.onNodeWithText("More controls")
        val initialMore = more.getBoundsInRoot().top

        fun assertStretch(index: Int) {
            val pressed = dockBounds()
            val before = initialButtons[index]
            val after = pressed[index]
            val growth = (after.right - after.left) - (before.right - before.left)
            assertTrue("Expected a restrained stretch, got $growth", growth > 1.dp && growth <= 9.dp)
            assertEquals(initialButtons.first().left, pressed.first().left)
            assertEquals(initialButtons.last().right, pressed.last().right)
            pressed.zip(initialButtons).forEach { (current, initial) ->
                assertEquals(initial.top, current.top)
                assertEquals(initial.bottom, current.bottom)
            }
            assertTrue("Ambient needs room to keep its full label", pressed[1].right - pressed[1].left >= 104.dp)
        }

        ancButton.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(120)
        assertStretch(0)
        ancButton.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(350)
        assertEquals(initialButtons, dockBounds())

        offButton.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(120)
        assertStretch(2)
        offButton.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(350)
        assertEquals(initialButtons, dockBounds())
        ancButton.assertIsSelected()
        offButton.assertIsNotSelected()
        assertEquals("Applying", offButton.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(initialMore, more.getBoundsInRoot().top)
        compose.runOnIdle {
            assertEquals(listOf(SonyProtocol.Mode.OFF), requested.map { it.mode })
        }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            confirmed.value = requested.single()
            pending.value = null
        }
        val positions = List(48) {
            compose.mainClock.advanceTimeByFrame()
            more.getBoundsInRoot().top.value
        }
        offButton.assertIsSelected()
        assertEquals(initialButtons, dockBounds())
        assertTrue("Options should collapse after the earbuds confirm Off", positions.last() < initialMore.value)
        val frameSteps = (listOf(initialMore.value) + positions).zipWithNext { a, b -> kotlin.math.abs(a - b) }
        assertTrue("The mode card lost a full section gap in a single frame: $frameSteps",
            frameSteps.all { it < UiTokens.homeSectionGap.value })
        assertTrue("The collapse should have visible intermediate positions", positions.distinct().size > 5)
    }

    @Test fun floatingModesLeaveSpaceToOpenTheLastRow() {
        var opened = false
        var dockColor = 0
        compose.setContent {
            AppTheme(dynamic = false) {
                val color = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                SideEffect { dockColor = color }
                HushScreen(
                    status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT),
                    pending = null, fresh = true, error = null, battery = SonyProtocol.Battery(80, 80, 70),
                    granted = true, hasDevice = true, onConnection = { opened = true },
                    onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
                )
            }
        }
        val viewport = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val ambient = compose.onNodeWithContentDescription(SonyProtocol.Mode.AMBIENT.label)
        val button = ambient.getBoundsInRoot()
        val off = compose.onNodeWithContentDescription(SonyProtocol.Mode.OFF.label).getBoundsInRoot()
        val gapX = with(compose.density) { ((button.right + off.left) / 2).roundToPx() }
        val gapY = with(compose.density) { ((button.top + button.bottom) / 2).roundToPx() }
        compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            assertEquals("The shared dock should cover the page between mode buttons", dockColor, bitmap.getPixel(gapX, gapY))
            bitmap.recycle()
        }
        assertTrue("The scroll viewport should continue behind the mode buttons", viewport.getBoundsInRoot().bottom > button.bottom)
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        val deviceInfo = compose.onNodeWithContentDescription("Device info").assertIsDisplayed()
        assertTrue("The last row should clear the mode buttons", deviceInfo.getBoundsInRoot().bottom < button.top)
        assertEquals(button, ambient.getBoundsInRoot())
        deviceInfo.performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun home() = shoot("home") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78, caseCharging = false), granted = true, hasDevice = true,
            onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {}, onProfiles = {},
            speakToChat = true,
        )
    }

    @Test fun ambientControlsStayPutWhenTheControlLinkReconnects() {
        val status = mutableStateOf(Headphones.Status.CONNECTED)
        val batteryParts = mutableStateOf(setOf(0, 1, 2))
        compose.setContent {
            AppTheme(dynamic = false) {
                HushScreen(
                    status = status.value, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, level = 20),
                    pending = null, fresh = status.value == Headphones.Status.CONNECTED, error = null,
                    battery = SonyProtocol.Battery(28, 28, 91), batteryParts = batteryParts.value,
                    batterySeenAt = NOW, linked = true, granted = true, hasDevice = true,
                    onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
                )
            }
        }
        val find = compose.onNodeWithText("Find").getBoundsInRoot()
        val ambient = compose.onNodeWithText("Ambient level").getBoundsInRoot()
        compose.runOnIdle {
            status.value = Headphones.Status.CONNECTING
            batteryParts.value = emptySet()
        }
        assertEquals(find, compose.onNodeWithText("Find").getBoundsInRoot())
        assertEquals(ambient, compose.onNodeWithText("Ambient level").getBoundsInRoot())
        compose.runOnIdle {
            status.value = Headphones.Status.CONNECTED
            batteryParts.value = setOf(0, 1, 2)
        }
        assertEquals(ambient, compose.onNodeWithText("Ambient level").getBoundsInRoot())
    }

    /** Predictive back posed mid-gesture: the battery page shrinks away, Home waits underneath. */
    private fun backSwipe(name: String, pose: PagePose) = shoot(name) {
        PageLayers(listOf("HOME", "BATTERY"), pose) { page ->
            if (page == "HOME") HushScreen(
                status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC, level = 12), pending = null,
                fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78), granted = true, hasDevice = true,
                onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            ) else BatteryContent(weekOfUse(), NOW)
        }
    }

    @Test fun backSwipe30() = backSwipe("back-swipe-30", PagePose(swipe = { 0.3f }, dy = { 120f }))
    @Test fun backSwipe100() = backSwipe("back-swipe-100", PagePose(swipe = { 1f }, dy = { 400f }))
    /** Let go at 70 %: a quarter into the ending. */
    @Test fun backCommit() = backSwipe("back-commit", PagePose(swipe = { 0.7f }, settle = { 0.25f }))
    /** Opening a page, 40 % in. */
    @Test fun pageOpen() = backSwipe("page-open", PagePose(settle = { 0.4f }, opening = { true }))

    /** The "Listening left" tile on the 448 × 960 dp render (px), where Battery grows from. */
    private val tile = androidx.compose.ui.geometry.Rect(48f, 915f, 660f, 1305f)
    /** Container transform: Battery grows out of the tile, 30 % and 70 % in. */
    @Test fun cardOpen30() = backSwipe("card-open-30", PagePose(settle = { 0.3f }, opening = { true }, origin = { tile }))
    @Test fun cardOpen70() = backSwipe("card-open-70", PagePose(settle = { 0.7f }, opening = { true }, origin = { tile }))
    /** Back swipe let go at 60 %: the page returns into the tile, halfway. */
    @Test fun cardClose() = backSwipe("card-close", PagePose(swipe = { 0.6f }, settle = { 0.5f }, origin = { tile }))

    /** Accessibility: the largest font sizes Android offers must not clip or overlap. */
    @Config(fontScale = 2.0f)
    @Test fun homeLargeFont() = shoot("home-font-200") {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, level = 12), pending = null,
            fresh = true, error = null, battery = SonyProtocol.Battery(69, 70, 78, caseCharging = true), granted = true, hasDevice = true,
            onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
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
            onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            ambientUntil = System.currentTimeMillis() + 252_000L,
        )
    }

    @Test fun homeEdgeCases() = shoot("home-edge") {
        HushScreen(
            status = Headphones.Status.DISCONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, level = 12), pending = null,
            fresh = false, error = null, battery = SonyProtocol.Battery(15, 100, null, rightCharging = true), granted = true, hasDevice = true,
            batteryParts = setOf(0), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            linked = false, lastSeen = System.currentTimeMillis() - 25 * 60_000L, batterySeenAt = System.currentTimeMillis() - 25 * 60_000L,
        )
    }

    private val settingsFixture = mapOf(
        AdvancedSettings.Key.CHAT to listOf(0, 1), AdvancedSettings.Key.CHAT_CONFIG to listOf(0, 1),
        AdvancedSettings.Key.SIDETONE to listOf(0, 7, 9), AdvancedSettings.Key.TOUCH to listOf(2, 0, 32), AdvancedSettings.Key.PANEL to listOf(0, 0),
        AdvancedSettings.Key.CYCLE to listOf(1, 53, 1, 0, 1), AdvancedSettings.Key.GESTURES to listOf(0),
        AdvancedSettings.Key.POWER to listOf(16, 0), AdvancedSettings.Key.QUALITY to listOf(0),
        AdvancedSettings.Key.GUIDANCE to listOf(0, 1), AdvancedSettings.Key.GUIDANCE_VOLUME to listOf(0),
    )

    @Config(qualifiers = "w448dp-h1500dp-night-xxhdpi")
    /** Every setting's illustration, at the moment of its loop that explains it on its own. */

    /** The sheet that comes up when the earbuds connect, over another app. */
    @Test fun connectedSheet() = shoot("connected-sheet") {
        Box(Modifier.fillMaxSize().background(Color(0xFF3B4A5C))) {
            ConnectedSheetContent(
                "WF-1000XM5", Headphones.Status.CONNECTED, SonyProtocol.Battery(82, 79, 56, caseCharging = true), setOf(0, 1, 2), shown = 1f, canResume = true,
            )
        }
    }

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

    @Test fun cachedBatteryPageIdentifiesTheLastReading() {
        compose.setContent {
            AppTheme(dynamic = false) {
                BatteryContent(emptyList(), NOW, device = DeviceState(battery = SonyProtocol.Battery(20, 85, 40)))
            }
        }
        compose.onNodeWithText("Last reading").assertIsDisplayed()
    }

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
                    out += BatterySample(start + m * min, level.coerceAtLeast(5), false, SonyProtocol.Mode.ANC, session, case,
                        left = (level + 6).coerceIn(5, 100), right = level.coerceAtLeast(5))
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
            canAddTile = true, canPinWidget = true, version = "2.1.0",
            weekendMode = OnConnect.AMBIENT, onProfiles = {}, onBackup = {},
        )
    }

    @Test fun diagnostics() = shoot("page-diagnostics") {
        DiagnosticsContent(listOf("✓ WF-1000XM5 · secure V2", "→ [0C] 66 17", "← [0C] 67 17 01 01 00 00 14", "→ [0C] 12 02", "← [0C] 13 02 10"),
            result = "Control link ready · Noise control confirmed · LDAC · 3/3 battery levels live", onCheck = {},
            timings = mapOf(CommandScheduler.Priority.CONTROL to listOf(CommandScheduler.Timing(22, 104))))
    }

    @Test fun profiles() = shoot("page-profiles") {
        PersonalProfilesContent(
            mapOf(1 to PersonalProfile(SonyProtocol.NcState(SonyProtocol.Mode.ANC),
                EqualizerProtocol.State(0xA0, listOf(1, 2, 0, 0, -1, 0)),
                mapOf(DeviceSettingsProtocol.Feature.DSEE to true, DeviceSettingsProtocol.Feature.AUTO_PAUSE to false))),
            DeviceState(ncConfirmed = true), false, {}, {}, { _, _ -> },
        )
    }

    @Test fun backup() = shoot("page-backup") { BackupContent({}, {}, {}) }

    @Test fun vibrations() = shoot("page-vibrations") {
        VibrationsContent(Haptics.Cue.entries.associateWith { if (it == Haptics.Cue.AMBIENT) 1 else 0 }, shaped = true)
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
            onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
        )
    }

    private fun gallery(name: String, battery: Boolean) = shoot(name) {
        val sizes = if (battery)
            listOf(252 to 104, 164 to 104, 84 to 104, 340 to 208, 252 to 150, 252 to 80)
        // 322 x 105: the owner's slot beside the media player on their Pixel 10 Pro XL (measured from
        // their screenshot); 297 x 97: four cells, measured earlier.
        else listOf(322 to 105, 424 to 96, 340 to 96, 297 to 97, 252 to 96, 164 to 96, 340 to 56, 164 to 208, 300 to 150, 200 to 64)
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
                            clock(), linked = i != 4, lastSeen = clock() - 3_600_000, interactive = false,
                        ) else ModeWidget.viewsFor(ctx, w.toFloat(), h.toFloat(), SonyProtocol.Mode.entries[i % 3],
                            pending = if (i == 2) SonyProtocol.Mode.ANC else null, interactive = false, linked = i != 6)
                        addView(views.apply(ctx, this), FrameLayout.LayoutParams(-1, -1))
                    }
                }, modifier = Modifier.size(w.dp, h.dp))
            }
        }
    }

    /** Widgets print the reading's clock time ("Updated 2:00 PM"): always today at 14:00, so goldens never drift with the hour. */
    private fun clock() = java.time.LocalDate.now().atTime(14, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Config(qualifiers = "w448dp-h960dp-night-xxhdpi")
    @Test fun statusWidgets() = shoot("widgets-status") {
        Column(Modifier.background(Color(0xFF0B1530)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(SonyProtocol.Mode.ANC to true, SonyProtocol.Mode.AMBIENT to false).forEach { (mode, linked) ->
                AndroidView(factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val views = StatusWidget.viewsFor(ctx, mode, SonyProtocol.Battery(64, 71, 40, rightCharging = !linked) to clock(), linked, interactive = false)
                        addView(views.apply(ctx, this), FrameLayout.LayoutParams(-1, -1))
                    }
                }, modifier = Modifier.size(180.dp, 180.dp))
            }
        }
    }

    @Config(qualifiers = "w448dp-h960dp-night-xxhdpi")
    @Test fun dashboards() = shoot("widgets-dashboard") {
        val now = clock()
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

    @Config(qualifiers = "w448dp-h1440dp-night-xxhdpi")
    @Test fun modeWidgets() = gallery("widgets-modes", battery = false)
    @Config(qualifiers = "w448dp-h1100dp-night-xxhdpi")
    @Test fun batteryWidgets() = gallery("widgets-battery", battery = true)
}

