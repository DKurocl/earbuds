package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

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
    val codec: CodecProtocol.State? = null,
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

    private const val CMD = SonyProtocol.TYPE_COMMAND.toInt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes connection setup and command sequences on the wire. */
    private val lock = Mutex()
    private lateinit var app: Context

    private val _state = MutableStateFlow(DeviceState())
    val state = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()
    private val _features = MutableStateFlow<Map<DeviceSettingsProtocol.Feature, Boolean>>(emptyMap())
    val features = _features.asStateFlow()
    private val _advanced = MutableStateFlow<Map<AdvancedSettings.Key, List<Int>>>(emptyMap())
    val advanced = _advanced.asStateFlow()
    private val _equalizer = MutableStateFlow<EqualizerProtocol.State?>(null)
    val equalizer = _equalizer.asStateFlow()

    /** Subtype that each advanced setting last answered with (quality has two variants). */
    private val advancedInquiries = ConcurrentHashMap<AdvancedSettings.Key, Int>()
    private val advancedLocks = ConcurrentHashMap<AdvancedSettings.Key, Mutex>()
    private val featureLocks = ConcurrentHashMap<DeviceSettingsProtocol.Feature, Mutex>()
    private val equalizerLock = Mutex()
    private val batteryLock = Mutex()
    private val codecLock = Mutex()

    // ---- reply matching ---------------------------------------------------------------
    /** A caller waiting for the first incoming frame that [accepts]. */
    private class Waiter(val accepts: (type: Int, payload: ByteArray) -> Boolean) {
        val reply = CompletableDeferred<ByteArray?>()
    }
    private val waiters = CopyOnWriteArrayList<Waiter>()

    /**
     * Registers a waiter, runs [send] under the wire lock and waits for the matching frame.
     * The waiter exists before anything is sent, so a fast reply cannot be missed.
     * Returns null when sending failed, the link closed or nothing matched in time.
     */
    private suspend fun exchange(
        timeoutMs: Long,
        connect: Boolean = true,
        accepts: (type: Int, payload: ByteArray) -> Boolean,
        send: suspend () -> Boolean,
    ): ByteArray? {
        val waiter = Waiter(accepts)
        waiters += waiter
        try {
            val sent = lock.withLock {
                val ready = if (connect) ensureConnectedLocked() else conn.connected
                ready && send()
            }
            return if (sent) withTimeoutOrNull(timeoutMs) { waiter.reply.await() } else null
        } finally {
            waiters -= waiter
        }
    }

    // ---- mode requests ----------------------------------------------------------------
    private data class ModeRequest(val id: Long, val state: SonyProtocol.NcState, val delivered: CompletableDeferred<Boolean>)
    private val modeVersion = AtomicLong()
    private val requests = Channel<ModeRequest>(Channel.CONFLATED, onUndeliveredElement = { it.delivered.complete(false) })
    private var activeRequest: ModeRequest? = null

    // ---- background reads while a screen is open ------------------------------------
    private var infoJob: Job? = null
    /** Set when the earbuds push changes on their own; polling then becomes a slow fallback. */
    @Volatile private var codecPushed = false
    @Volatile private var batteryPushed = false

    private val usage = ConnectionUse()
    val idle get() = !usage.active && !conn.connected && infoJob?.isActive != true && _state.value.pending == null

    private val conn = SonyConnection(scope, ::addLog, ::onPayload) {
        _state.update {
            it.copy(status = Status.DISCONNECTED, ncConfirmed = false, codec = null, liveBatteryParts = emptySet())
        }
        clearDeviceSettings()
        waiters.forEach { it.reply.complete(null) }
    }

    init {
        scope.launch {
            for (requested in requests) {
                try {
                    delay(45) // lets a burst of taps settle on the last one
                    if (modeVersion.get() == requested.id) apply(requested.state, requested.id) { requested.delivered.complete(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog("✗ mode: ${e.message}")
                    _state.update { it.copy(error = "Could not change mode. Try again.") }
                } finally {
                    requested.delivered.complete(false)
                    synchronized(this@Headphones) {
                        if (modeVersion.get() == requested.id) {
                            _state.update { it.copy(pending = null) }
                            activeRequest = null
                        }
                    }
                }
            }
        }
    }

    @Synchronized fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            // Show the last known levels at once; they stay dimmed until the earbuds answer.
            val battery = Prefs.lastBattery(app)
            _state.update { it.copy(nc = Prefs.lastNc(app), battery = battery?.first, batterySeenAt = battery?.second) }
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

    // ---- connection ownership -----------------------------------------------------------
    @Synchronized fun hold(foreground: Boolean = true) { usage.acquire(foreground) }

    @Synchronized fun release(foreground: Boolean = true) {
        usage.release(foreground)
        if (!usage.foreground) { infoJob?.cancel(); infoJob = null }
        if (!usage.active) { cancelModes(); conn.close() }
    }

    private fun clearDeviceSettings() {
        _features.value = emptyMap()
        _advanced.value = emptyMap()
        advancedInquiries.clear()
        _equalizer.value = null
    }

    private suspend fun ensureConnectedLocked(): Boolean {
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
            it.copy(status = Status.CONNECTING, ncConfirmed = false, liveBatteryParts = emptySet(), codec = null, firmware = null)
        }
        clearDeviceSettings()
        codecPushed = false
        batteryPushed = false
        try {
            if (!conn.connect(dev) { usage.active } || !conn.send(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))) {
                conn.close()
                if (!usage.active) return false
                return fail("Could not connect. Check that your earbuds are connected to this phone.")
            }
        } catch (e: CancellationException) {
            conn.close(); throw e
        } catch (e: Exception) {
            addLog("✗ ${e.message}")
            return fail("Could not connect to the earbuds")
        }
        if (!usage.active) { conn.close(); return false }
        _state.update { it.copy(status = Status.CONNECTED, error = null) }
        // A working control link proves Android's Bluetooth link is up.
        if (Prefs.linked(app) != true) { Prefs.setLinked(app, true); Widgets.refreshAll(app) }
        startInfoLoop()
        return true
    }

    private fun audioLinked(adapter: android.bluetooth.BluetoothAdapter): Boolean = runCatching {
        adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED ||
            adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED
    }.getOrDefault(true)

    private fun fail(message: String): Boolean {
        _state.update { it.copy(status = Status.FAILED, error = message) }
        return false
    }

    /** Drops queued and running mode requests; the next action reconnects on demand. */
    @Synchronized private fun cancelModes() {
        modeVersion.incrementAndGet()
        activeRequest?.delivered?.complete(false)
        activeRequest = null
        _state.update { it.copy(pending = null) }
        while (true) {
            val old = requests.tryReceive().getOrNull() ?: break
            old.delivered.complete(false)
        }
    }

    fun disconnect() {
        cancelModes()
        infoJob?.cancel(); infoJob = null
        scope.launch { lock.withLock { conn.close() } }
    }

    suspend fun connectAndSync(): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) return@withLock false
        // Mode controls never wait behind a state read.
        if (_state.value.pending == null && !conn.send(SonyProtocol.NC_GET)) return@withLock false
        startInfoLoop()
        conn.connected
    }

    /**
     * One-shot sync when Android reports the earbuds connected. Holds a background lease only:
     * no polling starts, and the control socket closes on return unless a screen is open.
     * Applies [apply] if given, then reads the battery so widgets and the cache are current.
     */
    suspend fun syncOnConnect(apply: SonyProtocol.Mode?, scene: Scene? = null): Boolean {
        hold(foreground = false)
        try {
            var ok = false
            for (attempt in 0..1) {
                // The earbuds can refuse RFCOMM while A2DP/HFP are still being set up.
                if (attempt > 0) delay(1500)
                ok = connectAndSync()
                if (ok || usage.foreground) break
            }
            if (!ok) {
                // A background attempt must not leave an error banner for the next launch.
                if (!usage.foreground) _state.update {
                    it.copy(error = null, status = if (it.status == Status.FAILED) Status.DISCONNECTED else it.status)
                }
                return false
            }
            if (scene != null) withTimeoutOrNull(5000) { applyScene(scene) }
            else if (apply != null) {
                val base = _state.value.shownNc ?: SonyProtocol.NcState(apply)
                withTimeoutOrNull(4000) { request(base.copy(mode = apply)).await() }
            }
            withTimeoutOrNull(3000) { queryBattery() }
            return true
        } finally {
            release(foreground = false)
        }
    }

    /** Reads codec and battery now, connecting first if needed. */
    suspend fun refreshInfo() {
        if (_state.value.connected || connectAndSync()) {
            queryCodec()
            queryBattery()
        }
    }

    // ---- noise control ------------------------------------------------------------------
    @Synchronized fun request(state: SonyProtocol.NcState): Deferred<Boolean> {
        val current = _state.value
        activeRequest?.takeIf { current.pending == state && it.state == state }?.let { return it.delivered }
        if (current.pending == null && current.ncConfirmed && current.nc == state && conn.connected) return CompletableDeferred(true)
        val request = ModeRequest(modeVersion.incrementAndGet(), state, CompletableDeferred())
        _state.update { it.copy(pending = state, error = null) }
        activeRequest = request
        requests.trySend(request)
        return request.delivered
    }

    /**
     * Applies a scene: noise control first (it is what the user hears change), then the
     * equalizer preset if the scene has one. True only when every part was confirmed.
     */
    suspend fun applyScene(scene: Scene): Boolean {
        val delivered = request(scene.target(_state.value.shownNc)).await()
        val preset = scene.eqPreset ?: return delivered
        if (_equalizer.value == null && readEqualizer() == null) return false
        return delivered && (_equalizer.value?.preset == preset || writeEqualizer(preset = preset))
    }

    private suspend fun apply(state: SonyProtocol.NcState, id: Long, onDelivered: (Boolean) -> Unit): Boolean = lock.withLock {
        if (!ensureConnectedLocked() || modeVersion.get() != id) { onDelivered(false); return@withLock false }
        _state.value.let { if (it.ncConfirmed && it.nc == state) { onDelivered(true); return@withLock true } }
        var ok = conn.send(SonyProtocol.ncSet(state))
        // A cached socket can look connected after the earbuds have dropped it.
        // Reconnect once for the latest choice; never replay a superseded choice.
        if (!ok && modeVersion.get() == id && ensureConnectedLocked() && modeVersion.get() == id) ok = conn.send(SonyProtocol.ncSet(state))
        if (!ok) {
            onDelivered(false)
            _state.update { it.copy(error = "The earbuds did not confirm the command. Try again.") }
            return@withLock false
        }
        setNc(state, confirmed = false)
        onDelivered(true) // Widget lifetime depends on delivery, not on the optional read-back.
        if (modeVersion.get() != id) return@withLock true
        // Read back while still holding the wire, so the next command waits for the device.
        val waiter = Waiter { type, p -> type == CMD && SonyProtocol.parseNc(p) == state }
        waiters += waiter
        try {
            if (conn.send(SonyProtocol.NC_GET)) withTimeoutOrNull(900) { waiter.reply.await() }
        } finally {
            waiters -= waiter
        }
        true
    }

    suspend fun selectDevice(address: String, name: String?) = lock.withLock {
        cancelModes()
        conn.close()
        Prefs.saveDevice(app, address, name)
        Prefs.clearBattery(app)
        _state.value = DeviceState()
        clearDeviceSettings()
        ModeWidget.refreshAll(app)
    }

    private fun setNc(state: SonyProtocol.NcState, confirmed: Boolean) {
        _state.update { it.copy(nc = state, ncConfirmed = confirmed) }
        Prefs.saveNc(app, state)
        ModeWidget.refreshAll(app)
    }

    /** Waits until no mode change is in flight, so reads never delay a tap. */
    private suspend fun awaitModeIdle(timeoutMs: Long = 2200) =
        withTimeoutOrNull(timeoutMs) { _state.first { it.pending == null } } != null

    // ---- codec and battery ----------------------------------------------------------------
    /**
     * While a screen is open: read codec and battery once, then rely on the earbuds' own
     * change notifications. Polling stays only as a slow fallback for firmware that does not
     * push, and backs off when a part (for example a case without a reading) stays unknown.
     */
    @Synchronized private fun startInfoLoop() {
        if (!usage.foreground || infoJob?.isActive == true) return
        infoJob = scope.launch {
            delay(400)
            var codecDue = 0L
            var batteryDue = 0L
            var batteryRetry = 8_000L
            var equalizerAsked = false
            while (usage.foreground && conn.connected) {
                _state.first { it.pending == null || !it.connected }
                if (!usage.foreground || !conn.connected) break
                if (now() >= codecDue) {
                    queryCodec()
                    codecDue = now() + if (codecPushed) 120_000L else 30_000L
                }
                if (!usage.foreground || !conn.connected) break
                // One equalizer read per connection, so Home and scenes know the current preset.
                if (_equalizer.value == null && !equalizerAsked) { equalizerAsked = true; readEqualizer() }
                if (!usage.foreground || !conn.connected) break
                if (now() >= batteryDue) {
                    queryBattery()
                    val complete = _state.value.liveBatteryParts.containsAll(setOf(0, 1, 2))
                    batteryRetry = if (complete) 8_000L else (batteryRetry * 2).coerceAtMost(60_000L)
                    batteryDue = now() + when {
                        batteryPushed -> 180_000L
                        complete -> 60_000L
                        else -> batteryRetry
                    }
                }
                val wait = (minOf(codecDue, batteryDue) - now()).coerceAtLeast(1_000L)
                withTimeoutOrNull(wait) { _state.first { !it.connected } }
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    suspend fun queryCodec(): CodecProtocol.State? = codecLock.withLock {
        _state.update { it.copy(codecRefreshing = true) }
        try {
            if (!awaitModeIdle()) return@withLock null
            exchange(900, connect = false, accepts = { type, p -> type == CMD && CodecProtocol.parse(p) != null }) {
                conn.send(CodecProtocol.get)
            }?.let(CodecProtocol::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("✗ codec: ${e.message}"); null
        } finally {
            _state.update { it.copy(codecRefreshing = false) }
        }
    }

    private fun batteryFamily(p: ByteArray) =
        if (SonyProtocol.parseBattery(p, SonyProtocol.Battery()) == null) null else if (p[1].toInt() == 0x0A) 2 else 1

    suspend fun queryBattery() = batteryLock.withLock {
        _state.update { it.copy(batteryRefreshing = true) }
        var received = false
        try {
            // 01 and 09 are both documented Sony V2 dual-battery inquiry formats; 0A is the case.
            for (q in SonyProtocol.BATTERY_GETS) {
                val family = if (q[1].toInt() == 0x0A) 2 else 1
                if (family == 1 && received && _state.value.liveBatteryParts.containsAll(setOf(0, 1))) continue
                if (!awaitModeIdle()) continue
                val reply = exchange(900, connect = false, accepts = { type, p -> type == CMD && batteryFamily(p) == family }) {
                    conn.send(q)
                }
                if (reply != null && SonyProtocol.liveBatteryParts(reply).isNotEmpty()) received = true
                yield()
            }
            if (!received) addLog("Battery: no decoded reply; requested 22 01 / 22 09 / 22 0A")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("✗ battery: ${e.message}")
        } finally {
            _state.update { it.copy(batteryRefreshing = false) }
        }
    }

    // ---- device settings -----------------------------------------------------------------
    private fun isAdvancedReply(key: AdvancedSettings.Key, type: Int, p: ByteArray) =
        AdvancedSettings.parse(type, p)?.first == key && (p[0].toInt() and 255) == key.getCode + 1

    suspend fun readAdvanced(key: AdvancedSettings.Key): List<Int>? = advancedLocks.getOrPut(key) { Mutex() }.withLock {
        val subtypes = if (key == AdvancedSettings.Key.QUALITY) listOf(0, 2) else listOf(key.subtype)
        for (subtype in subtypes) {
            val reply = exchange(1000, accepts = { type, p -> isAdvancedReply(key, type, p) }) {
                conn.send(byteArrayOf(key.getCode.toByte(), subtype.toByte()), key.type)
            }
            if (reply != null) return@withLock AdvancedSettings.parse(key.type.toInt(), reply)?.second
            if (!_state.value.connected) break
        }
        null
    }

    suspend fun writeAdvancedField(key: AdvancedSettings.Key, index: Int, code: Int): Boolean = advancedLocks.getOrPut(key) { Mutex() }.withLock {
        var wanted: List<Int>? = null
        val reply = exchange(1200, connect = false, accepts = { type, p -> isAdvancedReply(key, type, p) }) {
            // Patch the newest device values so other fields are preserved.
            val current = _advanced.value[key] ?: return@exchange false
            val next = AdvancedSettings.patch(key, current, index, code) ?: return@exchange false
            val subtype = advancedInquiries[key] ?: key.subtype
            wanted = next
            conn.send(key.set(next, subtype), key.type) &&
                conn.send(byteArrayOf(key.getCode.toByte(), subtype.toByte()), key.type)
        }
        val target = wanted ?: return@withLock false
        AdvancedSettings.equivalent(key, reply?.let { AdvancedSettings.parse(key.type.toInt(), it)?.second }, target)
    }

    suspend fun readFirmware() = lock.withLock { if (ensureConnectedLocked()) conn.send(byteArrayOf(0x04, 0x02)) else false }

    suspend fun powerOff(): Boolean = lock.withLock {
        cancelModes()
        if (!ensureConnectedLocked()) return@withLock false
        val delivered = conn.send(byteArrayOf(0x24, 0x03, 0x01))
        conn.close()
        delivered
    }

    private fun isFeatureReply(feature: DeviceSettingsProtocol.Feature, type: Int, p: ByteArray) =
        type == CMD && DeviceSettingsProtocol.parse(p)?.first == feature && (p[0].toInt() and 255) in listOf(0xE7, 0xF7)

    suspend fun readFeature(feature: DeviceSettingsProtocol.Feature): Boolean? = featureLocks.getOrPut(feature) { Mutex() }.withLock {
        exchange(1400, accepts = { type, p -> isFeatureReply(feature, type, p) }) { conn.send(feature.get) }
            ?.let { DeviceSettingsProtocol.parse(it)?.second }
    }

    suspend fun writeFeature(feature: DeviceSettingsProtocol.Feature, value: Boolean): Boolean = featureLocks.getOrPut(feature) { Mutex() }.withLock {
        val reply = exchange(1400, connect = false, accepts = { type, p -> isFeatureReply(feature, type, p) }) {
            feature in _features.value && conn.send(feature.set(value)) && conn.send(feature.get)
        }
        reply != null && DeviceSettingsProtocol.parse(reply)?.second == value
    }

    private fun isEqualizerReply(type: Int, p: ByteArray) =
        type == CMD && (p[0].toInt() and 255) == 0x57 && EqualizerProtocol.parse(p) != null

    suspend fun readEqualizer(): EqualizerProtocol.State? = equalizerLock.withLock {
        exchange(1400, accepts = ::isEqualizerReply) { conn.send(EqualizerProtocol.get) }?.let(EqualizerProtocol::parse)
    }

    suspend fun writeEqualizer(preset: Int? = null, bands: List<Int>? = null): Boolean = equalizerLock.withLock {
        val payload = if (bands != null) EqualizerProtocol.custom(bands) else EqualizerProtocol.preset(requireNotNull(preset))
        val actual = exchange(1400, connect = false, accepts = ::isEqualizerReply) {
            _equalizer.value != null && conn.send(payload) && conn.send(EqualizerProtocol.get)
        }?.let(EqualizerProtocol::parse) ?: return@withLock false
        if (bands != null) actual.preset == 0xA0 && actual.bands == bands else actual.preset == preset
    }

    // ---- incoming frames ---------------------------------------------------------------------
    private fun onPayload(type: Int, p: ByteArray) {
        decode(type, p)
        for (w in waiters) if (!w.reply.isCompleted && w.accepts(type, p)) w.reply.complete(p)
    }

    /** Updates state from any reply or notification. Waiters are matched separately. */
    private fun decode(type: Int, p: ByteArray) {
        val op = p.firstOrNull()?.toInt()?.and(255) ?: return
        if (type == CMD) AdvancedSettings.firmware(p)?.let { fw -> _state.update { it.copy(firmware = fw) }; return }
        AdvancedSettings.parse(type, p)?.let { (key, value) ->
            advancedInquiries[key] = p[1].toInt() and 255
            _advanced.update { it + (key to value) }
            return
        }
        if (type != CMD) return
        CodecProtocol.parse(p)?.let { codec ->
            if (op == 0x15) codecPushed = true
            _state.update { it.copy(codec = codec) }
            return
        }
        DeviceSettingsProtocol.parse(p)?.let { (feature, value) -> _features.update { it + (feature to value) }; return }
        EqualizerProtocol.parse(p)?.let { _equalizer.value = it; return }
        SonyProtocol.parseNc(p)?.let { setNc(it, confirmed = true); return }
        val live = SonyProtocol.liveBatteryParts(p)
        if (batteryFamily(p) != null) {
            if (op == 0x25) batteryPushed = true
            if (live.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val updated = _state.updateAndGet {
                    val merged = SonyProtocol.parseBattery(p, it.battery ?: SonyProtocol.Battery()) ?: it.battery
                    it.copy(battery = merged, batterySeenAt = now, liveBatteryParts = it.liveBatteryParts + live)
                }
                updated.battery?.let { Prefs.saveBattery(app, it, now); Widgets.refreshAll(app) }
            }
        }
    }
}
