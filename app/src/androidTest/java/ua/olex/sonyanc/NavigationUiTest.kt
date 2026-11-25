package ua.olex.sonyanc

import android.animation.ValueAnimator
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.lifecycle.Lifecycle
import androidx.activity.BackEventCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

class NavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun productionGearOpensAppSettingsInOneTap() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    private fun swipeBack(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) = compose.runOnUiThread {
        val back = compose.activity.onBackPressedDispatcher
        val width = compose.activity.window.decorView.width.toFloat()
        val from = if (edge == BackEventCompat.EDGE_LEFT) 8f else width - 8f
        val to = if (edge == BackEventCompat.EDGE_LEFT) 240f else width - 240f
        back.dispatchOnBackStarted(BackEventCompat(from, 900f, 0f, edge))
        back.dispatchOnBackProgressed(BackEventCompat(to, 980f, progress, edge))
    }

    @Test fun cancelledBackSwipePutsThePageBack() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
        val before = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        swipeBack(0.6f)
        compose.waitForIdle()
        // Mid-swipe the page has shrunk away from where it was.
        assertTrue(compose.onNodeWithContentDescription("Back").getBoundsInRoot().left > before.left)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        val after = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        assertEquals(before.left.value, after.left.value, 0.5f)
        assertEquals(before.top.value, after.top.value, 0.5f)
        compose.onNodeWithText("Name").assertIsDisplayed()
    }

    @Test fun finishedBackSwipeReturnsHome() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
        swipeBack(0.5f, BackEventCompat.EDGE_RIGHT)
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun leavingTheAppStopsConnectionOwnership() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue("Connection or polling still owned after onStop",Headphones.idle)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun settingsKeepsItsScrollPositionAfterResumeAndRecreation() {
        val scenario = compose.activityRule.scenario
        compose.onNodeWithContentDescription("Settings").performClick()
        val row = compose.onNodeWithText("Wallpaper colors")
        row.performScrollTo().assertIsDisplayed()
        val viewport = compose.onNode(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange),
        )
        fun scrollRange() = viewport.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        ].run { value() to maxValue() }
        val before = row.getBoundsInRoot()
        val beforeViewport = viewport.getBoundsInRoot()
        val beforeRange = scrollRange()
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        row.assertIsDisplayed()
        assertEquals(before.top.value, row.getBoundsInRoot().top.value, 0.5f)

        scenario.recreate()
        row.assertIsDisplayed()
        assertEquals(beforeRange, scrollRange())
        // Activity recreation can attach the new scroll viewport at a different root offset.
        // Compare the row within the viewport while keeping an absolute check for normal resume.
        val afterViewport = viewport.getBoundsInRoot()
        assertEquals(
            "Scroll viewport before $beforeViewport and after $afterViewport",
            before.top.value - beforeViewport.top.value,
            row.getBoundsInRoot().top.value - afterViewport.top.value,
            0.5f,
        )
    }

    @Test fun predictiveBackKeepsThePageStillWhenSystemAnimationsAreDisabled() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

        val oldScale = shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale 0")
            compose.waitUntil(5_000) { !ValueAnimator.areAnimatorsEnabled() }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Name").assertIsDisplayed()
            val before = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
            swipeBack(0.6f)
            compose.waitForIdle()
            assertEquals(before, compose.onNodeWithContentDescription("Back").getBoundsInRoot())
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        } finally {
            if (oldScale.toFloatOrNull() == null) shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $oldScale")
            assertEquals(oldScale, shell("settings get global animator_duration_scale"))
        }
    }
}
