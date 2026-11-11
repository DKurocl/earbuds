package ua.olex.sonyanc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Test

/**
 * Google's Accessibility Test Framework on every page, on a real Android: touch targets, text
 * contrast, labels for TalkBack, duplicate descriptions. A failure names the node and the rule.
 * UI fixtures only; no earbuds are emulated.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityUiTest {
    private fun check(content: @Composable () -> Unit) = runAndroidComposeUiTest<ComponentActivity> {
        setContent { AppTheme(dynamic = false) { content() } }
        enableAccessibilityChecks()
        onRoot().tryPerformAccessibilityChecks()
    }

    private val battery = SonyProtocol.Battery(69, 70, 78, caseCharging = true)

    @Test fun home() = check {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.ANC, level = 12), pending = null,
            fresh = true, error = null, battery = battery, granted = true, hasDevice = true,
            codec = CodecProtocol.State(0x10), onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
            speakToChat = true,
        )
    }

    @Test fun homeAmbient() = check {
        HushScreen(
            status = Headphones.Status.CONNECTED, nc = SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT, voice = true, level = 12), pending = null,
            fresh = true, error = null, battery = battery, granted = true, hasDevice = true,
            onMode = {}, onSettings = {}, onConnect = {}, onPermission = {}, onBluetooth = {}, onPick = {},
        )
    }

    @Test fun earbudSettings() = check {
        EarbudSettingsContent(
            mapOf(AdvancedSettings.Key.CHAT to listOf(0, 1), AdvancedSettings.Key.TOUCH to listOf(2, 0, 0x20)),
            DeviceSettingsProtocol.Feature.entries.associateWith { true },
        )
    }

    @Test fun equalizer() = check { EqualizerContent(EqualizerProtocol.State(0xA0, listOf(3, 4, 1, -2, 2, 6)), busy = false, unavailable = false) }

    @Test fun deviceInfo() = check {
        DeviceInfoContent("WF-1000XM5", Headphones.Status.CONNECTED, CodecProtocol.State(0x10), "2.0.1", refreshing = false)
    }

    @Test fun battery() = check { BatteryContent(emptyList(), System.currentTimeMillis()) }
}
