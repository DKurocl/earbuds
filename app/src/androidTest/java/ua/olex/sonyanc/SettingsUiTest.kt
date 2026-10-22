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
        listOf("DSEE","Speak-to-Chat","Hear my voice in calls","Head gestures","Pause when removed","Voice guidance").forEach {title ->
            compose.onNodeWithContentDescription(title).performScrollTo().assertIsOn().performClick().assertIsOff()
        }
        compose.onNodeWithText("Device controls").assertDoesNotExist()
        compose.onNodeWithText("Read setting").assertDoesNotExist()
    }
    @Test fun everyChoiceIsReachableAndPreservesTheOtherFields() {
        var last:Triple<AdvancedSettings.Key,Int,Int>?=null
        content {key,index,code->last=Triple(key,index,code)}
        val titles=mapOf(
            AdvancedSettings.Key.TOUCH to listOf("Left touch control","Right touch control"),
            AdvancedSettings.Key.CYCLE to listOf("Noise control cycle"),
            AdvancedSettings.Key.CHAT_CONFIG to listOf("Conversation sensitivity","Resume after conversation"),
            AdvancedSettings.Key.POWER to listOf("Automatic power off"),
            AdvancedSettings.Key.QUALITY to listOf("Bluetooth quality"),
            AdvancedSettings.Key.GUIDANCE_VOLUME to listOf("Voice guidance volume"))
        titles.forEach {(key,labels)->key.fields.forEachIndexed {i,field->field.choices.forEach {choice->
            compose.onNodeWithContentDescription(labels[i]).performScrollTo().performClick()
            compose.onAllNodesWithText(choice.label).onLast().performClick()
            compose.runOnIdle {assertEquals(Triple(key,field.index,choice.code),last)}
        }}}
    }
    @Test fun missingDeviceValuesNeverBecomeEnabledSwitches() {
        compose.setContent {AppTheme(dynamic=false){EarbudSettingsContent(emptyMap(),emptyMap())}}
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Earbud settings").assertIsDisplayed()
        compose.onNodeWithText("Voice guidance volume").performScrollTo().assertIsDisplayed()
    }
    @Test fun rapidModesAmbientLevelAndVoiceFocusUseTheLatestState() {
        compose.setContent {
            var value by remember {mutableStateOf(SonyProtocol.NcState(SonyProtocol.Mode.ANC))}
            AppTheme(dynamic=false) {HushScreen(Headphones.Status.CONNECTED,value,null,true,null,SonyProtocol.Battery(80,90,60),
                true,true,onMode={value=it},onSettings={},onConnect={},onPermission={},onBluetooth={},onPick={})}
        }
        repeat(3) {
            listOf("Ambient sound","Noise control off","Noise cancelling").forEach {title->
                compose.onNodeWithContentDescription(title).performScrollTo().performClick()
            }
        }
        compose.onNodeWithContentDescription("Ambient sound").performClick().assertIsSelected()
        compose.onNodeWithText("Low").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ambient sound level").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"5 of 20"))
        compose.onNodeWithContentDescription("Voice focus").performScrollTo().performClick().assertIsOn()
    }    @Test fun largeFontChoicesArePlacedBelowTheirLabels() {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                AppTheme(dynamic=false) {EarbudSettingsContent(fixtures,DeviceSettingsProtocol.Feature.entries.associateWith{true})}
            }
        }
        listOf("Left touch control","Right touch control","Automatic power off","Bluetooth quality","Voice guidance volume").forEach {title->
            compose.onNodeWithContentDescription(title).performScrollTo()
            val label=compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
            val choice=compose.onNodeWithContentDescription(title).fetchSemanticsNode().boundsInRoot
            assertTrue("$title value overlaps its label",label.bottom<=choice.top+1f)
        }
    }

}
