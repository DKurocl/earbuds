package ua.olex.sonyanc

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI fixtures only: these tests do not emulate or certify Sony hardware. */
class SettingsUiTest {
    @get:Rule val compose=createComposeRule()
    private val fixtures=mapOf(
        AdvancedSettings.Key.TOUCH to listOf(2,0,0x20),
        AdvancedSettings.Key.CYCLE to listOf(1,0x35,1,0,1),
        AdvancedSettings.Key.CHAT to listOf(0,1),
        AdvancedSettings.Key.CHAT_CONFIG to listOf(0,1),
        AdvancedSettings.Key.POWER to listOf(0x10,0),
        AdvancedSettings.Key.SIDETONE to listOf(0,7,9),
        AdvancedSettings.Key.GESTURES to listOf(0),
        AdvancedSettings.Key.QUALITY to listOf(0),
        AdvancedSettings.Key.GUIDANCE to listOf(0,1),
        AdvancedSettings.Key.GUIDANCE_VOLUME to listOf(0))
    private fun content(onChange:(AdvancedSettings.Key,Int,Int)->Unit={_,_,_->}) {
        compose.setContent {
            var values by remember {mutableStateOf(fixtures)}
            var basic by remember {mutableStateOf(DeviceSettingsProtocol.Feature.entries.associateWith{true})}
            AppTheme(dynamic=false) {
                EarbudSettingsContent(values,basic,onUpdate={key,index,code->
                    values=values+(key to values.getValue(key).toMutableList().apply{this[index]=code})
                    onChange(key,index,code)
                },onBasic={key,value->basic=basic+(key to value)})
            }
        }
    }
    @Test fun allBooleanControlsAreImmediatelyEditable() {
        content()
        listOf("DSEE","Speak-to-Chat","Hear my voice","Head gestures","Pause when removed","Voice prompts").forEach {title ->
            compose.onNodeWithText(title).performScrollTo().assertIsOn().performClick().assertIsOff()
        }
        compose.onNodeWithText("Device controls").assertDoesNotExist()
        compose.onNodeWithText("Read setting").assertDoesNotExist()
    }
    // The settings now open a full-screen choice page in its own dialog window.
    // Scope actions to that window: the underlying settings row also contains the selected label.
    private fun choiceOption(label: String) =
        compose.onNode(hasText(label) and isSelectable() and hasAnyAncestor(isDialog()))

