package ua.olex.sonyanc

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ua.olex.sonyanc.CommandScheduler.Priority
import java.util.Calendar
import kotlin.math.log10

// Formats from Sony Sound Connect 13.3.1; see docs/SAFE-LISTENING.md and docs/SONY-FEATURES.md.

private val T1 = SonyProtocol.TYPE_COMMAND
private val T2 = SonyProtocol.TYPE_COMMAND_2
private fun u(b: Byte) = b.toInt() and 255
private fun be(p: ByteArray, at: Int, n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or u(p[at + it]).toLong() }; return v }

/** What the earbuds say they can do: the support-function lists of both command tables. */
internal object SupportedFunctions {
    // Keys are table shl 8 or code, as in SONY_FUNCTION_NAMES.
    const val SL_HBS_1 = 0x250
    const val SL_TWS_1 = 0x251
    const val SL_HBS_2 = 0x252
    const val SL_TWS_2 = 0x253
    const val FIT_TEST = 0x1F6
    const val PAIRING = 0x230
    const val SOURCE_SWITCH = 0x231
    const val PAIRING_WITH_CLASS = 0x232

    private val _codes = MutableStateFlow<Set<Int>?>(null)
    val codes = _codes.asStateFlow()
    @Volatile private var readFor = -1L

    fun parse(p: ByteArray, table: Int): Set<Int>? {
        if (p.size < 3 || u(p[0]) != 0x07 || p[1].toInt() != 0) return null
        val n = u(p[2])
        if (p.size != 3 + 2 * n) return null
        return (0 until n).map { (table shl 8) or u(p[3 + 2 * it]) }.toSet()
    }

    fun encode(codes: Set<Int>) = codes.sorted().joinToString(",") { it.toString(16) }
    fun decode(raw: String?): Set<Int>? = raw?.split(',')?.mapNotNull { it.toIntOrNull(16) }?.toSet()?.takeIf { it.isNotEmpty() }

    fun load(ctx: Context) { if (_codes.value == null) _codes.value = decode(Prefs.p(ctx).getString("functions", null)) }
    fun forget() { _codes.value = null; readFor = -1L }

    /** Once per connection: firmware updates can add functions. */
    suspend fun refresh(ctx: Context) {
        val generation = Headphones.conn.generation
        if (readFor == generation) return
        val first = ask(T1, 1) ?: return
        val second = ask(T2, 2) ?: emptySet()
        readFor = generation
        val all = first + second
        Prefs.p(ctx).edit().putString("functions", encode(all)).apply()
        _codes.value = all
    }

    private suspend fun ask(type: Byte, table: Int): Set<Int>? = Headphones.exchange(
        1500, connect = false, priority = Priority.BACKGROUND,
        accepts = { t, p -> t == type.toInt() && parse(p, table) != null },
    ) { Headphones.conn.send(byteArrayOf(0x06, 0x00), type) }?.let { parse(it, table) }

    fun names(codes: Set<Int>): List<String> = codes.sorted().mapNotNull { SONY_FUNCTION_NAMES[it] }
}

/**
 * Sony Safe Listening: each earbud measures the sound reaching the ear and keeps an hourly log.
 * The app reads the log and the live level; the dose is the WHO-ITU H.870 weekly allowance.
 */
internal object SafeListening {
    const val NOT_SENDING = 3
    private const val SENDING = 1
    private const val COMPLETED = 2

    /** The inquired type to use, best first, or null when the earbuds have no Safe Listening. */
    fun type(codes: Set<Int>?): Int? = codes?.let {
        when {
            SupportedFunctions.SL_TWS_2 in it -> 3
            SupportedFunctions.SL_TWS_1 in it -> 1
            SupportedFunctions.SL_HBS_2 in it -> 2
            SupportedFunctions.SL_HBS_1 in it -> 0
            else -> null
        }
    }

    private fun tws(type: Int) = type == 1 || type == 3
    fun recordSize(type: Int) = if (type >= 2) 13 else 12

    /** One log entry: [side] 0 headphones, 1 left, 2 right; [timestamp] seconds on the earbud's clock. */
    data class Record(val side: Int, val timestamp: Long, val rtc: Int, val minutes: Int, val pressure: Long)

    fun record(p: ByteArray, at: Int, size: Int): Record? {
        if (at + size > p.size || u(p[at]) !in 0..2) return null
        return Record(u(p[at]), be(p, at + 1, 4), be(p, at + 5, 2).toInt(), u(p[at + 7]), be(p, at + 8, 4))
    }

