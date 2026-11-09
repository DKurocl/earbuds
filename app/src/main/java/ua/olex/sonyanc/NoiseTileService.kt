package ua.olex.sonyanc

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
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
            // Mode, link and the emptier earbud's level; codec and other updates are skipped.
            Headphones.state.map {Triple(it.shownNc?.mode,it.connected,it.battery?.let{b->listOfNotNull(b.left,b.right).minOrNull()})}
                .distinctUntilChanged().collect {(mode,connected,level) ->
                qsTile?.apply {
                    label="Noise control"
                    subtitle=(mode?.short ?: "Tap to connect")+(level?.let{" · $it%"} ?: "")
                    // Earbuds in the case: the tile greys out instead of failing a few seconds later.
                    val away=!connected && Prefs.linked(this@NoiseTileService)==false
                    if(away) subtitle="In case"
                    this.state=when {
                        away -> Tile.STATE_UNAVAILABLE
                        connected && mode!=SonyProtocol.Mode.OFF -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    contentDescription="Noise control, "+(mode?.label ?: "not connected")
                    updateTile()
                }
            }
        }
    }
    override fun onClick() {
        super.onClick()
        if(!Headphones.hasPermission(this) || Prefs.deviceAddress(this)==null) {
            qsTile?.apply {subtitle="Set up Earbuds first";updateTile()}
            return
        }
        val current=(Headphones.state.value.shownNc ?: Prefs.lastNc(this))?.mode
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
