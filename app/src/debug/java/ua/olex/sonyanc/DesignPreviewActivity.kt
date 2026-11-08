package ua.olex.sonyanc

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Deterministic emulator visual checks; no connection, permissions, or hidden hardware mocks. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scene = intent.getStringExtra("scene") ?: "anc"
        setContent { AppTheme { Scene(scene) } }
    }
}

private val settingsFixture = mapOf(
    AdvancedSettings.Key.CHAT to listOf(0, 1), AdvancedSettings.Key.CHAT_CONFIG to listOf(0, 1),
    AdvancedSettings.Key.SIDETONE to listOf(0, 7, 9), AdvancedSettings.Key.TOUCH to listOf(2, 0, 32),
    AdvancedSettings.Key.CYCLE to listOf(1, 53, 1, 0, 1), AdvancedSettings.Key.GESTURES to listOf(0),
    AdvancedSettings.Key.POWER to listOf(16, 0), AdvancedSettings.Key.QUALITY to listOf(0),
    AdvancedSettings.Key.GUIDANCE to listOf(0, 1), AdvancedSettings.Key.GUIDANCE_VOLUME to listOf(0),
)

@Composable
private fun Scene(scene: String) {
    when (scene) {
        "settings" -> EarbudSettingsContent(settingsFixture, DeviceSettingsProtocol.Feature.entries.associateWith { true })
        "settings-loading" -> EarbudSettingsContent(emptyMap(), emptyMap(), AdvancedSettings.Key.entries.toSet(), DeviceSettingsProtocol.Feature.entries.toSet())
        "equalizer" -> EqualizerContent(EqualizerProtocol.State(0xA0, listOf(3, 4, 1, -2, 2, 6)), busy = false, unavailable = false)
        "equalizer-preset" -> EqualizerContent(EqualizerProtocol.State(0x16, emptyList()), busy = false, unavailable = false)
        "info" -> DeviceInfoContent("WF-1000XM5", Headphones.Status.CONNECTED, CodecProtocol.State(0x10), "2.0.1", refreshing = false)
        "app" -> AppSettingsContent(
            listOf(PairedDevice("00:11", "WF-1000XM5")), "00:11", "WF-1000XM5", dynamicColor = true, haptics = true,
            canAddTile = true, canPinWidget = true, version = "1.0.0",
        )
        "diagnostics" -> DiagnosticsContent(listOf("✓ WF-1000XM5 · secure V2", "→ [0C] 66 17", "← [0C] 67 17 01 01 00 00 14", "→ [0C] 12 02", "← [0C] 13 02 10"))
        "widgets" -> WidgetGallery()
        else -> {
            var state by remember { mutableStateOf(SonyProtocol.NcState(if (scene == "ambient") SonyProtocol.Mode.AMBIENT else SonyProtocol.Mode.ANC, level = 12)) }
            HushScreen(
                status = if (scene == "error") Headphones.Status.FAILED else Headphones.Status.CONNECTED,
                nc = state, pending = null, fresh = true,
                error = if (scene == "error") "Could not connect. Check that your earbuds are connected to this phone." else null,
                battery = SonyProtocol.Battery(82, 79, 56, rightCharging = true), granted = scene != "permission", hasDevice = true,
                codec = CodecProtocol.State(0x10), onMode = { state = it }, onSettings = {}, onConnect = {}, onPermission = {},
                onBluetooth = {}, onPick = {}, equalizerPreset = "Custom",
            )
        }
    }
}

@Composable
private fun WidgetGallery() {
    Surface(color = Color(0xFF111724), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            listOf(360 to SonyProtocol.Mode.AMBIENT, 280 to SonyProtocol.Mode.ANC, 200 to SonyProtocol.Mode.OFF).forEach { (width, mode) ->
                Text("$width dp · launcher slot 104 dp", color = Color(0xFFCAD1E4), style = MaterialTheme.typography.labelLarge)
                AndroidView(factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val views = ModeWidget.viewsForSize(ctx, width.toFloat(), mode, interactive = false)
                        addView(views.apply(ctx, this), FrameLayout.LayoutParams(-1, -1))
                    }
                }, modifier = Modifier.width(width.dp).height(104.dp))
            }
        }
    }
}
