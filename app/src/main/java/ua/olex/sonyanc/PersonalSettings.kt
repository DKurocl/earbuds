package ua.olex.sonyanc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class PersonalProfile(
    val noise: SonyProtocol.NcState,
    val equalizer: EqualizerProtocol.State?,
) {
    fun encode() = JSONObject()
        .put("mode", noise.mode.name)
        .put("voice", noise.voice)
        .put("level", noise.level)
        .apply {
            equalizer?.let {
                put("preset", it.preset)
                put("bands", JSONArray(it.bands))
            }
        }.toString()

    companion object {
        fun decode(encoded: String): PersonalProfile? = runCatching {
            val json = JSONObject(encoded)
            val level = json.getInt("level").also { require(it in 0..20) }
            val noise = SonyProtocol.NcState(SonyProtocol.Mode.valueOf(json.getString("mode")), json.getBoolean("voice"), level)
            val eq = if (json.has("preset")) {
                val preset = json.getInt("preset").also { require(it in EqualizerProtocol.presets) }
                val values = json.getJSONArray("bands")
                val bands = List(values.length()) { values.getInt(it) }
                require(bands.size in listOf(0, 6) && bands.all { it in -10..10 })
                require(preset != 0xA0 || bands.size == 6)
                EqualizerProtocol.State(preset, bands)
            } else null
            PersonalProfile(noise, eq)
        }.getOrNull()
    }
}

/** Portable preferences only. Android manages pairing and permissions on the destination phone. */
internal object SettingsBackup {
    private const val MAX_BYTES = 32_768
    private val booleans = setOf("dynamic_color", "haptics", "tile_two_way")
    private val profiles = setOf("profile_1", "profile_2")

    fun encode(ctx: Context): ByteArray {
        val settings = JSONObject()
        for ((key, value) in Prefs.p(ctx).all) {
            if (valid(key, value)) settings.put(key, value)
        }
        return JSONObject().put("version", 1).put("settings", settings).toString(2).encodeToByteArray()
    }

    fun restore(ctx: Context, input: java.io.InputStream) {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES)
        val backup = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
        require(backup.getInt("version") == 1)
        val settings = backup.getJSONObject("settings")
        val entries = settings.keys().asSequence().associateWith(settings::get)
        require(entries.all { (key, value) -> valid(key, value) })
        val editor = Prefs.p(ctx).edit()
        (booleans + profiles + setOf("on_connect", "weekend_mode", "ambient_minutes")).forEach { editor.remove(it) }
        for ((key, value) in entries) when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
        }
        check(editor.commit()) { "Could not save preferences" }
    }

    private fun valid(key: String, value: Any?): Boolean = when (key) {
        in booleans -> value is Boolean
        in profiles -> value is String && PersonalProfile.decode(value) != null
        "on_connect", "weekend_mode" -> value is String && OnConnect.entries.any { it.name == value }
        "ambient_minutes" -> value is Int && value in AmbientTimer.CHOICES
        else -> false
    }
}
