package ua.olex.sonyanc

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object Prefs {
    internal fun p(ctx: Context) = ctx.getSharedPreferences("sonyanc", Context.MODE_PRIVATE)

    /** First time out each day: "day,weekend,minute;..." (ChargeReminder). */
    fun outTimes(ctx: Context): List<Triple<Int, Boolean, Int>> = p(ctx).getString("out_times", "").orEmpty().split(';').mapNotNull { e ->
        e.split(',').takeIf { it.size == 3 }?.let { (d, w, m) -> runCatching { Triple(d.toInt(), w == "1", m.toInt()) }.getOrNull() }
    }
    fun setOutTimes(ctx: Context, v: List<Triple<Int, Boolean, Int>>) =
        p(ctx).edit().putString("out_times", v.joinToString(";") { "${it.first},${if (it.second) 1 else 0},${it.third}" }).apply()
    fun chargeReminder(ctx: Context) = p(ctx).getBoolean("charge_reminder", false)
    fun setChargeReminder(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("charge_reminder", on).apply()
    fun activityMode(ctx: Context) = p(ctx).getBoolean("activity_mode", false)
    fun setActivityMode(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("activity_mode", on).apply()
    fun afterSleep(ctx: Context) = AfterSleep.entries.getOrNull(p(ctx).getInt("after_sleep", 0)) ?: AfterSleep.POWER_OFF
    fun setAfterSleep(ctx: Context, value: AfterSleep) = p(ctx).edit().putInt("after_sleep", value.ordinal).apply()
    fun qualityByPlace(ctx: Context) = p(ctx).getBoolean("quality_by_place", false)
    fun setQualityByPlace(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("quality_by_place", on).apply()
    fun panelRefused(ctx: Context) = p(ctx).getBoolean("panel_refused", false)
    fun setPanelRefused(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("panel_refused", value).apply()
    fun dynamicColor(ctx: Context) = p(ctx).getBoolean("dynamic_color", true)
    fun haptics(ctx: Context) = p(ctx).getBoolean("haptics", true)
    fun setDynamicColor(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("dynamic_color", value).apply()
    fun setHaptics(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("haptics", value).apply()

    fun deviceAddress(ctx: Context): String? = p(ctx).getString("device", null)
    fun displayName(ctx: Context): String? = p(ctx).getString("display_name", null)
    fun setDisplayName(ctx: Context, value: String) = p(ctx).edit().putString("display_name", value).apply()
    fun deviceName(ctx: Context): String? = p(ctx).getString("device_name", null)
    fun saveDevice(ctx: Context, address: String, name: String?) =
        p(ctx).edit().putString("device", address).putString("device_name", name).apply()

    fun lastNc(ctx: Context): SonyProtocol.NcState? {
        val prefs = p(ctx)
        val m = prefs.getString("mode", null) ?: return null
        val mode = runCatching { SonyProtocol.Mode.valueOf(m) }.getOrNull() ?: return null
        return SonyProtocol.NcState(mode, prefs.getBoolean("voice", false), prefs.getInt("level", 20))
    }

    /** The last codec the earbuds reported (CodecProtocol code), shown until they report again. */
    fun lastCodec(ctx: Context): Int? = p(ctx).getInt("codec", -1).takeIf { it >= 0 }
    fun saveCodec(ctx: Context, code: Int) { if (lastCodec(ctx) != code) p(ctx).edit().putInt("codec", code).apply() }

    /** The owner chose "Prefer sound quality": Earbuds keeps the earbuds on it. */
    fun preferQuality(ctx: Context) = p(ctx).getBoolean("prefer_quality", false)
    fun setPreferQuality(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("prefer_quality", on).apply()

    /** Which take of a haptic cue the owner chose (Haptics.Cue name), 0 by default. */
    fun hapticTake(ctx: Context, cue: String) = p(ctx).getInt("haptic_$cue", 0)
    fun setHapticTake(ctx: Context, cue: String, take: Int) = p(ctx).edit().putInt("haptic_$cue", take).apply()

    fun saveNc(ctx: Context, s: SonyProtocol.NcState) =
        p(ctx).edit().putString("mode", s.mode.name).putBoolean("voice", s.voice).putInt("level", s.level).apply()

    // ---- last battery reading, shown at once on launch until the earbuds answer ----
    private fun level(v: Int) = if (v in 1..100) v else null

    fun lastBattery(ctx: Context): Pair<SonyProtocol.Battery, Long>? {
        val prefs = p(ctx)
        val at = prefs.getLong("battery_at", 0L).takeIf { it > 0 } ?: return null
        val b = SonyProtocol.Battery(
            level(prefs.getInt("battery_left", 0)), level(prefs.getInt("battery_right", 0)), level(prefs.getInt("battery_case", 0)),
            prefs.getBoolean("charging_left", false), prefs.getBoolean("charging_right", false), prefs.getBoolean("charging_case", false),
        )
        return if (b.left == null && b.right == null && b.case == null) null else b to at
    }

    fun saveBattery(ctx: Context, b: SonyProtocol.Battery, at: Long) = p(ctx).edit()
        .putInt("battery_left", b.left ?: 0).putInt("battery_right", b.right ?: 0).putInt("battery_case", b.case ?: 0)
        .putBoolean("charging_left", b.leftCharging).putBoolean("charging_right", b.rightCharging).putBoolean("charging_case", b.caseCharging)
        .putLong("battery_at", at).apply()

    /** A different pair must not inherit this pair's measurements or settings. */
    @Synchronized fun replaceDevice(ctx: Context, address: String, name: String?) {
        val editor = p(ctx).edit().putString("device", address).putString("device_name", name)
        listOf(
            "mode", "voice", "level", "codec", "battery_at", "linked", "last_seen",
            "cache_advanced", "cache_features", "cache_eq", "last_eq_preset", "panel_refused",
            "battery_log", "battery_health", "buds_reminded_session",
        ).forEach { editor.remove(it) }
        editor.apply()
        batteryHistory = null
    }

    // ---- Android's Bluetooth link, from ACL broadcasts (see ConnectionReceiver) ----
    /** True/false once a broadcast or a working control link said so; null while unknown. */
    fun linked(ctx: Context): Boolean? = p(ctx).takeIf { it.contains("linked") }?.getBoolean("linked", false)
    internal fun linkedChanges(ctx: Context) = changes(ctx, setOf("linked")) { linked(ctx) }
    internal fun tileLinkChanges(ctx: Context) = changes(ctx, setOf("linked", "device")) {
        linked(ctx) to deviceAddress(ctx)
    }
    internal fun timerChanges(ctx: Context) = changes(ctx, setOf("timer_until")) { AmbientTimer.until(ctx) }

    private fun <T> changes(ctx: Context, keys: Set<String>, value: () -> T) = callbackFlow {
        val prefs = p(ctx)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed in keys) trySend(value())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(value())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setLinked(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("linked", value).apply()
    /** When the earbuds last disconnected from the phone (usually: went back in the case). */
    fun lastSeen(ctx: Context): Long = p(ctx).getLong("last_seen", 0L)
    fun setLastSeen(ctx: Context, at: Long) = p(ctx).edit().putLong("last_seen", at).apply()

    internal fun personalProfiles(ctx: Context) = changes(ctx, setOf("profile_1", "profile_2")) {
        (1..2).associateWith { slot -> p(ctx).getString("profile_$slot", null)?.let(PersonalProfile::decode) }
    }

    internal fun savePersonalProfile(ctx: Context, slot: Int, profile: PersonalProfile) =
        p(ctx).edit().putString("profile_$slot", profile.encode()).apply()

    internal fun weekendMode(ctx: Context) = p(ctx).getString("weekend_mode", null)
        ?.let { value -> OnConnect.entries.firstOrNull { it.name == value } }

    internal fun setWeekendMode(ctx: Context, mode: OnConnect?) =
        p(ctx).edit().putString("weekend_mode", mode?.name).apply()

    fun onConnect(ctx: Context): OnConnect = OnConnect.from(p(ctx).getString("on_connect", null))
    /** "At home" (Places): null means the same as everywhere. */
    fun homeMode(ctx: Context): OnConnect? = p(ctx).getString("home_mode", null)?.let { n -> OnConnect.entries.firstOrNull { it.name == n } }
    fun setHomeMode(ctx: Context, value: OnConnect?) = p(ctx).edit().putString("home_mode", value?.name).apply()
    fun home(ctx: Context): Pair<Double, Double>? {
        val lat = p(ctx).getString("home_lat", null)?.toDoubleOrNull() ?: return null
        val lon = p(ctx).getString("home_lon", null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }
    fun setHome(ctx: Context, lat: Double, lon: Double) = p(ctx).edit().putString("home_lat", lat.toString()).putString("home_lon", lon.toString()).apply()
    fun setOnConnect(ctx: Context, value: OnConnect) = p(ctx).edit().putString("on_connect", value.name).apply()

    fun caseReminder(ctx: Context) = p(ctx).getBoolean("case_reminder", false)
    /** The sheet that comes up when the earbuds connect (needs "Stay in sync"); on by default. */
    fun connectSheet(ctx: Context) = p(ctx).getBoolean("connect_sheet", true)
    fun setConnectSheet(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("connect_sheet", value).apply()
    /** Low earbud battery heads-up; on by default (it still needs the notification permission). */
    fun budsReminder(ctx: Context) = p(ctx).getBoolean("buds_reminder", true)
    fun setBudsReminder(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("buds_reminder", value).apply()
    fun budsRemindedSession(ctx: Context) = p(ctx).getInt("buds_reminded_session", -1)
    fun setBudsRemindedSession(ctx: Context, value: Int) = p(ctx).edit().putInt("buds_reminded_session", value).apply()
    fun setCaseReminder(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("case_reminder", value).apply()
    fun caseRemindedAt(ctx: Context) = p(ctx).getLong("case_reminded_at", 0L)
    fun setCaseRemindedAt(ctx: Context, at: Long) = p(ctx).edit().putLong("case_reminded_at", at).apply()

    /** Quick Settings tile: true cycles ANC ↔ Ambient only; false also visits Off. */
    fun tileTwoWay(ctx: Context) = p(ctx).getBoolean("tile_two_way", false)
    fun setTileTwoWay(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("tile_two_way", value).apply()

    // ---- last values read from the earbuds, preserved across control connections ----
    fun cachedAdvanced(ctx: Context): Map<AdvancedSettings.Key, List<Int>> =
        p(ctx).getString("cache_advanced", null).orEmpty().split(';').mapNotNull { row ->
            val (k, v) = row.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val key = AdvancedSettings.Key.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
            val values = v.split(',').map { it.toIntOrNull() ?: return@mapNotNull null }
            if (key.valid(values)) key to values else null
        }.toMap()
    internal fun cachedAdvancedChanges(ctx: Context) = changes(ctx, setOf("cache_advanced")) { cachedAdvanced(ctx) }

    @Synchronized fun cacheAdvanced(ctx: Context, key: AdvancedSettings.Key, value: List<Int>) {
        val all = cachedAdvanced(ctx) + (key to value)
        p(ctx).edit()
            .putString("cache_advanced", all.entries.joinToString(";") { (k, v) -> "${k.name}:${v.joinToString(",")}" })
            .apply()
    }
    fun cachedFeatures(ctx: Context): Map<DeviceSettingsProtocol.Feature, Boolean> =
        p(ctx).getString("cache_features", null).orEmpty().split(';').mapNotNull { row ->
            val (k, v) = row.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            DeviceSettingsProtocol.Feature.entries.firstOrNull { it.name == k }?.let { it to (v == "1") }
        }.toMap()
    internal fun cachedFeatureChanges(ctx: Context) = changes(ctx, setOf("cache_features")) { cachedFeatures(ctx) }

    @Synchronized fun cacheFeature(ctx: Context, feature: DeviceSettingsProtocol.Feature, value: Boolean) {
        val all = cachedFeatures(ctx) + (feature to value)
        p(ctx).edit()
            .putString("cache_features", all.entries.joinToString(";") { (k, v) -> "${k.name}:${if (v) 1 else 0}" })
            .apply()
    }
    fun cachedEqualizer(ctx: Context): EqualizerProtocol.State? = p(ctx).getString("cache_eq", null)?.let { raw ->
        val parts = raw.split('|')
        val preset = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in EqualizerProtocol.presets } ?: return null
        val bands = parts.getOrNull(1).orEmpty().split(',').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        if (bands.size !in listOf(0, 6) || bands.any { it !in -10..10 }) null else EqualizerProtocol.State(preset, bands)
    }
    fun lastEnabledEqualizerPreset(ctx: Context): Int =
        p(ctx).getInt("last_eq_preset", 0xA0).takeIf { it != 0 && it in EqualizerProtocol.presets } ?: 0xA0

    fun cacheEqualizer(ctx: Context, eq: EqualizerProtocol.State) {
        val editor = p(ctx).edit().putString("cache_eq", "${eq.preset}|${eq.bands.joinToString(",")}")
        if (eq.preset != 0) editor.putInt("last_eq_preset", eq.preset)
        editor.apply()
    }

    // ---- real battery use (BatteryLog) ----
    private class BatteryHistory(val encoded: String?, val samples: List<BatterySample>) {
        val noiseControl by lazy { BatteryLog.rate(samples, SonyProtocol.Mode.ANC) }
        val off by lazy { BatteryLog.rate(samples, SonyProtocol.Mode.OFF) }
    }

    private var batteryHistory: BatteryHistory? = null

    @Synchronized private fun batteryHistory(ctx: Context): BatteryHistory {
        val encoded = p(ctx).getString("battery_log", null)
        return batteryHistory?.takeIf { it.encoded == encoded }
            ?: BatteryHistory(encoded, BatteryLog.decode(encoded)).also { batteryHistory = it }
    }

    fun batteryLog(ctx: Context): List<BatterySample> = batteryHistory(ctx).samples

    @Synchronized fun addBatterySample(ctx: Context, s: BatterySample) {
        val log = batteryLog(ctx)
        val next = BatteryLog.add(log, s)
        if (next === log) return
        // This week's measured full charge, for the months-long health view.
        val week = BatteryHealth.weekOf(s.at)
        val thisWeek = next.filter { BatteryHealth.weekOf(it.at) == week }
        val health = BatteryHealth.record(batteryHealth(ctx), week, BatteryLog.drain(thisWeek, SonyProtocol.Mode.ANC))
        val encoded = BatteryLog.encode(next)
        p(ctx).edit().putString("battery_log", encoded).putString("battery_health", BatteryHealth.encode(health)).apply()
        batteryHistory = BatteryHistory(encoded, next)
    }
    fun batteryHealth(ctx: Context) = BatteryHealth.decode(p(ctx).getString("battery_health", null))
    /** Measured percent per minute for [mode], or null while there is too little real use. */
    fun drainRate(ctx: Context, mode: SonyProtocol.Mode?): Double? = when (mode) {
        null -> null
        SonyProtocol.Mode.OFF -> batteryHistory(ctx).off
        SonyProtocol.Mode.ANC, SonyProtocol.Mode.AMBIENT -> batteryHistory(ctx).noiseControl
    }
    fun session(ctx: Context) = p(ctx).getInt("session", 0)
    fun nextSession(ctx: Context) = p(ctx).edit().putInt("session", session(ctx) + 1).apply()

    // ---- "Ambient for a moment" ----
    fun ambientMinutes(ctx: Context) = p(ctx).getInt("ambient_minutes", 5)
    fun setAmbientMinutes(ctx: Context, value: Int) = p(ctx).edit().putInt("ambient_minutes", value).apply()
    fun timerUntil(ctx: Context) = p(ctx).getLong("timer_until", 0L)
    fun timerReturn(ctx: Context) = p(ctx).getString("timer_return", null)?.let { runCatching { SonyProtocol.Mode.valueOf(it) }.getOrNull() }
    fun setTimer(ctx: Context, until: Long, back: SonyProtocol.Mode) =
        p(ctx).edit().putLong("timer_until", until).putString("timer_return", back.name).apply()
    fun clearTimer(ctx: Context) = p(ctx).edit().remove("timer_until").remove("timer_return").apply()

    /** Version for which the widget picker previews were last generated. */
    fun previewVersion(ctx: Context): Long = p(ctx).getLong("preview_version", 0L)
    fun setPreviewVersion(ctx: Context, value: Long) = p(ctx).edit().putLong("preview_version", value).apply()

}

/** What to do with noise control when the earbuds connect to the phone. */
enum class OnConnect(val label: String, val mode: SonyProtocol.Mode?) {
    KEEP("Don't change", null),
    ANC("Noise cancelling", SonyProtocol.Mode.ANC),
    AMBIENT("Ambient sound", SonyProtocol.Mode.AMBIENT),
    OFF("Noise control off", SonyProtocol.Mode.OFF);

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: KEEP
    }
}
