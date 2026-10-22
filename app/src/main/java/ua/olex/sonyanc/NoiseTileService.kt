package ua.olex.sonyanc

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Uses the widget's bounded reconnect and acknowledgement path, without opening the app. */
class NoiseTileService : TileService() {
    private var listening:Job?=null
    override fun onStartListening() {
        super.onStartListening()
        Headphones.init(this)
        listening?.cancel()
        listening=CoroutineScope(Dispatchers.Main).launch {
            combine(Headphones.nc,Headphones.pending,Headphones.status) {state,pending,status ->
                Triple(state,pending,status)
            }.collect {(state,pending,status) ->
                qsTile?.apply {
                    label="Noise control"
                    if(Build.VERSION.SDK_INT>=29) subtitle=(pending ?: state)?.mode?.short ?: "Tap to connect"
                    this.state=if(status==Headphones.Status.CONNECTED && (pending ?: state)?.mode!=SonyProtocol.Mode.OFF) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    contentDescription="Noise control, "+((pending ?: state)?.mode?.label ?: "not connected")
                    updateTile()
                }
            }
        }
    }
    override fun onClick() {
        super.onClick()
        if(!Headphones.hasPermission(this) || Prefs.deviceAddress(this)==null) {
            qsTile?.apply {if(Build.VERSION.SDK_INT>=29) subtitle="Set up Hush first";updateTile()}
            return
        }
        val current=(Headphones.pending.value ?: Headphones.nc.value ?: Prefs.lastNc(this))?.mode
        val next=when(current) {
            SonyProtocol.Mode.ANC -> SonyProtocol.Mode.AMBIENT
            SonyProtocol.Mode.AMBIENT -> SonyProtocol.Mode.OFF
            else -> SonyProtocol.Mode.ANC
        }
        sendBroadcast(Intent(this,ModeWidget::class.java).setAction("ua.olex.sonyanc.SET_MODE").putExtra("mode",next.name))
    }
    override fun onStopListening() {listening?.cancel();listening=null;super.onStopListening()}
    override fun onDestroy() {listening?.cancel();super.onDestroy()}
}
