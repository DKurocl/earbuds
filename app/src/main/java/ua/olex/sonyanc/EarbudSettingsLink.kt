package ua.olex.sonyanc

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import ua.olex.sonyanc.Headphones.CMD
import ua.olex.sonyanc.Headphones.Waiter
import ua.olex.sonyanc.Headphones.addLog
import ua.olex.sonyanc.Headphones.app
import ua.olex.sonyanc.Headphones.conn
import ua.olex.sonyanc.Headphones.ensureConnectedLocked
import ua.olex.sonyanc.Headphones.exchange
import ua.olex.sonyanc.Headphones.lock
import ua.olex.sonyanc.Headphones.scope
import ua.olex.sonyanc.Headphones.usage
import ua.olex.sonyanc.Headphones.waiters
import ua.olex.sonyanc.Headphones._state

/** The earbuds' own settings (Speak-to-Chat, quality, equalizer, …): read, write, confirm. */
internal object EarbudSettingsLink {
    internal val _features = MutableStateFlow<Map<DeviceSettingsProtocol.Feature, Boolean>>(emptyMap())
    val features = _features.asStateFlow()
    internal val _advanced = MutableStateFlow<Map<AdvancedSettings.Key, List<Int>>>(emptyMap())
    val advanced = _advanced.asStateFlow()
    internal val _equalizer = MutableStateFlow<EqualizerProtocol.State?>(null)
    val equalizer = _equalizer.asStateFlow()

    /** Subtype that each advanced setting last answered with (quality has two variants). */
    internal val advancedInquiries = ConcurrentHashMap<AdvancedSettings.Key, Int>()
    private val advancedLocks = ConcurrentHashMap<AdvancedSettings.Key, Mutex>()
    private val featureLocks = ConcurrentHashMap<DeviceSettingsProtocol.Feature, Mutex>()
    private val equalizerLock = Mutex()

    internal fun clearDeviceSettings() {
        _features.value = emptyMap()
        _advanced.value = emptyMap()
        advancedInquiries.clear()
        _equalizer.value = null
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
        val ok = AdvancedSettings.equivalent(key, reply?.let { AdvancedSettings.parse(key.type.toInt(), it)?.second }, target)
        // The owner's choice of quality is kept by Earbuds too (see keepQuality).
        if (ok && key == AdvancedSettings.Key.QUALITY && index == 0) Prefs.setPreferQuality(app, code == 0)
        ok
    }

    /**
     * Once per connection: when the owner chose "Prefer sound quality" and the earbuds report
     * Stable connection again (a reset, or Sony's app), set it back. LDAC follows on the next link.
     */
    suspend fun keepQuality() {
        if (!Prefs.preferQuality(app)) return
        val now = readAdvanced(AdvancedSettings.Key.QUALITY)?.firstOrNull() ?: return
        if (now != 1) return
        addLog("Quality: earbuds were on Stable connection; preferring sound quality again")
        if (!writeAdvancedField(AdvancedSettings.Key.QUALITY, 0, 0)) addLog("✗ Quality: not confirmed")
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
}
