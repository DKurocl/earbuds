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
    fun setDisplayName(ctx: Context, value: String) = p(ctx).edit().putString("display_name",value).apply()
    fun deviceName(ctx: Context): String? = p(ctx).getString("device_name", null)
    fun saveDevice(ctx: Context, address: String, name: String?) =
        p(ctx).edit().putString("device", address).putString("device_name", name)
            .remove("battery").remove("battery_at").remove("linked").remove("last_seen").apply()

    fun lastNc(ctx: Context): SonyProtocol.NcState? {
        val prefs = p(ctx)
        val m = prefs.getString("mode", null) ?: return null
        val mode = runCatching { SonyProtocol.Mode.valueOf(m) }.getOrNull() ?: return null
        return SonyProtocol.NcState(mode, prefs.getBoolean("voice", false), prefs.getInt("level", 20))
    }

    fun saveNc(ctx: Context, s: SonyProtocol.NcState) =
        p(ctx).edit().putString("mode", s.mode.name).putBoolean("voice", s.voice).putInt("level", s.level).apply()

    /** Last decoded battery and when it was read; shown dimmed until live values arrive. */
    fun battery(ctx: Context): SonyProtocol.Battery? = Snapshot.decodeBattery(p(ctx).getString("battery", null))
    fun batteryAt(ctx: Context): Long = p(ctx).getLong("battery_at", 0L)
    fun saveBattery(ctx: Context, b: SonyProtocol.Battery, at: Long) =
        p(ctx).edit().putString("battery", Snapshot.encodeBattery(b)).putLong("battery_at", at).apply()

    /** Android's Bluetooth link to the earbuds: true/false from ACL broadcasts, null when unknown. */
    fun linked(ctx: Context): Boolean? = p(ctx).takeIf { it.contains("linked") }?.getBoolean("linked", false)
    fun setLinked(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("linked", value).apply()
    fun lastSeen(ctx: Context): Long = p(ctx).getLong("last_seen", 0L)
    fun setLastSeen(ctx: Context, at: Long) = p(ctx).edit().putLong("last_seen", at).apply()

    fun onConnect(ctx: Context): Snapshot.OnConnect = Snapshot.OnConnect.from(p(ctx).getString("on_connect", null))
    fun setOnConnect(ctx: Context, value: Snapshot.OnConnect) = p(ctx).edit().putString("on_connect", value.name).apply()
}
