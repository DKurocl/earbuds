package ua.olex.sonyanc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Requests from the owner's Music app (only apps signed with the owner's key hold the permission):
 *  - EQ_OFF: Music's Sony correction now shapes the sound, so the earbuds' own equalizer goes off;
 *  - SLEEP: Music's sleep timer stopped playback, so the earbuds do what App settings say
 *    (turn off by default, so they do not run down overnight).
 * Only when the saved earbuds are connected; one bounded exchange under a short background lease.
 */
class MusicLinkReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != EQ_OFF && action != SLEEP) return
        if (Prefs.linked(ctx) != true || !Headphones.hasPermission(ctx)) return
        Headphones.init(ctx)
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    Headphones.hold(foreground = false)
                    try {
                        if (!Headphones.connectAndSync()) return@withTimeoutOrNull
                        when (action) {
                            EQ_OFF -> {
                                Headphones.addLog("· Music: Sony correction on, turning the earbuds' equalizer off")
                                if (Headphones.readEqualizer()?.preset != 0x00) Headphones.writeEqualizer(preset = 0x00)
                            }
                            SLEEP -> when (Prefs.afterSleep(ctx)) {
                                AfterSleep.POWER_OFF -> { Headphones.addLog("· Music: sleep timer ended, turning the earbuds off"); Headphones.powerOff() }
                                AfterSleep.NOISE_OFF -> {
                                    Headphones.addLog("· Music: sleep timer ended, noise control off")
                                    ModeControl.request((Headphones.state.value.shownNc ?: SonyProtocol.NcState(SonyProtocol.Mode.OFF)).copy(mode = SonyProtocol.Mode.OFF)).await()
                                }
                                AfterSleep.NOTHING -> {}
                            }
                        }
                    } finally {
                        Headphones.release(foreground = false)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EQ_OFF = "ua.olex.sonyanc.action.EQ_OFF"
        const val SLEEP = "ua.olex.sonyanc.action.SLEEP"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** What the earbuds do when Music's sleep timer has stopped the music. */
enum class AfterSleep(val label: String) { POWER_OFF("Turn off earbuds"), NOISE_OFF("Noise control off"), NOTHING("Nothing") }
