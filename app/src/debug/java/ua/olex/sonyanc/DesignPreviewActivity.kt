package ua.olex.sonyanc

import android.os.Bundle
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
