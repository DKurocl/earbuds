package ua.olex.sonyanc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.lifecycle.Lifecycle

class NavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun productionGearOpensEarbudSettingsInOneTap() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onAllNodesWithText("Earbud settings").onLast().assertIsDisplayed()
        compose.onNodeWithText("DSEE").assertIsDisplayed()
        compose.onNodeWithText("Device controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Earbuds").assertIsDisplayed()
    }    @Test fun leavingTheAppStopsConnectionOwnership() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue("Connection or polling still owned after onStop",Headphones.idle)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Earbuds").assertIsDisplayed()
    }

}
