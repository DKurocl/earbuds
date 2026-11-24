package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Everything the UI, widget and tile show about the link, as one snapshot. */
data class DeviceState(
    val status: Headphones.Status = Headphones.Status.DISCONNECTED,
    /** Last known noise-control state (persisted), or the device's confirmed one. */
    val nc: SonyProtocol.NcState? = null,
    /** A mode request that has not finished yet. */
    val pending: SonyProtocol.NcState? = null,
    /** True once the earbuds themselves reported [nc] on this connection. */
    val ncConfirmed: Boolean = false,
    val error: String? = null,
    val battery: SonyProtocol.Battery? = null,
    /** Wall-clock time of the last real battery reading (also restored from the previous session). */
    val batterySeenAt: Long? = null,
    /** Parts (0 left, 1 right, 2 case) whose level was read on this connection. */
    val liveBatteryParts: Set<Int> = emptySet(),
    val batteryRefreshing: Boolean = false,
    /** The codec in use, or the last one used (see [codecLive]); kept across links. */
    val codec: CodecProtocol.State? = null,
    /** True once the earbuds reported [codec] on this connection. */
    val codecLive: Boolean = false,
    val codecRefreshing: Boolean = false,
    val firmware: String? = null,
) {
    val connected get() = status == Headphones.Status.CONNECTED
    /** The mode to show: a pending choice wins over the last reported one. */
    val shownNc get() = pending ?: nc
}

/** One on-demand link shared by the screen, widget and tile; no persistent service. */
// The only stored context is ctx.applicationContext; no Activity is retained.
@SuppressLint("MissingPermission", "StaticFieldLeak")
object Headphones {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    internal const val CMD = SonyProtocol.TYPE_COMMAND.toInt()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val commands = CommandScheduler()
    internal lateinit var app: Context

    internal val _state = MutableStateFlow(DeviceState())
    val state = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()

    internal suspend fun exchange(
        timeoutMs: Long,
        connect: Boolean = true,
        priority: CommandScheduler.Priority = CommandScheduler.Priority.NORMAL,
        readyToRead: () -> Boolean = { true },
        accepts: (type: Int, payload: ByteArray) -> Boolean,
        prepare: suspend () -> Boolean = { true },
        send: suspend () -> Boolean,
    ): ByteArray? = commands.run(priority) {
        if (!readyToRead()) return@run null
        val ready = if (connect) ensureConnectedLocked() else conn.connected
        if (ready && prepare()) commands.readLocked(timeoutMs, accepts, send) else null
    }

    internal val usage = ConnectionUse()
    val idle get() = !usage.active && !conn.connected && DeviceReads.infoJob?.isActive != true && _state.value.pending == null

    internal val conn = SonyConnection(scope, ::addLog, ::onPayload) {
        _state.update {
            it.copy(status = Status.DISCONNECTED, ncConfirmed = false, codecLive = false,
                liveBatteryParts = emptySet(), firmware = null)
        }
        EarbudSettingsLink.clearDeviceSettings()
        DeviceReads.invalidate()
        commands.disconnected()
    }