    data class Batch(val statuses: List<Int>, val records: List<Record>)

    /** NTFY_STATUS 0x55: statuses (one, or left and right), a count and that many records. */
    fun logs(p: ByteArray, type: Int): Batch? {
        if (p.size < 4 || u(p[0]) != 0x55 || u(p[1]) != type) return null
        val sides = if (tws(type)) 2 else 1
        val countAt = 2 + sides
        if (p.size <= countAt) return null
        val n = u(p[countAt])
        val size = recordSize(type)
        if (p.size != countAt + 1 + n * size) return null
        val records = (0 until n).map { record(p, countAt + 1 + it * size, size) ?: return null }
        return Batch((0 until sides).map { u(p[2 + it]) }, records)
    }

    /** RET_STATUS 0x53: statuses and the current, still open round for each side. */
    fun status(p: ByteArray, type: Int): Batch? {
        if (p.size < 3 || u(p[0]) != 0x53 || u(p[1]) != type) return null
        val sides = if (tws(type)) 2 else 1
        val size = recordSize(type)
        if (p.size != 2 + sides + sides * size) return null
        val records = (0 until sides).map { record(p, 2 + sides + it * size, size) ?: return null }
        return Batch((0 until sides).map { u(p[2 + it]) }, records)
    }

    /** SET_STATUS 0x54: ask for every record after the last one kept for each side. */
    fun request(type: Int, last: Map<Int, Pair<Long, Int>>, status: Int = SENDING): ByteArray {
        val sides = if (tws(type)) listOf(1, 2) else listOf(0)
        val out = java.io.ByteArrayOutputStream()
        out.write(0x54); out.write(type)
        sides.forEach { out.write(status) }
        sides.forEach { side ->
            val (ts, rtc) = if (status == SENDING) last[side] ?: (0L to 0) else 0L to 0
            for (i in 3 downTo 0) out.write(((ts shr (8 * i)) and 0xFF).toInt())
            out.write((rtc shr 8) and 0xFF); out.write(rtc and 0xFF)
        }
        return out.toByteArray()
    }

    data class Capability(val roundMinutes: Int, val intervalSeconds: Int, val days: Int)
    fun capability(p: ByteArray, type: Int): Capability? =
        if (p.size == 9 && u(p[0]) == 0x51 && u(p[1]) == type) Capability(u(p[2]), u(p[7]), u(p[8])) else null

    /** RET_PARAM 0x57: 0 means the earbuds can log now. */
    fun available(p: ByteArray, type: Int): Boolean? =
        if (p.size == 3 && u(p[0]) == 0x57 && u(p[1]) == type) u(p[2]) == 0 else null

    fun start(type: Int) = byteArrayOf(0x58, type.toByte(), 0x00, 0x01)

    /** The live level: dB, or why there is none (0 not playing, 1 in a call, 2 not worn). */
    data class Live(val db: Int?, val reason: Int?)
    fun live(p: ByteArray, type: Int): Live? {
        if (p.size != 4 || u(p[0]) != 0x5B || u(p[1]) != type) return null
        val level = u(p[2])
        return if (level == 255) Live(null, u(p[3]).takeIf { it in 0..2 }) else Live(level, null)
    }

    // ---- math (Sony's SlConstant) --------------------------------------------------------------
    private const val PA2_SCALE = 100_000.0
    private const val REF_PA2 = 20e-6 * 20e-6
    enum class Reference(val label: String, val detail: String, val allowancePa2Min: Double) {
        STANDARD("Standard", "80 dB for 40 hours a week", 1.6 * 60),
        SENSITIVE("Sensitive", "75 dB for 40 hours a week", 0.51 * 60),
    }

    /** Pa^2 minutes from a raw sound pressure sum. */
    fun energy(pressure: Long) = pressure / PA2_SCALE / 60.0

    fun averageDb(pressure: Double, minutes: Double): Double? =
        if (minutes <= 0 || pressure <= 0) null else 10 * log10(energy(pressure.toLong()) / minutes / REF_PA2)

    /** Share of the weekly allowance, 1.0 = 100 %. Left and right count as one listener, as Sony does. */
    fun dose(pressure: Double, reference: Reference, days: Int = 7) = energy(pressure.toLong()) / (reference.allowancePa2Min * days / 7.0)

