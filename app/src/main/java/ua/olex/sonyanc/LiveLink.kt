package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * "Stay in sync": Earbuds as the earbuds' companion app, the way Pixel Buds and watch apps are.
 *
 * After the owner allows it once (Android's own companion dialog), Android binds [LinkService]
 * while the earbuds are connected to the phone and unbinds it when they go back in the case.
 * For that time Earbuds keeps its control channel open: the earbuds' own notifications keep the
 * widget, tile and battery true (a mode changed with a touch on the earbuds shows at once) and a
 * tap is sent at once, without connecting first. No foreground service, notification or polling:
 * Android keeps the process for as long as the earbuds are present, and nothing runs otherwise.
 */
object LiveLink {
    /** Presence events need Android 16's request API; older versions keep the on-demand link. */
    @get:ChecksSdkIntAtLeast(api = 36)
    val supported get() = Build.VERSION.SDK_INT >= 36

    private fun cdm(ctx: Context) = ctx.getSystemService(CompanionDeviceManager::class.java)

    /** The association for the saved earbuds, if the owner allowed one. */
    fun association(ctx: Context): AssociationInfo? {
        if (!supported) return null
        val address = Prefs.deviceAddress(ctx) ?: return null
        return runCatching { cdm(ctx)?.myAssociations.orEmpty() }.getOrDefault(emptyList())
            .firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
    }

    fun enabled(ctx: Context) = association(ctx) != null

    /**
     * Asks Android to make Earbuds the companion of the saved earbuds. [confirm] launches the
     * system's consent dialog; [done] reports the outcome (true once observing has started).
     */
    fun enable(ctx: Context, confirm: (IntentSender) -> Unit, done: (Boolean) -> Unit) {
        val address = Prefs.deviceAddress(ctx)
        val manager = cdm(ctx)
        if (!supported || address == null || manager == null) {
            Headphones.addLog("✗ Stay in sync: ${if (!supported) "needs Android 16" else if (address == null) "no earbuds chosen" else "no companion service"}")
            return done(false)
        }
        association(ctx)?.let { return done(observe(ctx, it.id)) }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        manager.associate(request, ctx.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = confirm(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val current = address.equals(Prefs.deviceAddress(ctx), ignoreCase = true)
                val observed = current && observe(ctx, associationInfo.id)
                if (!observed) runCatching { manager.disassociate(associationInfo.id) }
                done(observed)
            }
            override fun onFailure(error: CharSequence?) {
                Headphones.addLog("✗ companion: $error")
                done(false)
            }
        })
    }

    /** Called after the consent dialog: observing starts in onAssociationCreated; this covers a cancel. */
    fun confirmed(ctx: Context, ok: Boolean, done: (Boolean) -> Unit) { if (!ok) done(enabled(ctx)) }

    fun disable(ctx: Context) {
        if (!supported) return
        val info = association(ctx) ?: return
        runCatching { cdm(ctx)?.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()) }
        runCatching { cdm(ctx)?.disassociate(info.id) }
        Headphones.stay(false)
    }

    /** A new pair of earbuds was chosen: the old association no longer applies. */
    fun forget(ctx: Context) {
        if (!supported) return
        runCatching { cdm(ctx)?.myAssociations.orEmpty().forEach { cdm(ctx)?.disassociate(it.id) } }
        Headphones.stay(false)
    }

    @SuppressLint("MissingPermission") // REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE is a normal permission
    private fun observe(ctx: Context, id: Int): Boolean {
        if (!supported) return false
        val manager = cdm(ctx) ?: return false
        return runCatching {
            manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        }.onFailure { Headphones.addLog("✗ companion observe: ${it.message}") }.isSuccess
    }

    /** Re-arms observing after an update or reboot (cheap and idempotent). */
    fun ensureObserving(ctx: Context) { association(ctx)?.let { observe(ctx, it.id) } }

    /** What a presence event means for the link. Pure, unit-tested. */
    fun presenceChange(event: Int): Boolean? = when (event) {
        DevicePresenceEvent.EVENT_BT_CONNECTED -> true
        DevicePresenceEvent.EVENT_BT_DISCONNECTED, DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED -> false
        else -> null
    }
}

/** Bound by Android while the associated earbuds are connected (see [LiveLink]). */
@RequiresApi(36)
class LinkService : CompanionDeviceService() {
    private var activeAssociation: Int? = null

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        Headphones.init(this)
        Headphones.addLog("· Stay in sync: Android reports event ${event.event}")
        val present = LiveLink.presenceChange(event.event) ?: return
        if (present) {
            if (event.associationId != LiveLink.association(this)?.id) return
            if (!Headphones.hasPermission(this)) return Headphones.addLog("✗ Stay in sync: no Bluetooth permission")
            activeAssociation = event.associationId
        } else {
            if (event.associationId != activeAssociation) return
            activeAssociation = null
        }
        Headphones.stay(present)
        // Android binds this service for the companion: the surest moment to bring the sheet up.
        if (present) ConnectedSheet.show(this, "Stay in sync")
    }

    override fun onDestroy() {
        // Unbound: the earbuds are gone, or Android stopped observing.
        if (activeAssociation != null) Headphones.stay(false)
        super.onDestroy()
    }
}
