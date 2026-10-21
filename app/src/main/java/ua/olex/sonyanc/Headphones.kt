package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One on-demand link shared by the screen and widget; no persistent service. */
@SuppressLint("MissingPermission")
object Headphones {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var app: Context
    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status = _status.asStateFlow()
    private val _nc = MutableStateFlow<SonyProtocol.NcState?>(null)
    val nc = _nc.asStateFlow()
    private val _pending = MutableStateFlow<SonyProtocol.NcState?>(null)
    val pending = _pending.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _fresh = MutableStateFlow(false)
    val fresh = _fresh.asStateFlow()
    private val _battery = MutableStateFlow<SonyProtocol.Battery?>(null)
    val battery = _battery.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()
    private val requests = Channel<SonyProtocol.NcState>(Channel.CONFLATED)
    private val conn = SonyConnection(scope, ::addLog, ::onPayload) {
        _status.value = Status.DISCONNECTED
        _fresh.value = false
        _battery.value = null
    }
    private var holders = 0
    private var idleJob: Job? = null

    init {
        scope.launch {
            for (requested in requests) {
                // While a connect/ACK is in progress only the newest queued choice survives.
                apply(requested)
                if (_pending.value == requested) _pending.value = null
            }
        }
    }

    @Synchronized fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            _nc.value = Prefs.lastNc(app)
        }
    }
    fun addLog(line: String) = _log.update { (it + line).takeLast(120) }
    fun clearLog() { _log.value = emptyList() }
    fun clearError() { _error.value = null }
    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
    ) == PackageManager.PERMISSION_GRANTED

    fun bondedDevices(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        return runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { it.name?.contains("WF-1000XM5", ignoreCase = true) == true }
                .toList()
        }.getOrDefault(emptyList())
    }
    private fun savedDevice(): BluetoothDevice? = bondedDevices(app)
        .firstOrNull { it.address == Prefs.deviceAddress(app) }

    @Synchronized fun hold() { holders++; idleJob?.cancel() }
    @Synchronized fun release(graceMs: Long = 12000) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(graceMs)
                lock.withLock { synchronized(this@Headphones) { if (holders == 0) conn.close() } }
            }
        }
    }

    private suspend fun ensureConnectedLocked(): Boolean {
        if (conn.connected) return true
        if (!hasPermission(app)) return fail("Дозволь доступ до Bluetooth")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) return fail("Увімкни Bluetooth на телефоні")
        val dev = savedDevice() ?: return fail("Вибери свої WF-1000XM5")
        _status.value = Status.CONNECTING
        _fresh.value = false
        _battery.value = null
        try {
            if (!conn.connect(dev) || !conn.send(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))) {
                conn.close()
                return fail("Не вдалося підключитися. Перевір навушники та закрий Sony Sound Connect.")
            }
        } catch (e: CancellationException) { conn.close(); throw e }
        catch (e: Exception) { addLog("✗ ${e.message}"); return fail("Не вдалося підключитися до навушників") }
        _status.value = Status.CONNECTED
        _error.value = null
        return true
    }
    private fun fail(message: String): Boolean {
        _status.value = Status.FAILED
        _error.value = message
        return false
    }

    suspend fun connectAndSync(): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) return@withLock false
        // Mode controls never wait behind speculative battery probes.
        if (_pending.value == null) conn.send(SonyProtocol.NC_GET)
        true
    }

    fun request(state: SonyProtocol.NcState) {
        _error.value = null
        _pending.value = state
        requests.trySend(state)
    }

    suspend fun apply(state: SonyProtocol.NcState): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) return@withLock false
        val ok = conn.send(SonyProtocol.ncSet(state))
        if (ok) {
            setNc(state) // Only ACKed state becomes the last known widget state.
            // ACK acknowledges delivery; a read-back provides actual device state.
            _fresh.value = false
            conn.send(SonyProtocol.NC_GET)
        } else _error.value = "Режим не застосовано. Спробуй ще раз."
        ok
    }

    suspend fun selectDevice(address: String, name: String?) = lock.withLock {
        conn.close()
        Prefs.saveDevice(app, address, name)
        _nc.value = null
        _pending.value = null
        ModeWidget.refreshAll(app)
    }
    suspend fun queryBattery() = lock.withLock {
        if (ensureConnectedLocked()) for (q in SonyProtocol.BATTERY_GETS) {
            if (!conn.send(q)) break
        }
    }
    private fun setNc(state: SonyProtocol.NcState) {
        _nc.value = state
        Prefs.saveNc(app, state)
        ModeWidget.refreshAll(app)
    }
    private fun onPayload(p: ByteArray) {
        SonyProtocol.parseNc(p)?.let { setNc(it); _fresh.value = true; return }
        SonyProtocol.parseBattery(p, _battery.value ?: SonyProtocol.Battery())?.let { _battery.value = it }
    }
}
