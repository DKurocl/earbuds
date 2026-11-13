package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

/**
 * Everything the app screen decides, kept apart from how it looks: the owner's settings, the
 * actions behind every button, and the messages they produce. The earbuds' own state stays in
 * [Headphones]; this survives rotation, theme and font-size changes, which the screen does not.
 * Only what needs an Activity (permission dialogs, launching other apps) stays in the UI.
 */
@SuppressLint("MissingPermission")
class AppModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Application get() = getApplication()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-line confirmations and errors for the snackbar. */
    val messages = _messages.asSharedFlow()
    fun show(message: String) { _messages.tryEmit(message) }

    // ---- the earbuds and access ----
    var granted by mutableStateOf(Headphones.hasPermission(ctx)); private set
    var saved by mutableStateOf(Prefs.deviceAddress(ctx)); private set
    var devices by mutableStateOf(emptyList<PairedDevice>()); private set
    var displayName by mutableStateOf(Prefs.displayName(ctx) ?: Prefs.deviceName(ctx) ?: "WF-1000XM5"); private set

    // ---- owner's settings ----
    var haptics by mutableStateOf(Prefs.haptics(ctx)); private set
    var dynamicColor by mutableStateOf(Prefs.dynamicColor(ctx)); private set
    var onConnect by mutableStateOf(Prefs.onConnect(ctx)); private set
    var caseReminder by mutableStateOf(Prefs.caseReminder(ctx)); private set
    var budsReminder by mutableStateOf(Prefs.budsReminder(ctx) && notificationsAllowed()); private set
    var tileTwoWay by mutableStateOf(Prefs.tileTwoWay(ctx)); private set
    var liveLink by mutableStateOf(LiveLink.enabled(ctx)); private set
    var homeMode by mutableStateOf(Prefs.homeMode(ctx)); private set
    var home by mutableStateOf(Prefs.home(ctx)); private set
    /** Bumped when a location permission may have changed, so its summary is read again. */
    var placesTick by mutableIntStateOf(0); private set

    // ---- "Ambient for a moment" ----
    var ambientMinutes by mutableIntStateOf(Prefs.ambientMinutes(ctx)); private set
    var ambientUntil by mutableStateOf(AmbientTimer.until(ctx)); private set
    private var ambientWatch: Job? = null

    var turningOff by mutableStateOf(false); private set

    // ---- vibrations ----
    var hapticTakes by mutableStateOf(Haptics.Cue.entries.associateWith { Prefs.hapticTake(ctx, it.name) }); private set
    fun tryHaptic(cue: Haptics.Cue, take: Int) {
        Prefs.setHapticTake(ctx, cue.name, take)
        hapticTakes = hapticTakes + (cue to take)
        Haptics.play(ctx, cue, Haptics.takes.getValue(cue)[take])
    }

    fun notificationsAllowed() = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The screen came to the front: check access, pick the only pair, connect, pick up timers. */
    fun onStart() {
        granted = Headphones.hasPermission(ctx)
        watchAmbient()
        if (!granted) return
        val bonded = Headphones.bondedDevices(ctx)
        devices = bonded.map { PairedDevice(it.address, it.name ?: "WF-1000XM5") }
        viewModelScope.launch {
            if (saved == null && bonded.size == 1) {
                Headphones.selectDevice(bonded.single().address, bonded.single().name)
                saved = bonded.single().address
            } else if (saved != null) Headphones.connectAndSync()
        }
    }

    fun onPermission(allowed: Boolean) { granted = allowed; if (allowed) onStart() }

    // ---- noise control ----
    /** Two-stage feedback: the tap ticks at once (UI); the earbuds' answer plays the mode's own pulse. */
    private var tapGeneration = 0

    fun requestMode(state: NcState) {
        // Choosing a mode yourself always wins over a running timer.
        if (ambientUntil != null) { AmbientTimer.cancel(ctx); ambientUntil = null }
        val before = Headphones.state.value.shownNc?.mode
        val receipt = Headphones.request(state)
        val mine = ++tapGeneration
        viewModelScope.launch {
            val ok = receipt.await()
            if (!haptics || mine != tapGeneration) return@launch
            if (ok) Haptics.confirm(ctx, if (state.mode != before) state.mode else null) else Haptics.reject(ctx)
        }
    }

    fun requestMode(mode: Mode) = requestMode((Headphones.state.value.nc ?: NcState(mode)).copy(mode = mode))

    fun setSpeakToChat(on: Boolean) = viewModelScope.launch {
        val ok = Headphones.writeAdvancedField(AdvancedSettings.Key.CHAT, 0, if (on) 0 else 1)
        if (haptics) if (ok) Haptics.confirm(ctx, null) else Haptics.reject(ctx)
        if (!ok) show("Speak-to-Chat change not confirmed. Try again.")
    }

    /** Starts the timer. Returns true when the countdown could also show in the status bar. */
    fun ambientForAMoment(): Boolean {
        val from = Headphones.state.value.shownNc ?: NcState(Mode.ANC)
        AmbientTimer.start(ctx, ambientMinutes, from)
        watchAmbient()
        return notificationsAllowed()
    }

    fun stayInAmbient() { AmbientTimer.cancel(ctx); ambientUntil = null }

    /** Follows the timer's end (or a cancel from the widget or notification) while the app is open. */
    private fun watchAmbient() {
        ambientUntil = AmbientTimer.until(ctx)
        ambientWatch?.cancel()
        val until = ambientUntil ?: return
        ambientWatch = viewModelScope.launch {
            delay(until - System.currentTimeMillis() + 300)
            ambientUntil = AmbientTimer.until(ctx)
        }
    }

    // ---- device info ----
    fun connect() = viewModelScope.launch { Headphones.connectAndSync() }
    fun refreshInfo() = viewModelScope.launch { Headphones.refreshInfo() }

    fun readDeviceInfo() = viewModelScope.launch {
        Headphones.readFirmware()
        Headphones.readAdvanced(AdvancedSettings.Key.QUALITY)
    }

    fun preferQuality() = viewModelScope.launch {
        val ok = Headphones.writeAdvancedField(AdvancedSettings.Key.QUALITY, 0, 0)
        show(if (ok) "Sound quality preferred. LDAC can be used after the earbuds reconnect." else "Change not confirmed. Try again.")
    }

    fun turnOff() = viewModelScope.launch {
        turningOff = true
        try { if (!Headphones.powerOff()) show("The earbuds didn't confirm. They may still be on.") }
        finally { turningOff = false }
    }

    // ---- app settings ----
    fun select(picked: PairedDevice, then: () -> Unit) = viewModelScope.launch {
        LiveLink.forget(ctx); liveLink = false
        Headphones.selectDevice(picked.address, picked.name)
        saved = picked.address
        then()
        Headphones.connectAndSync()
    }

    fun rename(name: String) { displayName = name; Prefs.setDisplayName(ctx, name) }
    fun updateDynamicColor(on: Boolean) { dynamicColor = on; Prefs.setDynamicColor(ctx, on) }
    fun updateHaptics(on: Boolean) { haptics = on; Prefs.setHaptics(ctx, on) }
    fun updateOnConnect(value: OnConnect) { onConnect = value; Prefs.setOnConnect(ctx, value) }
    fun updateTileTwoWay(on: Boolean) { tileTwoWay = on; Prefs.setTileTwoWay(ctx, on) }
    fun updateAmbientMinutes(minutes: Int) { ambientMinutes = minutes; Prefs.setAmbientMinutes(ctx, minutes) }

    /** Turning a reminder on needs notifications; returns false when the UI must ask first. */
    fun setCaseReminder(on: Boolean): Boolean {
        if (on && !notificationsAllowed()) return false
        caseReminder = on; Prefs.setCaseReminder(ctx, on); return true
    }
    fun onCaseReminderPermission(allowed: Boolean) {
        caseReminder = allowed; Prefs.setCaseReminder(ctx, allowed)
        if (!allowed) show("Notifications are off for Earbuds, so the reminder can't be shown.")
    }

    fun setBudsReminder(on: Boolean): Boolean {
        if (on && !notificationsAllowed()) return false
        budsReminder = on; Prefs.setBudsReminder(ctx, on)
        if (!on) LowBattery.onAway(ctx)
        return true
    }
    fun onBudsReminderPermission(allowed: Boolean) {
        budsReminder = allowed; Prefs.setBudsReminder(ctx, allowed)
        if (!allowed) show("Notifications are off for Earbuds, so the heads-up can't be shown.")
    }

    // ---- "Stay in sync" ----
    fun liveLinkResult(ok: Boolean) {
        liveLink = ok
        if (ok && Prefs.linked(ctx) == true) Headphones.stay(true)
        else if (!ok) show("Couldn't link to the earbuds. Make sure they're connected and try again.")
    }
    fun liveLinkOff() { LiveLink.disable(ctx); liveLink = false }

    // ---- "At home" ----
    fun updateHomeMode(value: OnConnect?) { homeMode = value; Prefs.setHomeMode(ctx, value) }
    fun locationChanged() { placesTick++ }

    fun saveHereAsHome() = Places.currentLocation(ctx) { here ->
        if (here == null) show("Couldn't find where you are. Turn on location and try again.")
        else { Prefs.setHome(ctx, here.latitude, here.longitude); home = here.latitude to here.longitude; show("Home saved") }
        placesTick++
    }

    val homeSummary: String get() {
        @Suppress("UNUSED_EXPRESSION") placesTick // read again after permission or location changes
        return when {
            home == null -> "Set to where you are now"
            !Places.hasLocation(ctx) || !Places.hasBackground(ctx) -> "Needs location \"Allow all the time\""
            else -> "Saved · tap to set to where you are now"
        }
    }
}
