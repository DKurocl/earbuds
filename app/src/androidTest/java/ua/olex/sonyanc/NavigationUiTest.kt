package ua.olex.sonyanc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.lifecycle.Lifecycle
import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals

class NavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun productionGearOpensAppSettingsInOneTap() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    private fun swipeBack(progress: Float) = compose.runOnUiThread {
        val back = compose.activity.onBackPressedDispatcher
        back.dispatchOnBackStarted(BackEventCompat(8f, 900f, 0f, BackEventCompat.EDGE_LEFT))
        back.dispatchOnBackProgressed(BackEventCompat(240f, 980f, progress, BackEventCompat.EDGE_LEFT))
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
        swipeBack(0.5f)
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

}
