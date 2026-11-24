package ua.olex.sonyanc

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Uses the widget's bounded reconnect and acknowledgement path, without opening the app. */
class NoiseTileService : TileService() {
    private var listening:Job?=null
    override fun onStartListening() {
        super.onStartListening()
        Headphones.init(this)
        listening?.cancel()
        listening=CoroutineScope(Dispatchers.Main).launch {
            Headphones.state.combine(Prefs.linkedChanges(this@NoiseTileService)) { device, linked ->
                Triple(device.shownNc?.mode, linked, device.pending != null)
            }.distinctUntilChanged().collect { (mode, linked, applying) ->
                val needsSetup = !Headphones.hasPermission(this@NoiseTileService) ||
                    Prefs.deviceAddress(this@NoiseTileService) == null
                qsTile?.apply {
                    label = "Noise control"
                    subtitle = when {
                        needsSetup -> "Set up Earbuds first"
                        linked == false -> "Earbuds disconnected"
                        applying -> "Applying ${mode?.short.orEmpty()}"
                        else -> mode?.short ?: "Tap to connect"
                    }
                    state = when {
                        linked == false && !needsSetup -> Tile.STATE_UNAVAILABLE
                        !needsSetup && linked == true && mode != null && mode != SonyProtocol.Mode.OFF -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    contentDescription = "$label, $subtitle"
                    updateTile()
                }
            }
        }
    }
    override fun onClick() {
        super.onClick()
        Headphones.init(this)
        if(!Headphones.hasPermission(this) || Prefs.deviceAddress(this)==null) {
            val setup = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(setup)
            return
        }
        if (Prefs.linked(this) == false) return
        val current=(Headphones.state.value.shownNc ?: Prefs.lastNc(this))?.mode
        val next=when(current) {
            SonyProtocol.Mode.ANC -> SonyProtocol.Mode.AMBIENT
            SonyProtocol.Mode.AMBIENT -> if(Prefs.tileTwoWay(this)) SonyProtocol.Mode.ANC else SonyProtocol.Mode.OFF
            else -> SonyProtocol.Mode.ANC
        }
        sendBroadcast(Intent(this,ModeWidget::class.java).setAction("ua.olex.sonyanc.SET_MODE").putExtra("mode",next.name))
    }
    override fun onStopListening() {listening?.cancel();listening=null;super.onStopListening()}
    override fun onDestroy() {listening?.cancel();super.onDestroy()}
}