    // ---- stored log ------------------------------------------------------------------------------
    /** Kept on the phone: when (phone clock), side, minutes and raw pressure. */
    data class Entry(val at: Long, val side: Int, val minutes: Int, val pressure: Long)

    fun encode(entries: List<Entry>) = entries.joinToString(";") { "${it.at}:${it.side}:${it.minutes}:${it.pressure}" }
    fun decode(raw: String?): List<Entry> = raw.orEmpty().split(';').mapNotNull { e ->
        val f = e.split(':').mapNotNull(String::toLongOrNull)
        if (f.size == 4) Entry(f[0], f[1].toInt(), f[2].toInt(), f[3]) else null
    }

    /**
     * The earbud clock counts seconds from its own start; [anchor] is its current round and when
     * the phone read it. A record from before a clock restart cannot be placed and is left out.
     */
    fun place(records: List<Record>, anchor: Map<Int, Record>, now: Long): List<Entry> = records.mapNotNull { r ->
        val a = anchor[r.side] ?: anchor.values.firstOrNull() ?: return@mapNotNull null
        if (a.rtc != r.rtc) return@mapNotNull null
        Entry(now - (a.timestamp - r.timestamp) * 1000, r.side, r.minutes, r.pressure)
    }

    data class Day(val start: Long, val pressure: Double, val minutes: Double)
    data class Summary(val pressure: Double, val minutes: Double, val days: List<Day>) {
        fun dose(reference: Reference) = SafeListening.dose(pressure, reference)
        val averageDb get() = SafeListening.averageDb(pressure, minutes)
    }

