package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
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
    var bluetoothOn by mutableStateOf(true); private set
    var saved by mutableStateOf(Prefs.deviceAddress(ctx)); private set
    var devices by mutableStateOf(emptyList<PairedDevice>()); private set
    var displayName by mutableStateOf(Prefs.displayName(ctx) ?: Prefs.deviceName(ctx) ?: "WF-1000XM5"); private set

    // ---- owner's settings ----
    var haptics by mutableStateOf(Prefs.haptics(ctx)); private set
    var dynamicColor by mutableStateOf(Prefs.dynamicColor(ctx)); private set
    var qualityByPlace by mutableStateOf(Prefs.qualityByPlace(ctx)); private set
    var afterSleep by mutableStateOf(Prefs.afterSleep(ctx)); private set
    fun updateAfterSleep(value: AfterSleep) { afterSleep = value; Prefs.setAfterSleep(ctx, value) }
    var chargeReminder by mutableStateOf(Prefs.chargeReminder(ctx)); private set
    fun chargeReminderChanged(on: Boolean) { chargeReminder = on; Prefs.setChargeReminder(ctx, on); ChargeReminder.reschedule(ctx) }
    var activityMode by mutableStateOf(Prefs.activityMode(ctx)); private set
    fun activityModeChanged(on: Boolean) { activityMode = on; Prefs.setActivityMode(ctx, on); ActivityMode.update(ctx) }
    fun updateQualityByPlace(on: Boolean) {
        qualityByPlace = on; Prefs.setQualityByPlace(ctx, on)
        // Applied now when the earbuds are connected, otherwise on the next connection.
        if (on) viewModelScope.launch { EarbudSettingsLink.keepQuality() }
    }
    var onConnect by mutableStateOf(Prefs.onConnect(ctx)); private set
    var caseReminder by mutableStateOf(Prefs.caseReminder(ctx)); private set
    var budsReminder by mutableStateOf(Prefs.budsReminder(ctx) && notificationsAllowed()); private set
    var tileTwoWay by mutableStateOf(Prefs.tileTwoWay(ctx)); private set
    var liveLink by mutableStateOf(LiveLink.enabled(ctx)); private set
    var connectSheet by mutableStateOf(Prefs.connectSheet(ctx)); private set
    var homeMode by mutableStateOf(Prefs.homeMode(ctx)); private set
    var home by mutableStateOf(Prefs.home(ctx)); private set
    /** Bumped when a location permission may have changed, so its summary is read again. */
    var placesTick by mutableIntStateOf(0); private set

    // ---- "Ambient for a moment" ----
    var ambientMinutes by mutableIntStateOf(Prefs.ambientMinutes(ctx)); private set
    var ambientUntil by mutableStateOf(AmbientTimer.until(ctx)); private set
    init {
        viewModelScope.launch { Prefs.timerChanges(ctx).collect { ambientUntil = it } }
    }

    var turningOff by mutableStateOf(false); private set

    // ---- vibrations ----
    var hapticTakes by mutableStateOf(Haptics.Cue.entries.associateWith { Prefs.hapticTake(ctx, it.name) }); private set
    fun tryHaptic(cue: Haptics.Cue, take: Int) {
        Prefs.setHapticTake(ctx, cue.name, take)
        hapticTakes = hapticTakes + (cue to take)
        Haptics.play(ctx, cue, Haptics.takes.getValue(cue)[take])
    }

    fun notificationsAllowed() = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun updateBluetoothState() {
        bluetoothOn = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }

    /** The screen came to the front: check access, pick the only pair, connect, pick up timers. */
    fun onStart(reconnect: Boolean = false) {
        granted = Headphones.hasPermission(ctx)
        if (!granted) return
        updateBluetoothState()
        val bonded = Headphones.bondedDevices(ctx)
        devices = bonded.map { PairedDevice(it.address, it.name ?: "WF-1000XM5") }
        viewModelScope.launch {
            if (saved == null && bonded.size == 1) {
                Headphones.selectDevice(bonded.single().address, bonded.single().name)
                saved = bonded.single().address
                Headphones.connectAndSync()
            } else if (reconnect && bluetoothOn && devices.any { it.address == saved }) {
                Headphones.connectAndSync()
            }
        }
    }

    fun onPermission(allowed: Boolean) { granted = allowed; if (allowed) onStart(reconnect = true) }

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
        ambientUntil = AmbientTimer.until(ctx)
        return notificationsAllowed()
    }

    fun stayInAmbient() { AmbientTimer.cancel(ctx); ambientUntil = null }

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

    var weekendMode by mutableStateOf(Prefs.weekendMode(ctx)); private set
    fun updateWeekendMode(mode: OnConnect?) {
        Prefs.setWeekendMode(ctx, mode)
        weekendMode = mode
    }

    var profileBusy by mutableStateOf(false); private set

    internal fun saveProfile(slot: Int) {
        if (profileBusy) return
        val state = Headphones.state.value
        val mode = state.nc?.takeIf { state.ncConfirmed && state.pending == null }
            ?: return show("Wait for your earbuds to confirm their current mode.")
        profileBusy = true
        viewModelScope.launch {
            try {
                val generation = Headphones.conn.generation
                val eq = Headphones.equalizer.value?.takeUnless { it.preset == 0xA0 && it.bands.size != 6 }
                val features = buildMap {
                    for (feature in PersonalProfile.supportedFeatures) {
                        val value = Headphones.features.value[feature] ?: Headphones.readFeature(feature)
                        if (value != null) put(feature, value)
                    }
                }
                if (generation != Headphones.conn.generation) {
                    show("Earbuds disconnected before the profile could be saved.")
                    return@launch
                }
                Prefs.savePersonalProfile(ctx, slot, PersonalProfile(mode, eq, features))
                show("Profile $slot saved.")
            } finally {
                profileBusy = false
            }
        }
    }

    internal fun applyProfile(slot: Int, profile: PersonalProfile) {
        if (profileBusy) return
        profileBusy = true
        viewModelScope.launch {
            try {
                stayInAmbient()
                val modeApplied = withTimeoutOrNull(8_500L) {
                    if (!Headphones.request(profile.noise).await()) return@withTimeoutOrNull false
                    val state = Headphones.state.first { it.pending == null }
                    state.ncConfirmed && state.nc?.let {
                        it.mode == profile.noise.mode &&
                            (it.mode != Mode.AMBIENT || it.level == profile.noise.level && it.voice == profile.noise.voice)
                    } == true
                } == true
                if (!modeApplied) {
                    show("The earbuds did not confirm the profile's noise control.")
                    return@launch
                }
                val eq = profile.equalizer
                val eqApplied = when {
                    eq == null -> true
                    eq.preset == 0xA0 -> Headphones.writeEqualizer(bands = eq.bands)
                    else -> Headphones.writeEqualizer(preset = eq.preset)
                }
                val unavailable = mutableListOf<String>()
                if (!eqApplied) unavailable += "Equalizer"
                for ((feature, value) in profile.features) {
                    if (!Headphones.writeFeature(feature, value)) unavailable += feature.title
                }
                show(if (unavailable.isEmpty()) "Profile $slot applied." else "Could not verify ${unavailable.joinToString()}.")
            } finally {
                profileBusy = false
            }
        }
    }

    fun exportSettings(uri: android.net.Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                checkNotNull(ctx.contentResolver.openOutputStream(uri, "wt")).use { it.write(SettingsBackup.encode(ctx)) }
            }
        }
        show(if (result.isSuccess) "Settings exported." else "Could not export settings.")
    }

    fun importSettings(uri: android.net.Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { checkNotNull(ctx.contentResolver.openInputStream(uri)).use { SettingsBackup.restore(ctx, it) } }
        }
        if (result.isFailure) return@launch show("Could not restore settings. Choose an Earbuds backup.")
        dynamicColor = Prefs.dynamicColor(ctx)
        haptics = Prefs.haptics(ctx)
        tileTwoWay = Prefs.tileTwoWay(ctx)
        onConnect = Prefs.onConnect(ctx)
        weekendMode = Prefs.weekendMode(ctx)
        ambientMinutes = Prefs.ambientMinutes(ctx)
        displayName = Prefs.displayName(ctx) ?: Prefs.deviceName(ctx) ?: "WF-1000XM5"
        caseReminder = Prefs.caseReminder(ctx)
        afterSleep = Prefs.afterSleep(ctx)
        hapticTakes = Haptics.Cue.entries.associateWith { Prefs.hapticTake(ctx, it.name) }
        Widgets.refreshAll(ctx)
        show("Settings restored.")
    }

    var diagnosticResult by mutableStateOf<String?>(null); private set
    var diagnosticRunning by mutableStateOf(false); private set

    fun runDiagnostics() {
        if (diagnosticRunning) return
        diagnosticRunning = true
        viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(8_000L) {
                    if (!Headphones.connectAndSync()) return@withTimeoutOrNull Headphones.state.value.error ?: "Control link unavailable."
                    val codec = Headphones.queryCodec()
                    Headphones.queryBattery()
                    val state = Headphones.state.value
                    listOf(
                        if (state.connected) "Control link ready" else "Control link lost during check",
                        if (state.ncConfirmed) "Noise control confirmed" else "Noise control unconfirmed",
                        codec?.takeIf { it.isKnown }?.label ?: "Codec unknown",
                        "${state.liveBatteryParts.size}/3 battery levels live",
                    ).joinToString(" · ")
                }
                diagnosticResult = result ?: "The connection check timed out."
            } finally {
                diagnosticRunning = false
            }
        }
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

    fun updateConnectSheet(on: Boolean) { connectSheet = on; Prefs.setConnectSheet(ctx, on) }

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
