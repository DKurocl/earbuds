package ua.olex.sonyanc

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

/** UI fixtures only: scenes are applied by the caller; no hardware is emulated. */
class ScenesUiTest {
    @get:Rule val compose = createComposeRule()

    private fun home(onScene: (Scene) -> Unit = {}, onSave: () -> Unit = {}, onEdit: (Scene) -> Unit = {}) {
        compose.setContent {
            var nc by remember { mutableStateOf(NcState(Mode.ANC)) }
            AppTheme(dynamic = false) {
                HushScreen(
                    Headphones.Status.CONNECTED, nc, null, true, null, SonyProtocol.Battery(80, 60, 50), true, true,
                    onMode = { nc = it }, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
                    scenes = Scenes.defaults,
                    onScene = { onScene(it); nc = it.target(nc) }, onSaveScene = onSave, onEditScene = onEdit,
                )
            }
        }
    }

    @Test fun tappingASceneAppliesItAndHighlightsTheMatch() {
        var applied: Scene? = null
        home(onScene = { applied = it })
        compose.onNodeWithText("Focus").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Conversation").performScrollTo().assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("talk", applied?.id) }
        compose.onNodeWithText("Conversation").assertIsSelected()
        compose.onNodeWithText("Focus").assertIsNotSelected()
    }

    @Test fun saveAndEditAreReachable() {
        var saved = false
        var edited: Scene? = null
        home(onSave = { saved = true }, onEdit = { edited = it })
        compose.onNodeWithText("Save current").performScrollTo().performClick()
        compose.onNodeWithText("Commute").performScrollTo().performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(saved)
            assertEquals("commute", edited?.id)
        }
    }

    @Test fun batteryShowsListeningTime() {
        home()
        compose.onNodeWithText("About 4 h 40 min of listening · case adds 8 h").performScrollTo().assertIsDisplayed()
    }
}