    /** Last seven days (today included), sides averaged; [current] is the round still open. */
    fun summary(entries: List<Entry>, current: List<Entry>, now: Long, sides: Int): Summary {
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val starts = (6 downTo 0).map { back -> (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -back) }.timeInMillis }
        val all = entries + current
        val days = starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: Long.MAX_VALUE
            val inDay = all.filter { it.at in start until end }
            Day(start, inDay.sumOf { it.pressure.toDouble() } / sides, inDay.sumOf { it.minutes.toDouble() } / sides)
        }
        return Summary(days.sumOf { it.pressure }, days.sumOf { it.minutes }, days)
    }

    // ---- state -------------------------------------------------------------------------------
    data class State(
        val type: Int? = null, val available: Boolean? = null, val syncing: Boolean = false,
        val summary: Summary? = null, val live: Live? = null, val intervalSeconds: Int = 5, val syncedAt: Long = 0,
    )
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val lock = Mutex()
    @Volatile private var syncedFor = -1L

    fun enabled(ctx: Context) = Prefs.p(ctx).getBoolean("sl_on", true)
    fun reference(ctx: Context) = Reference.entries.getOrElse(Prefs.p(ctx).getInt("sl_reference", 0)) { Reference.STANDARD }
    fun setReference(ctx: Context, r: Reference) { Prefs.p(ctx).edit().putInt("sl_reference", r.ordinal).apply() }
    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.p(ctx).edit().putBoolean("sl_on", on).apply()
        if (!on) _state.update { it.copy(live = null) }
    }

    private fun sides(type: Int) = if (tws(type)) 2 else 1

    /** Shows what is stored without touching the earbuds. */
    fun load(ctx: Context) {
        val type = type(SupportedFunctions.codes.value) ?: return _state.update { State() }
        val p = Prefs.p(ctx)
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(type = type, syncedAt = p.getLong("sl_synced", 0),
                summary = summary(decode(p.getString("sl_log", null)), decode(p.getString("sl_now", null)), now, sides(type)))
        }
    }

    /** Once per connection: start logging, then fetch every record the phone does not have yet. */
    suspend fun sync(ctx: Context, force: Boolean = false) = lock.withLock {
        val type = type(SupportedFunctions.codes.value) ?: return@withLock
        if (!enabled(ctx) || !Headphones.conn.connected) return@withLock
        val generation = Headphones.conn.generation
        if (!force && syncedFor == generation) return@withLock
        _state.update { it.copy(type = type, syncing = true) }
        try {
            ask(byteArrayOf(0x50, type.toByte()), 1500) { capability(it, type) != null }
                ?.let { capability(it, type) }?.let { c -> _state.update { it.copy(intervalSeconds = c.intervalSeconds.coerceIn(1, 60)) } }
            val ready = ask(byteArrayOf(0x56, type.toByte()), 1500) { available(it, type) != null }?.let { available(it, type) }
            _state.update { it.copy(available = ready) }
            if (ready == false) return@withLock
            Headphones.post(start(type), T2, Priority.BACKGROUND)
            val records = fetch(ctx, type)
            val current = ask(byteArrayOf(0x52, type.toByte()), 1500) { status(it, type) != null }?.let { status(it, type) }
                ?: return@withLock
            val now = System.currentTimeMillis()
            val anchor = current.records.associateBy { it.side }
            val p = Prefs.p(ctx)
            val keepFrom = now - 8 * 86_400_000L
            val old = decode(p.getString("sl_log", null))
            val added = place(records, anchor, now)
            val log = (old + added).filter { it.at >= keepFrom }.distinctBy { Triple(it.at / 1000, it.side, it.pressure) }.sortedBy { it.at }
            val last = lastKept(p.getString("sl_last", null)).toMutableMap()
            records.groupBy { it.side }.forEach { (side, list) -> list.maxByOrNull { it.timestamp }?.let { last[side] = it.timestamp to it.rtc } }
            val open = current.records.map { Entry(now, it.side, it.minutes, it.pressure) }
            p.edit().putString("sl_log", encode(log)).putString("sl_now", encode(open))
                .putString("sl_last", last.entries.joinToString(";") { "${it.key}:${it.value.first}:${it.value.second}" })
                .putLong("sl_synced", now).apply()
            syncedFor = generation
            _state.update { it.copy(summary = summary(log, open, now, sides(type)), syncedAt = now) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Headphones.addLog("✗ safe listening: ${e.message}")
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    private fun lastKept(raw: String?): Map<Int, Pair<Long, Int>> = raw.orEmpty().split(';').mapNotNull { e ->
        val f = e.split(':').mapNotNull(String::toLongOrNull)
        if (f.size == 3) f[0].toInt() to (f[1] to f[2].toInt()) else null
    }.toMap()

    /**
     * Records arrive as notifications after the request. Sony treats a quiet link plus a status
     * read saying COMPLETED (or nothing to send) as the end; so does this, within 30 seconds.
     */
    private suspend fun fetch(ctx: Context, type: Int): List<Record> = coroutineScope {
        val got = mutableListOf<Record>()
        var heard = DeviceReads.now()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            Headphones.frames.collect { (t, p) ->
                if (t != T2.toInt()) return@collect
                logs(p, type)?.let { b -> synchronized(got) { got += b.records }; heard = DeviceReads.now() }
            }
        }
        try {
            if (!Headphones.post(request(type, lastKept(Prefs.p(ctx).getString("sl_last", null))), T2, Priority.BACKGROUND)) return@coroutineScope emptyList()
            withTimeoutOrNull(30_000) {
                while (true) {
                    delay(500)
                    if (DeviceReads.now() - heard < 2_500) continue
                    val s = ask(byteArrayOf(0x52, type.toByte()), 1500) { status(it, type) != null }?.let { status(it, type) } ?: break
                    if (s.statuses.all { it == COMPLETED || it == NOT_SENDING || it == 0 || it == 4 }) break
                    heard = DeviceReads.now()
                }
            }
        } finally {
            listener.cancel()
        }
        synchronized(got) { got.toList() }
    }

    /** The live level, while a screen shows it. */
    suspend fun readLive(): Live? {
        val type = _state.value.type ?: return null
        val live = ask(byteArrayOf(0x5A, type.toByte()), 1200) { live(it, type) != null }?.let { live(it, type) }
        _state.update { it.copy(live = live) }
        return live
    }

    private suspend fun ask(payload: ByteArray, timeout: Long, accepts: (ByteArray) -> Boolean): ByteArray? =
        Headphones.exchange(timeout, connect = false, priority = Priority.BACKGROUND,
            accepts = { t, p -> t == T2.toInt() && accepts(p) }) { Headphones.conn.send(payload, T2) }

    fun forget() { syncedFor = -1L; _state.value = State() }
}

/**
 * Sony's earpiece fitting test: the earbuds play a sound, measure the seal of each tip and say
 * good or poor. Table 1, system commands, inquired type 0x06.
 */
internal object FitTest {
    enum class Phase { IDLE, PREPARING, MEASURING, DONE, FAILED }
    data class State(val phase: Phase = Phase.IDLE, val left: Boolean? = null, val right: Boolean? = null, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val lock = Mutex()

    fun mode(on: Boolean) = byteArrayOf(0xF4.toByte(), 0x06, if (on) 1 else 0, 0x01)
    fun start(series: Int) = byteArrayOf(0xF8.toByte(), 0x06, 0x00, 0x00, series.toByte(), 0xFF.toByte())
    fun cancel(series: Int) = byteArrayOf(0xF8.toByte(), 0x06, 0x01, 0x00, series.toByte(), 0xFF.toByte())

    /** NTFY/RET param 0xF9/0xF7: operation status and error code. */
    data class Progress(val status: Int, val error: Int)
    fun progress(p: ByteArray): Progress? =
        if (p.size == 8 && (u(p[0]) == 0xF9 || u(p[0]) == 0xF7) && p[1].toInt() == 0x06) Progress(u(p[2]), u(p[3])) else null

    /** Extended param 0xFB/0xFD: fit per side, 0 good, 1 poor. */
    fun result(p: ByteArray): Pair<Boolean, Boolean>? =
        if (p.size == 8 && (u(p[0]) == 0xFB || u(p[0]) == 0xFD) && p[1].toInt() == 0x06 && u(p[2]) in 0..1 && u(p[3]) in 0..1)
            (u(p[2]) == 0) to (u(p[3]) == 0) else null

    /** The tip series the earbuds were told about (earpiece selection), or "not determined". */
    fun series(p: ByteArray): Int? = if (p.size == 3 && u(p[0]) == 0xF7 && p[1].toInt() == 0x07) u(p[2]) else null

    fun errorText(code: Int) = when (code) {
        1 -> "The left earbud isn't connected"
        2 -> "The right earbud isn't connected"
        3 -> "The test isn't available right now. Stop music and calls, then try again"
        4 -> "The left tip doesn't seal"
        5 -> "The right tip doesn't seal"
        6 -> "Neither tip seals"
        7 -> "Couldn't measure. Stay still and quiet, then try again"
        else -> "The test didn't finish"
    }

    fun reset() { if (_state.value.phase != Phase.MEASURING && _state.value.phase != Phase.PREPARING) _state.value = State() }

    suspend fun run() = lock.withLock {
        if (!Headphones.conn.connected) { _state.value = State(Phase.FAILED, error = "Connect the earbuds first"); return@withLock }
        _state.value = State(Phase.PREPARING)
        val series = Headphones.exchange(1200, connect = false,
            accepts = { t, p -> t == T1.toInt() && series(p) != null }) { Headphones.conn.send(byteArrayOf(0xF6.toByte(), 0x07), T1) }
            ?.let(::series) ?: 0xFF
        var entered = false
        try {
            coroutineScope {
                var progress: Progress? = null
                var fit: Pair<Boolean, Boolean>? = null
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    Headphones.frames.collect { (t, p) ->
                        if (t != T1.toInt()) return@collect
                        progress(p)?.let { progress = it }
                        result(p)?.let { fit = it }
                    }
                }
                try {
                    entered = Headphones.post(mode(true), T1)
                    if (!entered) { _state.value = State(Phase.FAILED, error = "The earbuds didn't start the test"); return@coroutineScope }
                    delay(600)
                    if (!Headphones.post(start(series), T1)) { _state.value = State(Phase.FAILED, error = "The earbuds didn't start the test"); return@coroutineScope }
                    _state.value = State(Phase.MEASURING)
                    val finished = withTimeoutOrNull(25_000) {
                        while (progress?.status.let { it == null || it == 0 || it == 1 }) delay(200)
                        progress
                    }
                    when {
                        finished == null -> _state.value = State(Phase.FAILED, error = "The earbuds didn't answer in time")
                        finished.status == 3 && finished.error in 1..3 || finished.error == 7 -> _state.value = State(Phase.FAILED, error = errorText(finished.error))
                        else -> {
                            if (fit == null) fit = Headphones.exchange(1500, connect = false,
                                accepts = { t, p -> t == T1.toInt() && result(p) != null }) { Headphones.conn.send(byteArrayOf(0xFA.toByte(), 0x06), T1) }?.let(::result)
                            val f = fit
                            _state.value = if (f != null) State(Phase.DONE, f.first, f.second)
                            else State(Phase.FAILED, error = errorText(finished.error))
                        }
                    }
                } finally {
                    listener.cancel()
                }
            }
        } finally {
            if (entered && Headphones.conn.connected) Headphones.post(mode(false), T1)
        }
    }

    suspend fun stop() {
        if (_state.value.phase == Phase.MEASURING) Headphones.post(cancel(0xFF), T1)
    }
}

/** Multipoint: the devices the earbuds know, which are connected, and which one plays. */
internal object Multipoint {
    data class Device(val address: String, val name: String, val slot: Int) {
        val connected get() = slot != 0
    }
    data class Devices(val devices: List<Device>, val playing: Int)
    data class State(val list: Devices? = null, val busy: String? = null, val error: String? = null, val reading: Boolean = false)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun type(codes: Set<Int>?): Int? = codes?.let {
        when { SupportedFunctions.PAIRING_WITH_CLASS in it -> 2; SupportedFunctions.PAIRING in it -> 0; else -> null }
    }
    fun canSwitch(codes: Set<Int>?) = codes?.contains(SupportedFunctions.SOURCE_SWITCH) == true

    /** RET/NTFY param 0x37/0x39: count, then address (17 ASCII), slot, [class of device], name. */
    fun parse(p: ByteArray, type: Int): Devices? {
        if (p.size < 4 || (u(p[0]) != 0x37 && u(p[0]) != 0x39) || u(p[1]) != type) return null
        val n = u(p[2])
        var at = 3
        val devices = mutableListOf<Device>()
        repeat(n) {
            if (at + 19 > p.size) return null
            val address = String(p, at, 17, Charsets.US_ASCII)
            val slot = u(p[at + 17])
            val nameAt = at + 18 + if (type == 2) 3 else 0
            if (nameAt >= p.size) return null
            val len = u(p[nameAt])
            if (len > 128 || nameAt + 1 + len > p.size) return null
            val name = String(p, nameAt + 1, len, Charsets.UTF_8)
            devices += Device(address, name.ifBlank { address }, slot)
            at = nameAt + 1 + len
        }
        if (at != p.size - 1) return null
        return Devices(devices, u(p[at]))
    }

    fun action(type: Int, action: Int, address: String): ByteArray =
        byteArrayOf(0x3C, type.toByte(), 0x00, action.toByte()) + address.toByteArray(Charsets.US_ASCII).copyOf(17)
    fun switchTo(address: String): ByteArray = byteArrayOf(0x3C, 0x01) + address.toByteArray(Charsets.US_ASCII).copyOf(17)

    /** NTFY extended param 0x3D: the result code of a connect/disconnect (low nibble 0 = success). */
    fun outcome(p: ByteArray): Int? = when {
        p.size == 21 && u(p[0]) == 0x3D && (u(p[1]) == 0 || u(p[1]) == 2) -> u(p[3])
        p.size == 20 && u(p[0]) == 0x3D && u(p[1]) == 1 -> u(p[2]).let { if (it == 0) 0 else 1 }
        else -> null
    }

    suspend fun read() {
        val type = type(SupportedFunctions.codes.value) ?: return
        _state.update { it.copy(reading = true) }
        val list = Headphones.exchange(1500, connect = false,
            accepts = { t, p -> t == T2.toInt() && parse(p, type) != null }) { Headphones.conn.send(byteArrayOf(0x36, type.toByte()), T2) }
            ?.let { parse(it, type) }
        _state.update { it.copy(list = list ?: it.list, reading = false) }
    }

    /** Keeps the list current while the page is open: the earbuds notify after every change. */
    suspend fun follow() {
        val type = type(SupportedFunctions.codes.value) ?: return
        Headphones.frames.collect { (t, p) -> if (t == T2.toInt()) parse(p, type)?.let { l -> _state.update { it.copy(list = l) } } }
    }

    /** [action] 0 disconnect, 1 connect; [switch] moves playback to the device. */
    suspend fun perform(device: Device, action: Int?, switch: Boolean = false) {
        val type = type(SupportedFunctions.codes.value) ?: return
        _state.update { it.copy(busy = device.address, error = null) }
        val reply = Headphones.exchange(12_000, connect = false,
            accepts = { t, p -> t == T2.toInt() && outcome(p).let { it != null && it and 0x0F != 2 } },
        ) { Headphones.conn.send(if (switch) switchTo(device.address) else action(type, action ?: 1, device.address), T2) }
        val code = reply?.let(::outcome)
        _state.update {
            it.copy(busy = null, error = when {
                code == null -> "${device.name} didn't answer"
                code and 0x0F == 0 -> null
                switch -> "Couldn't move the sound to ${device.name}"
                action == 0 -> "Couldn't disconnect ${device.name}"
                else -> "Couldn't connect ${device.name}. Turn on its Bluetooth and keep it close"
            })
        }
        read()
    }

    fun forget() { _state.value = State() }
}
