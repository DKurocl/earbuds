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

        if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            Prefs.setLinked(ctx, false)
            Prefs.setLastSeen(ctx, System.currentTimeMillis())
            Widgets.refreshAll(ctx)
            CaseReminder.onEarbudsAway(ctx)
            return
        }

        Prefs.setLinked(ctx, true)
        Prefs.nextSession(ctx) // out of the case: a new continuous-use session for BatteryLog
        Widgets.refreshAll(ctx)
        CaseReminder.clear(ctx)
        if (!Headphones.hasPermission(ctx)) return
        Headphones.init(ctx)
        val onConnect = Prefs.onConnect(ctx)
        val pending = goAsync()
        scope.launch {
            try {
                // Let Android finish A2DP/HFP setup before opening the control channel.
                delay(SETTLE_MS)
                withTimeoutOrNull(SYNC_BUDGET_MS) { Headphones.syncOnConnect(onConnect.mode) }
                CaseReminder.onEarbudsOut(ctx)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Settle + budget stays well inside the broadcast deadline.
        internal const val SETTLE_MS = 2000L
        internal const val SYNC_BUDGET_MS = 7500L
    }
}
