package ua.olex.sonyanc

import android.os.Bundle
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*

/** Deterministic emulator visual checks; no connection, permissions, or hidden hardware mocks. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scene=intent.getStringExtra("scene") ?: "anc"
        setContent { AppTheme {
            if(scene=="settings") {
                EarbudSettingsContent(mapOf(
                    AdvancedSettings.Key.CHAT to listOf(0,1),AdvancedSettings.Key.CHAT_CONFIG to listOf(0,1),
                    AdvancedSettings.Key.SIDETONE to listOf(0,7,9),AdvancedSettings.Key.TOUCH to listOf(2,0,32),
                    AdvancedSettings.Key.CYCLE to listOf(1,53,1,0,1),AdvancedSettings.Key.GESTURES to listOf(0),
                    AdvancedSettings.Key.POWER to listOf(16,0),AdvancedSettings.Key.QUALITY to listOf(0),
                    AdvancedSettings.Key.GUIDANCE to listOf(0,1),AdvancedSettings.Key.GUIDANCE_VOLUME to listOf(0)),
                    DeviceSettingsProtocol.Feature.entries.associateWith{true})
                return@AppTheme
            }
            if(scene=="widgets") { WidgetGallery();return@AppTheme }
            var state by remember { mutableStateOf(SonyProtocol.NcState(if(scene=="ambient") SonyProtocol.Mode.AMBIENT else SonyProtocol.Mode.ANC,level=12)) }
            HushScreen(
                status=if(scene=="error") Headphones.Status.FAILED else Headphones.Status.CONNECTED,
                nc=state,pending=null,fresh=true,
                error=if(scene=="error") "Could not connect. Check that your earbuds are connected to this phone." else null,
                battery=SonyProtocol.Battery(82,79,56,rightCharging=true),granted=scene!="permission",hasDevice=true,
                codec=CodecProtocol.State(0x10),onMode={state=it},onSettings={},onConnect={},onPermission={},onBluetooth={},onPick={})
        } }
    }
}

@Composable
private fun WidgetGallery() {
    Surface(color=Color(0xFF111724),modifier=Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            listOf(360 to SonyProtocol.Mode.AMBIENT,280 to SonyProtocol.Mode.ANC,200 to SonyProtocol.Mode.OFF).forEach {(width,mode)->
                Text("${width} dp · launcher slot 104 dp",color=Color(0xFFCAD1E4),style=MaterialTheme.typography.labelLarge)
                AndroidView(factory={ctx->
                    FrameLayout(ctx).apply {
                        val views=ModeWidget.viewsForSize(ctx,width.toFloat(),mode,interactive=false)
                        addView(views.apply(ctx,this),FrameLayout.LayoutParams(-1,-1))
                    }
                },modifier=Modifier.width(width.dp).height(104.dp))
            }
        }
    }
}
