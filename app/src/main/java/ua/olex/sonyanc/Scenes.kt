package ua.olex.sonyanc

import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

/**
 * A named one-tap sound setup: noise control (with ambient level and voice focus) and,
 * optionally, an equalizer preset. Applied with the same verified commands as the manual controls.
 */
data class Scene(val id: String, val name: String, val nc: NcState, val eqPreset: Int? = null) {
    /** Ambient level and voice focus only matter in Ambient; the equalizer only when the scene sets one. */
    fun matches(current: NcState?, eq: EqualizerProtocol.State?): Boolean {
        if (current == null || current.mode != nc.mode) return false
        if (nc.mode == Mode.AMBIENT && (current.level != nc.level || current.voice != nc.voice)) return false
        return eqPreset == null || eq?.preset == eqPreset
    }

    /** The noise state to send: other modes keep the user's ambient level and voice focus. */
    fun target(current: NcState?): NcState = if (nc.mode == Mode.AMBIENT) nc else (current ?: nc).copy(mode = nc.mode)

    val summary: String
        get() = buildString {
            append(when (nc.mode) {
                Mode.ANC -> "Noise cancelling"
                Mode.AMBIENT -> "Ambient ${nc.level}" + if (nc.voice) " · voice focus" else ""
                Mode.OFF -> "Noise control off"
            })
            eqPreset?.let { EqualizerProtocol.presets[it] }?.let { append(" · EQ $it") }
        }
}

object Scenes {
    const val MAX = 8
    const val NAME_LENGTH = 24

    val defaults = listOf(
        Scene("focus", "Focus", NcState(Mode.ANC)),
        Scene("commute", "Commute", NcState(Mode.AMBIENT, voice = false, level = 10)),
        Scene("talk", "Conversation", NcState(Mode.AMBIENT, voice = true, level = 20)),
    )

    fun cleanName(name: String) = name.replace(Regex("[|\\r\\n]"), " ").trim().take(NAME_LENGTH)

    /** One scene per line: id|name|mode|voice|level|eqPreset (-1 = keep the equalizer). */
    fun encode(scenes: List<Scene>): String = scenes.joinToString("\n") {
        listOf(it.id, cleanName(it.name), it.nc.mode.name, if (it.nc.voice) 1 else 0, it.nc.level, it.eqPreset ?: -1).joinToString("|")
    }

    fun decode(text: String): List<Scene> = text.lineSequence().mapNotNull { line ->
        val p = line.split("|")
        if (p.size != 6 || p[0].isBlank() || p[1].isBlank()) return@mapNotNull null
        runCatching {
            Scene(
                p[0], p[1], NcState(Mode.valueOf(p[2]), p[3] == "1", p[4].toInt().coerceIn(0, 20)),
                p[5].toInt().takeIf { it in EqualizerProtocol.presets },
            )
        }.getOrNull()
    }.distinctBy { it.id }.take(MAX).toList()
}

/**
 * Listening time from Sony's rated WF-1000XM5 battery life: 8 h with noise cancelling or
 * ambient sound, 12 h with noise control off; the case holds two more full charges.
 * An estimate, not a measurement: LDAC, DSEE and volume shorten it.
 */
object BatteryLife {
    private fun ratedBudMinutes(mode: Mode?) = if (mode == Mode.OFF) 12 * 60 else 8 * 60

    /** Minutes left in the earbuds: the emptier bud decides. */
    fun budsMinutes(b: SonyProtocol.Battery, mode: Mode?): Int? {
        val level = listOfNotNull(b.left, b.right).minOrNull() ?: return null
        return ratedBudMinutes(mode) * level / 100
    }

    /** Extra listening the case can add by recharging the earbuds. */
    fun caseMinutes(b: SonyProtocol.Battery, mode: Mode?): Int? = b.case?.let { 2 * ratedBudMinutes(mode) * it / 100 }

    /** "6 h 30 min", rounded down to 10 minutes (callers handle anything under 10 minutes). */
    fun format(minutes: Int): String {
        val rounded = minutes.coerceAtLeast(10) / 10 * 10
        val h = rounded / 60
        val m = rounded % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun summary(b: SonyProtocol.Battery, mode: Mode?): String? {
        val buds = budsMinutes(b, mode) ?: return null
        val case = caseMinutes(b, mode)?.takeIf { it >= 10 }
        val left = if (buds < 10) "Under 10 min of listening" else "About ${format(buds)} of listening"
        return left + (case?.let { " · case adds ${format(it)}" } ?: "")
    }
}
