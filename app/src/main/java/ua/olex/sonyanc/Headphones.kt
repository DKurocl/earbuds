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
// The only stored context is ctx.applicationContext; no Activity is retained.
@SuppressLint("MissingPermission", "StaticFieldLeak")
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
    private val _batteryParts=MutableStateFlow<Set<Int>>(emptySet())
    val batteryParts=_batteryParts.asStateFlow()
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
    private val _advanced=MutableStateFlow<Map<AdvancedSettings.Key,List<Int>>>(emptyMap())
    val advanced=_advanced.asStateFlow()
    private val advancedInquiries=ConcurrentHashMap<AdvancedSettings.Key,Int>()
    private val advancedReplies=ConcurrentHashMap<AdvancedSettings.Key,CompletableDeferred<List<Int>?>>()
    private val advancedLocks=ConcurrentHashMap<AdvancedSettings.Key,Mutex>()
    private val _firmware=MutableStateFlow<String?>(null)
    val firmware=_firmware.asStateFlow()
    private val _equalizer = MutableStateFlow<EqualizerProtocol.State?>(null)
    val equalizer = _equalizer.asStateFlow()
    @Volatile private var eqReply: CompletableDeferred<EqualizerProtocol.State?>? = null
    @Volatile private var ncReply: Pair<SonyProtocol.NcState,CompletableDeferred<Boolean>>? = null
    private val featureLocks=ConcurrentHashMap<DeviceSettingsProtocol.Feature,Mutex>()
    private val equalizerLock=Mutex()
    private var infoJob: Job? = null
    private val usage=ConnectionUse()
    val idle get()=!usage.active && !conn.connected && infoJob?.isActive!=true && _pending.value==null

    private val conn = SonyConnection(scope, ::addLog, ::onPayload) {
        _status.value = Status.DISCONNECTED
        _fresh.value = false
        _codec.value=null
        codecReply?.complete(null)
        _batteryFresh.value = false
        _batteryParts.value=emptySet()
        _features.value=emptyMap()
        _advanced.value=emptyMap()
        advancedInquiries.clear()
        _equalizer.value=null
        advancedReplies.values.forEach{it.complete(null)}
        eqReply?.complete(null)
        ncReply?.second?.complete(false)
        _batteryNote.value = if(_battery.value!=null) "Last known levels" else "Connect to read battery"
        featureReplies.values.forEach {it.complete(null)}
        batteryReplies.values.forEach {it.complete(false)}
    }

    init {
        scope.launch {
            for (requested in requests) {
                try {
                    delay(45)
                    if(modeVersion.get()==requested.id) apply(requested.state,requested.id){requested.delivered.complete(it)}
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
                .filter { it.address==Prefs.deviceAddress(ctx) || it.name?.contains("WF-1000XM5", ignoreCase = true) == true }
                .toList()
        }.getOrDefault(emptyList())
    }
    private fun savedDevice(): BluetoothDevice? = bondedDevices(app)
        .firstOrNull { it.address == Prefs.deviceAddress(app) }

    @Synchronized fun hold(foreground:Boolean=true) {usage.acquire(foreground)}
    @Synchronized fun release(foreground:Boolean=true) {
        usage.release(foreground)
        if(!usage.foreground) {infoJob?.cancel();infoJob=null}
        if(!usage.active) {cancelModes();conn.close()}
    }

    private suspend fun ensureConnectedLocked(): Boolean {
        if(!usage.active) return false
        if (conn.connected) return true
        if (!hasPermission(app)) return fail("Allow Bluetooth access")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) return fail("Turn on Bluetooth")
        val dev = savedDevice() ?: return fail("Select your WF-1000XM5")
        _status.value = Status.CONNECTING
        _fresh.value = false
        _batteryFresh.value=false
        _batteryParts.value=emptySet()
        _codec.value=null
        _features.value=emptyMap()
        _advanced.value=emptyMap()
        advancedInquiries.clear()
        _firmware.value=null
        _equalizer.value = null
        try {
            if (!conn.connect(dev){usage.active} || !conn.send(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))) {
                conn.close()
                if(!usage.active) return false
                return fail("Could not connect. Check that your earbuds are connected to this phone.")
            }
        } catch (e: CancellationException) { conn.close(); throw e }
        catch (e: Exception) { addLog("✗ ${e.message}"); return fail("Could not connect to the earbuds") }
        if(!usage.active) {conn.close();return false}
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

    /** Drops the control link; the next action reconnects on demand. */
    @Synchronized private fun cancelModes() {
        modeVersion.incrementAndGet()
        activeRequest?.delivered?.complete(false)
        activeRequest=null;_pending.value=null
        while(true) {val old=requests.tryReceive().getOrNull() ?: break;old.delivered.complete(false)}
    }
    fun disconnect() { cancelModes();infoJob?.cancel();infoJob=null;scope.launch { lock.withLock { conn.close() } } }

    suspend fun connectAndSync(): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) return@withLock false
        // Mode controls never wait behind speculative battery probes.
        if (_pending.value == null && !conn.send(SonyProtocol.NC_GET)) return@withLock false
        startInfoLoop()
        conn.connected
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

    private suspend fun apply(state: SonyProtocol.NcState,id:Long,onDelivered:(Boolean)->Unit): Boolean = lock.withLock {
        if (!ensureConnectedLocked()) {onDelivered(false);return@withLock false}
        if(modeVersion.get()!=id) {onDelivered(false);return@withLock false}
        if(_fresh.value && _nc.value==state) {onDelivered(true);return@withLock true}
        var ok = conn.send(SonyProtocol.ncSet(state))
        // A cached socket can look connected after the earbuds have dropped it.
        // Reconnect once for the latest choice; never replay a superseded choice.
        if(!ok && modeVersion.get()==id && ensureConnectedLocked() && modeVersion.get()==id) ok=conn.send(SonyProtocol.ncSet(state))
        if (ok) {
            setNc(state)
            onDelivered(true) // Widget lifetime depends on delivery, not an optional read-back.
            _fresh.value = false
            if(modeVersion.get()!=id) return@withLock true
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
        cancelModes()
        conn.close()
        Prefs.saveDevice(app, address, name)
        _nc.value = null
        _pending.value = null
        _battery.value=null
        _batteryFresh.value=false
        _batteryParts.value=emptySet()
        _codec.value=null
        _features.value=emptyMap()
        _advanced.value=emptyMap()
        advancedInquiries.clear()
        _firmware.value=null
        ModeWidget.refreshAll(app)
    }
    @Synchronized private fun startInfoLoop() {
        if(!usage.foreground || infoJob?.isActive==true) return
        infoJob=scope.launch {
            delay(400)
            var batteryDue=0L
            while(usage.foreground && conn.connected) {
                while(_pending.value!=null && usage.foreground && conn.connected) delay(100)
                if(!usage.foreground || !conn.connected) break
                queryCodec()
                if(!usage.foreground || !conn.connected) break
                if(SystemClock.elapsedRealtime()>=batteryDue) {
                    queryBattery()
                    batteryDue=SystemClock.elapsedRealtime()+if(_batteryParts.value.containsAll(setOf(0,1,2))) 30000L else 8000L
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
            val sent=lock.withLock {conn.connected && conn.send(CodecProtocol.get)}
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
                if(family==1 && received && _batteryParts.value.containsAll(setOf(0,1))) continue
                if(withTimeoutOrNull(2200){while(_pending.value!=null) delay(60);true}!=true) continue
                val reply=CompletableDeferred<Boolean>()
                batteryReplies[family]=reply
                try {
                    val sent=lock.withLock {conn.connected && conn.send(q)}
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

    suspend fun readAdvanced(key:AdvancedSettings.Key):List<Int>? = advancedLocks.getOrPut(key){Mutex()}.withLock {
        if(!lock.withLock {ensureConnectedLocked()}) return@withLock null
        val inquiries=if(key==AdvancedSettings.Key.QUALITY) listOf(0,2) else listOf(key.subtype)
        for(subtype in inquiries) {
            val reply=CompletableDeferred<List<Int>?>()
            advancedReplies[key]=reply
            try {
                if(!lock.withLock {conn.connected && conn.send(byteArrayOf(key.getCode.toByte(),subtype.toByte()),key.type)}) break
                val value=withTimeoutOrNull(1000){reply.await()}
                if(value!=null) return@withLock value
            } finally {advancedReplies.remove(key,reply)}
        }
        null
    }
    suspend fun writeAdvancedField(key:AdvancedSettings.Key,index:Int,code:Int):Boolean = advancedLocks.getOrPut(key){Mutex()}.withLock {
        val reply=CompletableDeferred<List<Int>?>()
        try {
            val wanted=lock.withLock {
                if(!conn.connected) return@withLock null
                val current=_advanced.value[key] ?: return@withLock null
                val next=AdvancedSettings.patch(key,current,index,code) ?: return@withLock null
                val subtype=advancedInquiries[key] ?: key.subtype
                if(!conn.send(key.set(next,subtype),key.type)) return@withLock null
                advancedReplies[key]=reply
                if(!conn.send(byteArrayOf(key.getCode.toByte(),subtype.toByte()),key.type)) return@withLock null
                next
            } ?: return@withLock false
            AdvancedSettings.equivalent(key,withTimeoutOrNull(1200){reply.await()},wanted)
        } finally {advancedReplies.remove(key,reply)}
    }
    suspend fun readFirmware() = lock.withLock {if(ensureConnectedLocked()) conn.send(byteArrayOf(0x04,0x02)) else false}
    suspend fun powerOff():Boolean = lock.withLock {
        cancelModes()
        if(!ensureConnectedLocked()) return@withLock false
        val delivered=conn.send(byteArrayOf(0x24,0x03,0x01))
        conn.close();delivered
    }

    suspend fun readFeature(feature:DeviceSettingsProtocol.Feature):Boolean? = featureLocks.getOrPut(feature){Mutex()}.withLock {
        val reply=CompletableDeferred<Boolean?>()
        try {
            val sent=lock.withLock {
                if(!ensureConnectedLocked()) return@withLock false
                featureReplies[feature]=reply
                conn.send(feature.get)
            }
            if(sent) withTimeoutOrNull(1400){reply.await()} else null
        } finally {featureReplies.remove(feature,reply)}
    }
    suspend fun writeFeature(feature:DeviceSettingsProtocol.Feature,value:Boolean):Boolean = featureLocks.getOrPut(feature){Mutex()}.withLock {
        val reply=CompletableDeferred<Boolean?>()
        try {
            val sent=lock.withLock {
                if(!conn.connected || feature !in _features.value || !conn.send(feature.set(value))) return@withLock false
                featureReplies[feature]=reply
                conn.send(feature.get)
            }
            sent && withTimeoutOrNull(1400){reply.await()}==value
        } finally {featureReplies.remove(feature,reply)}
    }
    suspend fun readEqualizer():EqualizerProtocol.State? = equalizerLock.withLock {
        val reply=CompletableDeferred<EqualizerProtocol.State?>()
        try {
            val sent=lock.withLock {
                if(!ensureConnectedLocked()) return@withLock false
                eqReply=reply
                conn.send(EqualizerProtocol.get)
            }
            if(sent) withTimeoutOrNull(1400){reply.await()} else null
        } finally {if(eqReply===reply) eqReply=null}
    }
    suspend fun writeEqualizer(preset:Int?=null,bands:List<Int>?=null):Boolean = equalizerLock.withLock {
        val reply=CompletableDeferred<EqualizerProtocol.State?>()
        try {
            val sent=lock.withLock {
                if(!conn.connected || _equalizer.value==null) return@withLock false
                val payload=if(bands!=null) EqualizerProtocol.custom(bands) else EqualizerProtocol.preset(requireNotNull(preset))
                if(!conn.send(payload)) return@withLock false
                eqReply=reply
                conn.send(EqualizerProtocol.get)
            }
            if(!sent) return@withLock false
            val actual=withTimeoutOrNull(1400){reply.await()} ?: return@withLock false
            if(bands!=null) actual.preset==0xA0 && actual.bands==bands else actual.preset==preset
        } finally {if(eqReply===reply) eqReply=null}
    }

    private fun setNc(state: SonyProtocol.NcState) {
        _nc.value = state
        Prefs.saveNc(app, state)
        ModeWidget.refreshAll(app)
    }
    private fun onPayload(type:Int,p: ByteArray) {
        if(type==SonyProtocol.TYPE_COMMAND.toInt()) AdvancedSettings.firmware(p)?.let {_firmware.value=it;return}
        AdvancedSettings.parse(type,p)?.let{(key,value)->
            advancedInquiries[key]=p[1].toInt() and 255
            _advanced.update{it+(key to value)}
            if((p[0].toInt() and 255)==key.getCode+1) advancedReplies[key]?.complete(value)
            return
        }
        if(type!=SonyProtocol.TYPE_COMMAND.toInt()) return
        CodecProtocol.parse(p)?.let {
            _codec.value=it
            codecReply?.complete(it)
            return
        }
        DeviceSettingsProtocol.parse(p)?.let{(feature,value)->
            _features.update{it+(feature to value)}
            if((p[0].toInt() and 255) in listOf(0xE7,0xF7)) featureReplies[feature]?.complete(value)
            return
        }
        EqualizerProtocol.parse(p)?.let { _equalizer.value=it;if((p[0].toInt() and 255)==0x57) eqReply?.complete(it);return }
        SonyProtocol.parseNc(p)?.let {
            setNc(it); _fresh.value = true
            ncReply?.takeIf{reply->reply.first==it}?.second?.complete(true)
            return
        }
        SonyProtocol.parseBattery(p, _battery.value ?: SonyProtocol.Battery())?.let {
            val family=if(p[1].toInt()==0x0A) 2 else 1
            val hasValue=if(family==2) (p[2].toInt() and 255) in 1..100 else (p[2].toInt() and 255) in 1..100 || (p[4].toInt() and 255) in 1..100
            if(hasValue) {_battery.value=it;_batteryFresh.value=true;_batteryParts.update{parts->parts+SonyProtocol.liveBatteryParts(p)}}
            batteryReplies[family]?.complete(hasValue)
        }
    }
}
