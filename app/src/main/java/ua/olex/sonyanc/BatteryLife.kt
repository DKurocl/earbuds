package ua.olex.sonyanc

import ua.olex.sonyanc.SonyProtocol.Mode

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

    /** Short duration for a big number: "5 h 30 min", "< 10 min". */
    fun short(minutes: Int): String = if (minutes < 10) "< 10 min" else format(minutes)

    fun summary(b: SonyProtocol.Battery, mode: Mode?): String? {
        val buds = budsMinutes(b, mode) ?: return null
        val case = caseMinutes(b, mode)?.takeIf { it >= 10 }
        val left = if (buds < 10) "Under 10 min of listening" else "About ${format(buds)} of listening"
        return left + (case?.let { " · case adds ${format(it)}" } ?: "")
    }
}
