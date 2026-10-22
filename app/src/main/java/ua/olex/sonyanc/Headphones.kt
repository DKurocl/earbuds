package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

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
    private data class ModeRequest(val id:Long,val state:SonyProtocol.NcState,val delivered:CompletableDeferred<Boolean>)
    private val modeVersion=AtomicLong()
    private val requests = Channel<ModeRequest>(Channel.CONFLATED,onUndeliveredElement={it.delivered.complete(false)})
    private var activeRequest:ModeRequest?=null
    private val _batteryRefreshing=MutableStateFlow(false)
    val batteryRefreshing=_batteryRefreshing.asStateFlow()
    private val _batteryFresh=MutableStateFlow(false)
    val batteryFresh=_batteryFresh.asStateFlow()
    private val _batteryNote=MutableStateFlow("Connect to read battery")
    val batteryNote=_batteryNote.asStateFlow()
    private val batteryLock=Mutex()
    private val batteryReplies=ConcurrentHashMap<Int,CompletableDeferred<Boolean>>()
    private val _features=MutableStateFlow<Map<DeviceSettingsProtocol.Feature,Boolean>>(emptyMap())
    val features=_features.asStateFlow()
    private val featureReplies=ConcurrentHashMap<DeviceSettingsProtocol.Feature,CompletableDeferred<Boolean?>>()
    private val _codec=MutableStateFlow<CodecProtocol.State?>(null)
    val codec=_codec.asStateFlow()
    private val _codecRefreshing=MutableStateFlow(false)
    val codecRefreshing=_codecRefreshing.asStateFlow()
    private val codecLock=Mutex()
    @Volatile private var codecReply:CompletableDeferred<CodecProtocol.State?>?=null
    private val conn = SonyConnection(scope, ::addLog, ::onPayload) {
        _status.value = Status.DISCONNECTED
        _fresh.value = false
        _codec.value=null
        codecReply?.complete(null)
        _batteryFresh.value = false
        _batteryNote.value = if(_battery.value!=null) "Last known levels" else "Connect to read battery"
        featureReplies.values.forEach {it.complete(null)}
        batteryReplies.values.forEach {it.complete(false)}
    }
    private val _equalizer = MutableStateFlow<EqualizerProtocol.State?>(null)
    val equalizer = _equalizer.asStateFlow()
    @Volatile private var eqReply: CompletableDeferred<EqualizerProtocol.State>? = null
    @Volatile private var ncReply: Pair<SonyProtocol.NcState,CompletableDeferred<Boolean>>? = null
    private var infoJob: Job? = null
    @Volatile private var holders = 0
    private var idleJob: Job? = null

    init {
        scope.launch {
            for (requested in requests) {
                try {
                    delay(45)
                    if(modeVersion.get()==requested.id) apply(requested.state){requested.delivered.complete(it)}
                }
                catch(e:CancellationException){throw e}
                catch(e:Exception){addLog("✗ mode: ${e.message}");_error.value="Could not change mode. Try again."}
                finally {
                    requested.delivered.complete(false)
                    synchronized(this@Headphones) {if(modeVersion.get()==requested.id) {_pending.value=null;activeRequest=null}}
                }
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
        if (!hasPermission(app)) return fail("Allow Bluetooth access")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) return fail("Turn on Bluetooth")
        val dev = savedDevice() ?: return fail("Select your WF-1000XM5")
        _status.value = Status.CONNECTING
        _fresh.value = false
        _batteryFresh.value=false
        _codec.value=null
        _features.value=emptyMap()
        _equalizer.value = null
        try {
            if (!conn.connect(dev) || !conn.send(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))) {
                conn.close()
                return fail("Could not connect. Check that your earbuds are connected to this phone.")
            }
        } catch (e: CancellationException) { conn.close(); throw e }
        catch (e: Exception) { addLog("✗ ${e.message}"); return fail("Could not connect to the earbuds") }
        _status.value = Status.CONNECTED
        _error.value = null
        startInfoLoop()
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
        startInfoLoop()
        true
    }

    @Synchronized fun request(state: SonyProtocol.NcState): Deferred<Boolean> {
        activeRequest?.takeIf{_pending.value==state && it.state==state}?.let{return it.delivered}
        if(_pending.value==null && _fresh.value && _nc.value==state && conn.connected) return CompletableDeferred(true)
        _error.value=null
        val request=ModeRequest(modeVersion.incrementAndGet(),state,CompletableDeferred())
        _pending.value=state
        activeRequest=request
        requests.trySend(request)
        return request.delivered
    }

    private suspend fun apply(state: SonyProtocol.NcState,onDelivered:(Boolean)->Unit): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) {onDelivered(false);return@withLock false}
        if(_fresh.value && _nc.value==state) {onDelivered(true);return@withLock true}
        var ok = conn.send(SonyProtocol.ncSet(state))
        // A cached socket can look connected after the earbuds have dropped it.
        // Reconnect once for the latest choice; never replay a superseded choice.
        if(!ok && _pending.value==state && ensureConnectedLocked()) ok=conn.send(SonyProtocol.ncSet(state))
        if (ok) {
            setNc(state)
            onDelivered(true) // Widget lifetime depends on delivery, not an optional read-back.
            _fresh.value = false
            val reply=CompletableDeferred<Boolean>()
            ncReply=state to reply
            try {
                if(conn.send(SonyProtocol.NC_GET)) withTimeoutOrNull(900){reply.await()}
            } finally {if(ncReply?.second===reply) ncReply=null}
        } else {
            onDelivered(false)
            _error.value = "The earbuds did not confirm the command. Try again."
        }
        ok
    }

    suspend fun selectDevice(address: String, name: String?) = lock.withLock {
        conn.close()
        Prefs.saveDevice(app, address, name)
        _nc.value = null
        _pending.value = null
        _battery.value=null
        _batteryFresh.value=false
        _codec.value=null
        _features.value=emptyMap()
        ModeWidget.refreshAll(app)
    }
    private fun startInfoLoop() {
        if(infoJob?.isActive==true) return
        infoJob=scope.launch {
            delay(400)
            var batteryDue=0L
            while(holders>0 && conn.connected) {
                while(_pending.value!=null && holders>0 && conn.connected) delay(100)
                if(holders==0 || !conn.connected) break
                queryCodec()
                if(holders==0 || !conn.connected) break
                if(SystemClock.elapsedRealtime()>=batteryDue) {
                    queryBattery()
                    batteryDue=SystemClock.elapsedRealtime()+if(_batteryFresh.value) 30000L else 8000L
                }
                delay(8000)
            }
        }
    }

    suspend fun queryCodec():CodecProtocol.State? = codecLock.withLock {
        _codecRefreshing.value=true
        val reply=CompletableDeferred<CodecProtocol.State?>()
        try {
            if(withTimeoutOrNull(2200){while(_pending.value!=null) delay(60);true}!=true) return@withLock null
            codecReply=reply
            val sent=lock.withLock {ensureConnectedLocked() && conn.send(CodecProtocol.get)}
            if(!sent) return@withLock null
            withTimeoutOrNull(900){reply.await()}
        } catch(e:CancellationException){throw e}
        catch(e:Exception){addLog("✗ codec: ${e.message}");null}
        finally {
            if(codecReply===reply) codecReply=null
            _codecRefreshing.value=false
        }
    }

    suspend fun queryBattery() = batteryLock.withLock {
        _batteryRefreshing.value=true
        _batteryNote.value="Reading battery…"
        var received=false
        try {
            // 01 and 09 are both documented Sony V2 dual-battery inquiry formats.
            for(q in SonyProtocol.BATTERY_GETS) {
                val family=if(q[1].toInt()==0x0A) 2 else 1
                if(family==1 && received) continue
                if(withTimeoutOrNull(2200){while(_pending.value!=null) delay(60);true}!=true) continue
                val reply=CompletableDeferred<Boolean>()
                batteryReplies[family]=reply
                try {
                    val sent=lock.withLock {ensureConnectedLocked() && conn.send(q)}
                    if(sent && withTimeoutOrNull(900){reply.await()}==true) received=true
                } finally {batteryReplies.remove(family,reply)}
                yield()
            }
            _batteryNote.value=when {
                _batteryFresh.value -> "Live levels"
                _battery.value!=null -> "Last known levels"
                else -> "Battery unavailable"
            }
            if(!received) addLog("Battery: no decoded reply; requested 22 01 / 22 09 / 22 0A")
        } catch(e:CancellationException){throw e}
        catch(e:Exception){addLog("✗ battery: ${e.message}");_batteryNote.value="Could not read battery"}
        finally {_batteryRefreshing.value=false}
    }

    suspend fun readFeature(feature:DeviceSettingsProtocol.Feature):Boolean? = lock.withLock {
        if(!ensureConnectedLocked()) return@withLock null
        val reply=CompletableDeferred<Boolean?>()
        featureReplies[feature]=reply
        try {
            if(!conn.send(feature.get)) return@withLock null
            withTimeoutOrNull(1400){reply.await()}
        } finally {featureReplies.remove(feature,reply)}
    }
    suspend fun writeFeature(feature:DeviceSettingsProtocol.Feature,value:Boolean):Boolean = lock.withLock {
        if(!ensureConnectedLocked() || feature !in _features.value) return@withLock false
        if(!conn.send(feature.set(value))) return@withLock false
        val reply=CompletableDeferred<Boolean?>()
        featureReplies[feature]=reply
        try {
            if(!conn.send(feature.get)) return@withLock false
            withTimeoutOrNull(1400){reply.await()}==value
        } finally {featureReplies.remove(feature,reply)}
    }
    suspend fun readEqualizer(): EqualizerProtocol.State? = lock.withLock {
        if(!ensureConnectedLocked()) return@withLock null
        val reply=CompletableDeferred<EqualizerProtocol.State>()
        eqReply=reply
        try {
            if(!conn.send(EqualizerProtocol.get)) return@withLock null
            withTimeoutOrNull(1400){reply.await()}
        } finally {if(eqReply===reply) eqReply=null}
    }
    suspend fun writeEqualizer(preset:Int?=null,bands:List<Int>?=null):Boolean = lock.withLock {
        if(!ensureConnectedLocked() || _equalizer.value==null) return@withLock false
        val payload=if(bands!=null) EqualizerProtocol.custom(bands) else EqualizerProtocol.preset(requireNotNull(preset))
        if(!conn.send(payload)) return@withLock false
        val reply=CompletableDeferred<EqualizerProtocol.State>()
        eqReply=reply
        try {
            if(!conn.send(EqualizerProtocol.get)) return@withLock false
            val actual=withTimeoutOrNull(1400){reply.await()} ?: return@withLock false
            if(bands!=null) actual.preset==0xA0 && actual.bands==bands else actual.preset==preset
        } finally {if(eqReply===reply) eqReply=null}
    }

    private fun setNc(state: SonyProtocol.NcState) {
        _nc.value = state
        Prefs.saveNc(app, state)
        ModeWidget.clearNote(app)
    }
    private fun onPayload(p: ByteArray) {
        CodecProtocol.parse(p)?.let {
            _codec.value=it
            codecReply?.complete(it)
            return
        }
        DeviceSettingsProtocol.parse(p)?.let{(feature,value)->
            _features.update{it+(feature to value)}
            featureReplies[feature]?.complete(value)
            return
        }
        EqualizerProtocol.parse(p)?.let { _equalizer.value=it;eqReply?.complete(it);return }
        SonyProtocol.parseNc(p)?.let {
            setNc(it); _fresh.value = true
            ncReply?.takeIf{reply->reply.first==it}?.second?.complete(true)
            return
        }
        SonyProtocol.parseBattery(p, _battery.value ?: SonyProtocol.Battery())?.let {
            val family=if(p[1].toInt()==0x0A) 2 else 1
            val hasValue=if(family==2) it.case!=null else it.left!=null || it.right!=null
            if(hasValue) {_battery.value=it;_batteryFresh.value=true}
            batteryReplies[family]?.complete(hasValue)
        }
    }
}
