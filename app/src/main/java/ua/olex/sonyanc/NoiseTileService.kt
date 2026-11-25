package ua.olex.sonyanc

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import ua.olex.sonyanc.SonyProtocol.Mode

/** Uses the widget's bounded reconnect and acknowledgement path, without opening the app. */
class NoiseTileService : TileService() {
    private var listening:Job?=null
    override fun onStartListening() {
        super.onStartListening()
        Headphones.init(this)
        listening?.cancel()
        listening=CoroutineScope(Dispatchers.Main).launch {
            Headphones.state.combine(Prefs.tileLinkChanges(this@NoiseTileService)) { device, (linked, address) ->
                val needsSetup = !Headphones.hasPermission(this@NoiseTileService) || address == null
                TileState(device.shownNc?.mode, linked, device.pending != null, needsSetup)
            }.distinctUntilChanged().collect { (mode, linked, applying, needsSetup) ->
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
        sendBroadcast(ModeWidget.modeIntent(this, nextMode(current, Prefs.tileTwoWay(this))))
    }
    override fun onStopListening() {listening?.cancel();listening=null;super.onStopListening()}
    override fun onDestroy() {listening?.cancel();super.onDestroy()}

    private data class TileState(val mode: Mode?, val linked: Boolean?, val applying: Boolean, val needsSetup: Boolean)

    companion object {
        internal fun nextMode(current: Mode?, twoWay: Boolean): Mode = when (current) {
            Mode.ANC -> Mode.AMBIENT
            Mode.AMBIENT -> if (twoWay) Mode.ANC else Mode.OFF
            else -> Mode.ANC
        }
    }
}
