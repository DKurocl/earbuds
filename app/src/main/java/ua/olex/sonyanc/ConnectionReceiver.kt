package ua.olex.sonyanc

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reacts to Android's ACL link for the saved WF-1000XM5 (both actions are exempt from the
 * implicit-broadcast limits, so this also runs when Hush is not in memory).
 *
 * Connected: one bounded sync (optional on-connect mode, then battery) under a short background
 * lease. Disconnected: remember when, and dim the widgets. No service, job or alarm is created.
 */
class ConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val saved = Prefs.deviceAddress(ctx) ?: return
        if (device?.address != saved) return
        Headphones.init(ctx)
        Headphones.addLog(if (action == BluetoothDevice.ACTION_ACL_CONNECTED) "· Bluetooth: earbuds connected" else "· Bluetooth: earbuds disconnected")

        if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            Prefs.setLinked(ctx, false)
            Prefs.setLastSeen(ctx, System.currentTimeMillis())
            Widgets.refreshAll(ctx)
            CaseReminder.onEarbudsAway(ctx)
            LowBattery.onAway(ctx)
            Headphones.stay(false)
            return
        }

        Prefs.setLinked(ctx, true)
        Prefs.nextSession(ctx) // out of the case: a new continuous-use session for BatteryLog
        ChargeReminder.recordOut(ctx)
        Widgets.refreshAll(ctx)
        CaseReminder.clear(ctx)
        if (!Headphones.hasPermission(ctx)) return
        Headphones.init(ctx)
        // "When earbuds connect", or the home mode when the phone is at home (Places).
        val modeRevision = ModeControl.revision
        val session = Prefs.session(ctx)
        val apply = Places.modeOnConnect(ctx)
        fun sameSession() = Prefs.deviceAddress(ctx) == saved && Prefs.linked(ctx) == true && Prefs.session(ctx) == session
        val pending = goAsync()
        scope.launch {
            try {
                // The sheet comes from Stay in sync's presence event, the moment Android lets a
                // companion open a screen; this broadcast only backs it up if that has not come.
                delay(SHEET_BACKUP_MS)
                if (!sameSession()) return@launch
                ConnectedSheet.show(ctx, "Bluetooth")
                // Let Android finish A2DP/HFP setup before opening the control channel.
                delay(SETTLE_MS - SHEET_BACKUP_MS)
                if (!sameSession()) return@launch
                withTimeoutOrNull(SYNC_BUDGET_MS) { Headphones.syncOnConnect(apply, modeRevision, ::sameSession) }
                // Normally LinkService's presence event does this; the broadcast backs it up.
                if (sameSession()) {
                    if (LiveLink.enabled(ctx)) Headphones.stay(true)
                    CaseReminder.onEarbudsOut(ctx)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Settle + budget stays well inside the broadcast deadline.
        internal const val SETTLE_MS = 2000L
        private const val SHEET_BACKUP_MS = 1500L
        internal const val SYNC_BUDGET_MS = 7500L
    }
}
