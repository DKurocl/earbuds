package ua.olex.sonyanc

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("sonyanc", Context.MODE_PRIVATE)

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

    fun clearBattery(ctx: Context) = p(ctx).edit().remove("battery_at").remove("linked").remove("last_seen").apply()

    // ---- Android's Bluetooth link, from ACL broadcasts (see ConnectionReceiver) ----
    /** True/false once a broadcast or a working control link said so; null while unknown. */
    fun linked(ctx: Context): Boolean? = p(ctx).takeIf { it.contains("linked") }?.getBoolean("linked", false)
    fun setLinked(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("linked", value).apply()
    /** When the earbuds last disconnected from the phone (usually: went back in the case). */
    fun lastSeen(ctx: Context): Long = p(ctx).getLong("last_seen", 0L)
    fun setLastSeen(ctx: Context, at: Long) = p(ctx).edit().putLong("last_seen", at).apply()

    fun onConnect(ctx: Context): OnConnect = OnConnect.from(p(ctx).getString("on_connect", null))
    fun setOnConnect(ctx: Context, value: OnConnect) = p(ctx).edit().putString("on_connect", value.name).apply()

    /** Version for which the widget picker previews were last generated. */
    fun previewVersion(ctx: Context): Long = p(ctx).getLong("preview_version", 0L)
    fun setPreviewVersion(ctx: Context, value: Long) = p(ctx).edit().putLong("preview_version", value).apply()

    // ---- scenes ----
    fun scenes(ctx: Context): List<Scene> = p(ctx).getString("scenes", null)?.let(Scenes::decode) ?: Scenes.defaults
    fun saveScenes(ctx: Context, scenes: List<Scene>) = p(ctx).edit().putString("scenes", Scenes.encode(scenes)).apply()
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
