package ua.olex.sonyanc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import ua.olex.sonyanc.Headphones.CMD
import ua.olex.sonyanc.Headphones.addLog
import ua.olex.sonyanc.Headphones.app
import ua.olex.sonyanc.Headphones.conn
import ua.olex.sonyanc.Headphones.exchange
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

    suspend fun writeAdvancedField(key: AdvancedSettings.Key, index: Int, code: Int): Boolean {
        // The link may have dropped since the page was shown (it then forgets the live values and
        // the page shows the remembered ones): reconnect and read the setting before changing it.
        if (key !in _advanced.value) readAdvanced(key)
        return writeAdvancedLocked(key, index, code)
    }

    private suspend fun writeAdvancedLocked(key: AdvancedSettings.Key, index: Int, code: Int): Boolean = advancedLocks.getOrPut(key) { Mutex() }.withLock {
        val generation = conn.generation
        val current = _advanced.value[key] ?: run { addLog("✗ ${key.title}: no live value to change"); return@withLock false }
        val subtype = advancedInquiries[key] ?: key.subtype
        val get = byteArrayOf(key.getCode.toByte(), subtype.toByte())
        fun state(reply: ByteArray?) = reply?.let { AdvancedSettings.parse(key.type.toInt(), it)?.second }
        // Noise control on a touch sensor is 0x35 on newer firmware and 0 on older: try the likely one first.
        val wires = if (key == AdvancedSettings.Key.TOUCH) AdvancedSettings.touchCodes(current, code) else listOf(code)
        for (wire in wires) {
            val target = AdvancedSettings.patch(key, current, index, code, wire) ?: return@withLock false
            addLog("→ ${key.title}: $current → $target")
            var now = state(exchange(1200, connect = false,
                readyToRead = { conn.generation == generation },
                accepts = { type, p -> isAdvancedReply(key, type, p) },
                prepare = { conn.send(key.set(target, subtype), key.type) },
            ) { conn.send(get, key.type) })
            // Some settings (touch controls) are applied a moment after the earbuds answer: look again.
            for (look in 1..3) {
                if (AdvancedSettings.equivalent(key, now, target)) break
                delay(250)
                if (conn.generation != generation) return@withLock false
                now = state(exchange(1000, connect = false,
                    readyToRead = { conn.generation == generation },
                    accepts = { type, p -> isAdvancedReply(key, type, p) },
                ) { conn.send(get, key.type) }) ?: now
            }
            addLog("← ${key.title}: $now")
            if (AdvancedSettings.equivalent(key, now, target)) {
                if (conn.generation != generation) return@withLock false
                // The owner's choice of quality is kept by Earbuds too (see keepQuality).
                if (key == AdvancedSettings.Key.QUALITY && index == 0 && !Prefs.qualityByPlace(app)) Prefs.setPreferQuality(app, code == 0)
                return@withLock true
            }
            if (now == null) break // no answer at all: another code will not help
        }
        // The touch panel has two encodings (Sony's V1 and V2 differ in the first byte and flip the
        // second): when the reported one is refused, try the other once. If the earbuds refuse both,
        // they do not let the panel be turned off, and the switch is taken away (panelRefused).
        if (key == AdvancedSettings.Key.PANEL) {
            val on = AdvancedSettings.panelOn(listOf(current[0], code))
            val other = 1 - current[0]
            val target = listOf(other, AdvancedSettings.panelCode(listOf(other, 0), on))
            addLog("→ ${key.title}: $current → $target (other encoding)")
            val now = state(exchange(1000, connect = false,
                readyToRead = { conn.generation == generation },
                accepts = { type, p -> isAdvancedReply(key, type, p) },
                prepare = {
                    val sent = conn.send(key.set(target, subtype), key.type)
                    if (sent) delay(250)
                    sent
                },
            ) { conn.send(get, key.type) })
            addLog("← ${key.title}: $now")
            if (conn.generation != generation) return@withLock false
            if (now != null && AdvancedSettings.panelOn(now) == on) return@withLock true
            if (now != null) conn.ifConnected(generation) {
                panelRefused.value = true
                Prefs.setPanelRefused(app, true)
            }
        }
        false
    }

    /** The earbuds refused to turn the touch panel off in either encoding: the switch is hidden. */
    val panelRefused by lazy { MutableStateFlow(Prefs.panelRefused(app)) }

    /**
     * Once per connection, the Bluetooth quality the owner asked for:
     *  - "Best sound at home": sound quality (LDAC) at home, a stable connection elsewhere, where
     *    the street and pockets break up LDAC; nothing changes when the place can't be told;
     *  - otherwise "Prefer sound quality": set back when the earbuds report Stable connection again
     *    (a reset, or Sony's app). LDAC follows on the next link.
     */
    suspend fun keepQuality() {
        val wanted = when {
            Prefs.qualityByPlace(app) -> when (Places.atHome(app)) { true -> 0; false -> 1; null -> return }
            Prefs.preferQuality(app) -> 0
            else -> return
        }
        val now = readAdvanced(AdvancedSettings.Key.QUALITY)?.firstOrNull() ?: return
        if (now == wanted) return
        addLog("Quality: ${if (wanted == 0) "preferring sound quality" else "preferring a stable connection"}${if (Prefs.qualityByPlace(app)) " (by place)" else ""}")
        if (!writeAdvancedField(AdvancedSettings.Key.QUALITY, 0, wanted)) addLog("✗ Quality: not confirmed")
    }

    private fun isFeatureReply(feature: DeviceSettingsProtocol.Feature, type: Int, p: ByteArray) =
        type == CMD && DeviceSettingsProtocol.parse(p)?.first == feature && (p[0].toInt() and 255) in listOf(0xE7, 0xF7)

    suspend fun readFeature(feature: DeviceSettingsProtocol.Feature): Boolean? = featureLocks.getOrPut(feature) { Mutex() }.withLock {
        exchange(1400, accepts = { type, p -> isFeatureReply(feature, type, p) }) { conn.send(feature.get) }
            ?.let { DeviceSettingsProtocol.parse(it)?.second }
    }

    suspend fun writeFeature(feature: DeviceSettingsProtocol.Feature, value: Boolean): Boolean {
        if (feature !in _features.value) readFeature(feature) // reconnects after a dropped link
        return writeFeatureLocked(feature, value)
    }

    private suspend fun writeFeatureLocked(feature: DeviceSettingsProtocol.Feature, value: Boolean): Boolean = featureLocks.getOrPut(feature) { Mutex() }.withLock {
        val generation = conn.generation
        val reply = exchange(1400, connect = false,
            readyToRead = { conn.generation == generation },
            accepts = { type, p -> isFeatureReply(feature, type, p) },
            prepare = { feature in _features.value && conn.send(feature.set(value)) },
        ) { conn.send(feature.get) }
        reply != null && DeviceSettingsProtocol.parse(reply)?.second == value
    }

    private fun isEqualizerReply(type: Int, p: ByteArray) =
        type == CMD && (p[0].toInt() and 255) == 0x57 && EqualizerProtocol.parse(p) != null

    suspend fun readEqualizer(): EqualizerProtocol.State? = equalizerLock.withLock {
        exchange(1400, accepts = ::isEqualizerReply) { conn.send(EqualizerProtocol.get) }?.let(EqualizerProtocol::parse)
    }

    suspend fun writeEqualizer(preset: Int? = null, bands: List<Int>? = null): Boolean {
        if (_equalizer.value == null) readEqualizer() // reconnects after a dropped link
        return writeEqualizerLocked(preset, bands)
    }

    private suspend fun writeEqualizerLocked(preset: Int?, bands: List<Int>?): Boolean = equalizerLock.withLock {
        val payload = if (bands != null) EqualizerProtocol.custom(bands) else EqualizerProtocol.preset(requireNotNull(preset))
        val generation = conn.generation
        val actual = exchange(1400, connect = false,
            readyToRead = { conn.generation == generation },
            accepts = ::isEqualizerReply,
            prepare = { _equalizer.value != null && conn.send(payload) },
        ) { conn.send(EqualizerProtocol.get) }?.let(EqualizerProtocol::parse) ?: return@withLock false
        if (bands != null) actual.preset == 0xA0 && actual.bands == bands else actual.preset == preset
    }
}
