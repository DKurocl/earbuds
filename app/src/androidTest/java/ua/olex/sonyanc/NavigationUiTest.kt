package ua.olex.sonyanc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun productionGearOpensEarbudSettingsInOneTap() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onAllNodesWithText("Earbud settings").onLast().assertIsDisplayed()
        compose.onNodeWithText("DSEE").assertIsDisplayed()
        compose.onNodeWithText("Device controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Hush").assertIsDisplayed()
    }
}
