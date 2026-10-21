package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide owner of the headphone link, shared by the activity and the widget.
 *
 * Users "hold" the link (activity while visible, widget for one tap). When the last
 * holder lets go, the socket closes a few seconds later: nothing runs in the
 * background, no service, no notification.
 */
@SuppressLint("MissingPermission")
object Headphones {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var app: Context

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _nc = MutableStateFlow<SonyProtocol.NcState?>(null)
    val nc: StateFlow<SonyProtocol.NcState?> = _nc.asStateFlow()
    private val _battery = MutableStateFlow<SonyProtocol.Battery?>(null)
    val battery: StateFlow<SonyProtocol.Battery?> = _battery.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val conn = SonyConnection(
        scope,
        log = ::addLog,
        onPayload = ::onPayload,
        onClosed = { _status.value = Status.DISCONNECTED },
    )

    private var holders = 0
    private var idleJob: Job? = null

    fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            Prefs.lastNc(app)?.let { _nc.value = it }
        }
    }

    fun addLog(line: String) = _log.update { (it + line).takeLast(300) }
    fun clearLog() { _log.value = emptyList() }

    fun hasPermission(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
                else Manifest.permission.BLUETOOTH
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun bondedDevices(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty().sortedByDescending {
            (it.name ?: "").contains(Regex("WH-|WF-|Sony|LinkBuds|ULT", RegexOption.IGNORE_CASE))
        }
    }

    fun savedDevice(ctx: Context): BluetoothDevice? {
        val addr = Prefs.deviceAddress(ctx) ?: return null
        return bondedDevices(ctx).firstOrNull { it.address == addr }
    }

    // ---- holding the link ------------------------------------------------------------

    @Synchronized fun hold() { holders++; idleJob?.cancel() }

    @Synchronized fun release(graceMs: Long = 4000) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(graceMs)
                lock.withLock { if (holders == 0) conn.close() }
            }
        }
    }

    /** Connects to the saved device if needed. Must be called with [lock] held. */
    private suspend fun ensureConnectedLocked(): Boolean {
        if (conn.connected) return true
        val dev = savedDevice(app) ?: run { _status.value = Status.FAILED; return false }
        _status.value = Status.CONNECTING
        val ok = conn.connect(dev)
        _status.value = if (ok) Status.CONNECTED else Status.FAILED
        if (ok) conn.send(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))
        return ok
    }

    /** Full sync for the app screen: connect, read NC state and battery. */
    suspend fun connectAndSync(): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) return@withLock false
        conn.send(SonyProtocol.NC_GET)
        for (q in SonyProtocol.BATTERY_GETS) conn.send(q)
        true
    }

    fun disconnect() = scope.launch { lock.withLock { conn.close() } }

    /** Applies a NC/ambient state. Optimistically updates UI and widget first. */
    suspend fun apply(state: SonyProtocol.NcState): Boolean {
        val previous = _nc.value
        setNc(state)
        val ok = lock.withLock {
            ensureConnectedLocked() && conn.send(SonyProtocol.ncSet(state))
        }
        if (!ok && previous != null) setNc(previous)
        return ok
    }

    suspend fun sendRaw(payload: ByteArray): Boolean = lock.withLock {
        ensureConnectedLocked() && conn.send(payload)
    }

    // ---- incoming --------------------------------------------------------------------

    private fun setNc(s: SonyProtocol.NcState) {
        _nc.value = s
        Prefs.saveNc(app, s)
        ModeWidget.refreshAll(app)
    }

    private fun onPayload(p: ByteArray) {
        SonyProtocol.parseNc(p)?.let { setNc(it); return }
        SonyProtocol.parseBattery(p, _battery.value ?: SonyProtocol.Battery())?.let { _battery.value = it }
    }
}