    private fun closeChoicePage() {
        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun everyChoiceIsReachableAndPreservesTheOtherFields() {
        val writes = mutableListOf<Triple<AdvancedSettings.Key, Int, Int>>()
        content { key, index, code -> writes.add(Triple(key, index, code)) }
        val titles = mapOf(
            AdvancedSettings.Key.CYCLE to listOf("Noise control cycle"),
            AdvancedSettings.Key.CHAT_CONFIG to listOf("Voice detection", "Music comes back"),
            AdvancedSettings.Key.POWER to listOf("Automatic power off"),
            AdvancedSettings.Key.QUALITY to listOf("Bluetooth quality"),
            AdvancedSettings.Key.GUIDANCE_VOLUME to listOf("Prompt volume"))
        val expected = mutableListOf<Triple<AdvancedSettings.Key, Int, Int>>()
        titles.forEach { (key, labels) ->
            key.fields.forEachIndexed { i, field ->
                var current = fixtures.getValue(key)[field.index]
                field.choices.forEach { choice ->
                    compose.onNodeWithText(labels[i]).performScrollTo().performClick()
                    compose.onNode(isDialog()).assertExists()
                    val currentLabel = field.choices.first { it.code == current }.label
                    choiceOption(currentLabel).assertIsSelected()
                    choiceOption(choice.label).performScrollTo().performClick()
                    compose.onNode(isDialog()).assertDoesNotExist()
                    // Choosing the current value closes the page without a redundant device write.
                    if (choice.code != current) expected.add(Triple(key, field.index, choice.code))
                    current = choice.code
                    compose.runOnIdle { assertEquals(expected, writes) }
                    compose.onNodeWithText(labels[i]).assert(hasText(choice.label))
                }
            }
        }
    }

    @Test fun touchRowsShowAssignmentsAndWriteOnlyTheChosenSide() {
        val writes = mutableListOf<Triple<AdvancedSettings.Key, Int, Int>>()
        content { setting, index, code -> writes.add(Triple(setting, index, code)) }
        compose.onNodeWithText("Left earbud").performScrollTo().performClick()
        choiceOption("Noise control").assertIsSelected()
        closeChoicePage()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("Right earbud").performScrollTo().performClick()
        choiceOption("Playback").assertIsSelected()
        choiceOption("Off").performScrollTo().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Triple(AdvancedSettings.Key.TOUCH, 2, 0xFF)), writes) }
    }
    @Test fun missingDeviceValuesNeverBecomeEnabledSwitches() {
        compose.setContent {AppTheme(dynamic=false){EarbudSettingsContent(emptyMap(),emptyMap())}}
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Earbud settings").assertIsDisplayed()
        // Settings the earbuds never answered are left out, not shown as dead rows.
        compose.onNodeWithText("Prompt volume").assertDoesNotExist()
    }
    @Test fun rapidModesAmbientLevelAndVoiceFocusUseTheLatestState() {
        compose.setContent {
            var value by remember {mutableStateOf(SonyProtocol.NcState(SonyProtocol.Mode.ANC))}
            AppTheme(dynamic=false) {HushScreen(Headphones.Status.CONNECTED,value,null,true,null,SonyProtocol.Battery(80,90,60),
                true,true,onMode={value=it},onSettings={},onConnect={},onPermission={},onBluetooth={},onPick={})}
        }
        repeat(3) {
            listOf("Ambient sound","Noise control off","Noise cancelling").forEach {title->
                // The modes live in the fixed dock at the bottom: no scrolling.
                compose.onNodeWithContentDescription(title).performClick()
            }
        }
        compose.onNodeWithContentDescription("Ambient sound").performClick().assertIsSelected()
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithContentDescription("Ambient sound level").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"5 of 20"))
        compose.onNodeWithContentDescription("Voice focus").performClick().assertIsOn()
    }
    @Test fun largeFontValuesStayBelowTheirLabels() {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                AppTheme(dynamic=false) {EarbudSettingsContent(fixtures,DeviceSettingsProtocol.Feature.entries.associateWith{true})}
            }
        }
        listOf("Automatic power off","Bluetooth quality","Prompt volume").forEach {title->
            compose.onNodeWithText(title).performScrollTo()
            val label=compose.onNode(hasText(title),useUnmergedTree=true).fetchSemanticsNode()
            // The value is the next text below the label in the same column (it may sit inside the
            // row note's crossfade, so it is not always the label's sibling).
            val value=compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text),useUnmergedTree=true)
                .fetchSemanticsNodes().filter {it.id!=label.id && it.boundsInRoot.top>label.boundsInRoot.top &&
                    kotlin.math.abs(it.boundsInRoot.left-label.boundsInRoot.left)<4f}.minBy {it.boundsInRoot.top}
            assertTrue("$title value overlaps its label",label.boundsInRoot.bottom<=value.boundsInRoot.top+1f)
        }
    }
    @Test fun choicePageShowsTheCurrentValueAndBackDiscardsChanges() {
        val writes = mutableListOf<Triple<AdvancedSettings.Key, Int, Int>>()
        content { key, index, code -> writes.add(Triple(key, index, code)) }
        compose.onNodeWithText("Bluetooth quality").performScrollTo().performClick()
        choiceOption("Sound quality").assertIsSelected()
        closeChoicePage()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("Bluetooth quality").assert(hasText("Sound quality"))
    }
}