    @Synchronized fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            // Show the last known levels at once; they stay dimmed until the earbuds answer.
            val battery = Prefs.lastBattery(app)
            _state.update {
                it.copy(nc = Prefs.lastNc(app), battery = battery?.first, batterySeenAt = battery?.second,
                    codec = Prefs.lastCodec(app)?.let(CodecProtocol::State))
            }
        }
    }

    fun addLog(line: String) = _log.update { (it + line).takeLast(120) }
    fun clearLog() { _log.value = emptyList() }
    fun clearError() = _state.update { it.copy(error = null) }

    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    fun bondedDevices(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        return runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { it.address == Prefs.deviceAddress(ctx) || it.name?.contains("WF-1000XM5", ignoreCase = true) == true }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun savedDevice(): BluetoothDevice? = bondedDevices(app).firstOrNull { it.address == Prefs.deviceAddress(app) }


    // ---- public API: the parts live in ModeControl, DeviceReads and EarbudSettingsLink ------
    val features get() = EarbudSettingsLink.features
    val advanced get() = EarbudSettingsLink.advanced
    val equalizer get() = EarbudSettingsLink.equalizer
    fun request(state: SonyProtocol.NcState): Deferred<Boolean> = ModeControl.request(state)
    suspend fun queryCodec() = DeviceReads.queryCodec()
    suspend fun queryBattery() = DeviceReads.queryBattery()
    suspend fun readAdvanced(key: AdvancedSettings.Key) = EarbudSettingsLink.readAdvanced(key)
    suspend fun writeAdvancedField(key: AdvancedSettings.Key, index: Int, code: Int) = EarbudSettingsLink.writeAdvancedField(key, index, code)
    suspend fun readFeature(feature: DeviceSettingsProtocol.Feature) = EarbudSettingsLink.readFeature(feature)
    suspend fun writeFeature(feature: DeviceSettingsProtocol.Feature, value: Boolean) = EarbudSettingsLink.writeFeature(feature, value)
    suspend fun readEqualizer() = EarbudSettingsLink.readEqualizer()
    suspend fun writeEqualizer(preset: Int? = null, bands: List<Int>? = null) = EarbudSettingsLink.writeEqualizer(preset, bands)

    // ---- connection ownership -----------------------------------------------------------
    @Synchronized fun hold(foreground: Boolean = true) { usage.acquire(foreground) }

    @Synchronized fun release(foreground: Boolean = true) {
        usage.release(foreground)
        if (!usage.foreground) DeviceReads.stopInfoLoop()
        if (!usage.active) { ModeControl.cancelModes(); conn.close() }
    }

    // ---- staying connected while the earbuds are present (LiveLink) ---------------------
    private var stayJob: Job? = null
    /** Battery read on the open channel for firmware that does not push its levels. */
    private const val STAY_BATTERY_MS = 15 * 60_000L

    /** Android reports the companion earbuds connected (true) or gone (false). */
    @Synchronized fun stay(on: Boolean) {
        if (on == usage.staying) return
        usage.stay(on)
        stayJob?.cancel()
        stayJob = null
        if (on) stayJob = scope.launch { keepLinked() }
        else if (!usage.active) { ModeControl.cancelModes(); conn.close() }
        ActivityMode.update(app) // "Ambient while walking" listens only while the earbuds are here
    }

    val staying get() = usage.staying

    /**
     * Keeps the control channel open while the earbuds are out of the case: reconnects with a
     * backoff when it drops and otherwise waits. The earbuds' own notifications update the mode
     * and battery (decode); only firmware that never pushes its battery gets a slow read.
     */
    private suspend fun keepLinked() {
        // Let Android finish A2DP/HFP before the control channel, as ConnectionReceiver does.
        delay(ConnectionReceiver.SETTLE_MS)
        var backoff = 2_000L
        var batteryDue = DeviceReads.now() + STAY_BATTERY_MS
        while (usage.staying) {
            if (!_state.value.connected) {
                if (!connectAndSync()) {
                    // A background attempt must not leave an error banner for the next launch.
                    if (!usage.foreground) _state.update {
                        it.copy(error = null, status = if (it.status == Status.FAILED) Status.DISCONNECTED else it.status)
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                    continue
                }
                backoff = 2_000L
                withTimeoutOrNull(3000) { DeviceReads.queryBattery(priority = CommandScheduler.Priority.BACKGROUND) }
                batteryDue = DeviceReads.now() + STAY_BATTERY_MS
            }
            val wait = if (DeviceReads.batteryPushed) Long.MAX_VALUE else (batteryDue - DeviceReads.now()).coerceAtLeast(1_000L)
            val dropped = withTimeoutOrNull(wait) { _state.first { !it.connected } } != null
            // A screen polls on its own (startInfoLoop); in the background read once per period.
            if (!dropped && usage.staying && !DeviceReads.batteryPushed) {
                if (!usage.foreground) DeviceReads.queryBattery()
                batteryDue = DeviceReads.now() + STAY_BATTERY_MS
            }
        }
    }


    internal suspend fun ensureConnectedLocked(): Boolean {
        if (!usage.active) return false
        if (conn.connected) return true
        if (!hasPermission(app)) return fail("Allow Bluetooth access")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) return fail("Turn on Bluetooth")
        val dev = savedDevice() ?: return fail("Select your WF-1000XM5")
        // Earbuds in the case: say so at once instead of waiting seconds for RFCOMM to time out.
        // Only when Android reports no audio link and no connect broadcast says otherwise.
        if (Prefs.linked(app) != true && !audioLinked(adapter)) return fail("Earbuds aren't connected. Take them out of the case.")
        _state.update {
            it.copy(status = Status.CONNECTING, ncConfirmed = false, liveBatteryParts = emptySet(), codecLive = false, firmware = null)
        }
        val generation: Long
        try {
            val opened = conn.connect(dev) { usage.active }
            generation = conn.generation
            if (!opened || !conn.send(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))) {
                conn.close()
                if (!usage.active) return false
                return fail("Could not connect. Check that your earbuds are connected to this phone.")
            }
        } catch (e: CancellationException) {
            conn.close(); throw e
        } catch (e: Exception) {
            conn.close()
            addLog("✗ ${e.message}")
            return fail("Could not connect to the earbuds")
        }
        if (!usage.active) { conn.close(); return false }
        return conn.ifConnected(generation) {
            _state.update { it.copy(status = Status.CONNECTED, error = null) }
            // A working control link proves Android's Bluetooth link is up.
            if (Prefs.linked(app) != true) { Prefs.setLinked(app, true); Widgets.refreshAll(app) }
            DeviceReads.startInfoLoop()
        }
    }

    private fun audioLinked(adapter: android.bluetooth.BluetoothAdapter): Boolean = runCatching {
        adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED ||
            adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED
    }.getOrDefault(true)

    private fun fail(message: String): Boolean {
        _state.update { it.copy(status = Status.FAILED, error = message) }
        return false
    }

    suspend fun connectAndSync(): Boolean = connectAndSync(CommandScheduler.Priority.NORMAL)

    private suspend fun connectAndSync(priority: CommandScheduler.Priority): Boolean = commands.run(priority) {
        if (!ensureConnectedLocked()) return@run false
        // Skip the initial read if a mode command is already waiting.
        if (_state.value.pending == null) ModeControl.readBackLocked()
        DeviceReads.startInfoLoop()
        conn.connected
    }

    /**
     * One-shot sync when Android reports the earbuds connected. Holds a background lease only:
     * no polling starts, and the control socket closes on return unless a screen is open.
     * Applies [apply] if given, then reads the battery so widgets and the cache are current.
     */
    suspend fun syncOnConnect(apply: SonyProtocol.Mode?): Boolean {
        hold(foreground = false)
        try {
            var ok = false
            for (attempt in 0..1) {
                // The earbuds can refuse RFCOMM while A2DP/HFP are still being set up.
                if (attempt > 0) delay(1500)
                ok = connectAndSync(CommandScheduler.Priority.BACKGROUND)
                if (ok || usage.foreground) break
            }
            if (!ok) {
                // A background attempt must not leave an error banner for the next launch.
                if (!usage.foreground) _state.update {
                    it.copy(error = null, status = if (it.status == Status.FAILED) Status.DISCONNECTED else it.status)
                }
                return false
            }
            if (apply != null) {
                val base = _state.value.shownNc ?: SonyProtocol.NcState(apply)
                withTimeoutOrNull(4000) { ModeControl.request(base.copy(mode = apply)).await() }
            }
            withTimeoutOrNull(3000) { DeviceReads.queryBattery(priority = CommandScheduler.Priority.BACKGROUND) }
            return true
        } finally {
            release(foreground = false)
        }
    }

    /** Reads codec and battery now, connecting first if needed. */
    suspend fun refreshInfo() {
        if (_state.value.connected || connectAndSync()) {
            DeviceReads.queryCodec()
            DeviceReads.queryBattery()
        }
    }


    suspend fun selectDevice(address: String, name: String?) = commands.run(CommandScheduler.Priority.CONTROL) {
        if (address.equals(Prefs.deviceAddress(app), ignoreCase = true)) {
            Prefs.saveDevice(app, address, name)
            return@run
        }
        ModeControl.cancelModes()
        conn.close()
        Prefs.replaceDevice(app, address, name)
        _state.value = DeviceState()
        EarbudSettingsLink.panelRefused.value = false
        Widgets.refreshAll(app)
    }


    suspend fun readFirmware() = commands.run { if (ensureConnectedLocked()) conn.send(byteArrayOf(0x04, 0x02)) else false }

    suspend fun powerOff(): Boolean = commands.run(CommandScheduler.Priority.CONTROL) {
        ModeControl.cancelModes()
        if (!ensureConnectedLocked()) return@run false
        val delivered = conn.send(byteArrayOf(0x24, 0x03, 0x01))
        conn.close()
        delivered
    }


    // ---- incoming frames ---------------------------------------------------------------------
    private fun onPayload(type: Int, p: ByteArray) {
        decode(type, p)
        commands.received(type, p)
    }

    /** Updates state from any reply or notification. Waiters are matched separately. */
    private fun decode(type: Int, p: ByteArray) {
        val op = p.firstOrNull()?.toInt()?.and(255) ?: return
        if (type == CMD) AdvancedSettings.firmware(p)?.let { fw -> _state.update { it.copy(firmware = fw) }; return }
        AdvancedSettings.parse(type, p)?.let { (key, value) ->
            EarbudSettingsLink.advancedInquiries[key] = p[1].toInt() and 255
            Prefs.cacheAdvanced(app, EarbudSettingsLink._advanced.updateAndGet { it + (key to value) })
            return
        }
        if (type != CMD) return
        CodecProtocol.parse(p)?.let { codec ->
            if (op == 0x15) DeviceReads.codecPushed = true
            _state.update { it.copy(codec = codec, codecLive = true) }
            Prefs.saveCodec(app, codec.code)
            return
        }
        DeviceSettingsProtocol.parse(p)?.let { (feature, value) -> Prefs.cacheFeatures(app, EarbudSettingsLink._features.updateAndGet { it + (feature to value) }); return }
        EqualizerProtocol.parse(p)?.let { EarbudSettingsLink._equalizer.value = it; Prefs.cacheEqualizer(app, it); return }
        SonyProtocol.parseNc(p)?.let { ModeControl.setNc(it); return }
        val live = SonyProtocol.liveBatteryParts(p)
        if (DeviceReads.batteryFamily(p) != null) {
            if (op == 0x25) DeviceReads.batteryNotification(live)
            if (live.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val updated = _state.updateAndGet {
                    val merged = SonyProtocol.parseBattery(p, it.battery ?: SonyProtocol.Battery()) ?: it.battery
                    it.copy(battery = merged, batterySeenAt = now, liveBatteryParts = it.liveBatteryParts + live)
                }
                updated.battery?.let { b ->
                    Prefs.saveBattery(app, b, now)
                    // Feed the owner's real drain model with the emptier earbud.
                    listOfNotNull(b.left, b.right).minOrNull()?.let { level ->
                        Prefs.addBatterySample(app, BatterySample(now, level, b.leftCharging || b.rightCharging, updated.nc?.mode, Prefs.session(app), b.case))
                    }
                    Widgets.refreshAll(app)
                    LowBattery.onReading(app, b, updated.nc?.mode)
                }
            }
        }
    }
}
