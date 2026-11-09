package ua.olex.sonyanc

import ua.olex.sonyanc.SonyProtocol.Mode

/**
 * Listening time. Starts from Sony's rated WF-1000XM5 battery life (8 h with noise cancelling or
 * ambient sound, 12 h with noise control off; the case holds two more full charges) and switches
 * to the owner's own measured drain once [BatteryLog] has seen enough real use, so LDAC, DSEE
 * and volume are accounted for. Still an estimate.
 */
object BatteryLife {
    private fun ratedBudMinutes(mode: Mode?) = if (mode == Mode.OFF) 12 * 60 else 8 * 60

    /**
     * Minutes from 100 % to empty for [mode]: measured when [rate] (percent per minute) is known,
     * kept within half to one and a half times the rating so one odd session cannot mislead.
     */
    fun fullChargeMinutes(mode: Mode?, rate: Double? = null): Int {
        val rated = ratedBudMinutes(mode)
        return rate?.takeIf { it > 0 }?.let { (100 / it).toInt().coerceIn(rated / 2, rated * 3 / 2) } ?: rated
    }

    /** Minutes left in the earbuds: the emptier bud decides. */
    fun budsMinutes(b: SonyProtocol.Battery, mode: Mode?, rate: Double? = null): Int? {
        val level = listOfNotNull(b.left, b.right).minOrNull() ?: return null
        return fullChargeMinutes(mode, rate) * level / 100
    }

    /** Extra listening the case can add by recharging the earbuds. */
    fun caseMinutes(b: SonyProtocol.Battery, mode: Mode?, rate: Double? = null): Int? =
        b.case?.let { 2 * fullChargeMinutes(mode, rate) * it / 100 }

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

/**
 * One battery reading: the emptier earbud ([level]) and the case, with what the earbuds were doing.
 * [session] changes on every connect (out of the case).
 */
data class BatterySample(val at: Long, val level: Int, val charging: Boolean, val mode: Mode?, val session: Int, val case: Int? = null)

/** The owner's measured drain for one mode bucket: percent per minute, from [minutes] of real use. */
data class Drain(val rate: Double, val minutes: Double)

/**
 * The owner's real drain, learned only from readings the app already takes (on connect, while a
 * screen is open). Pairs of readings count when they are in the same out-of-case session, not
 * charging, close enough in time and falling. Noise cancelling and ambient share a bucket; off
 * has its own, like Sony's ratings.
 */
object BatteryLog {
    const val MAX = 600
    /** A week of history for the Battery page, plus a day of margin. */
    const val KEEP_MS = 8 * 24 * 60 * 60_000L
    private const val MAX_GAP = 3 * 60 * 60_000L
    private const val MIN_MINUTES = 30.0
    private const val MIN_DROP = 5

    fun encode(samples: List<BatterySample>): String = samples.joinToString(";") {
        "${it.at},${it.level},${if (it.charging) 1 else 0},${it.mode?.name ?: "-"},${it.session},${it.case ?: "-"}"
    }

    fun decode(text: String?): List<BatterySample> = text.orEmpty().split(';').mapNotNull { row ->
        val p = row.split(',')
        if (p.size != 5 && p.size != 6) return@mapNotNull null // 5 fields before the case was recorded
        val at = p[0].toLongOrNull() ?: return@mapNotNull null
        val level = p[1].toIntOrNull()?.takeIf { it in 1..100 } ?: return@mapNotNull null
        val session = p[4].toIntOrNull() ?: return@mapNotNull null
        BatterySample(at, level, p[2] == "1", Mode.entries.firstOrNull { it.name == p[3] }, session,
            p.getOrNull(5)?.toIntOrNull()?.takeIf { it in 1..100 })
    }

    /** Appends a reading; repeats of the same level within ten minutes add nothing. */
    fun add(samples: List<BatterySample>, s: BatterySample): List<BatterySample> {
        val last = samples.lastOrNull()
        if (last != null && last.session == s.session && last.level == s.level && last.case == s.case &&
            last.charging == s.charging && s.at - last.at < 10 * 60_000L) return samples
        return (samples.filter { s.at - it.at <= KEEP_MS } + s).takeLast(MAX)
    }

    private fun bucket(mode: Mode?) = if (mode == Mode.OFF) 1 else 0

    /** Percent per minute for [mode]'s bucket, or null until there is enough real use. */
    fun rate(samples: List<BatterySample>, mode: Mode?): Double? = drain(samples, mode)?.rate

    /** The measured drain and how much use it rests on, or null until there is enough. */
    fun drain(samples: List<BatterySample>, mode: Mode?): Drain? {
        var minutes = 0.0
        var drop = 0
        for ((a, b) in samples.zipWithNext()) {
            val dt = b.at - a.at
            if (a.session != b.session || a.charging || b.charging || dt <= 0 || dt > MAX_GAP) continue
            if (b.level > a.level || bucket(a.mode) != bucket(mode)) continue
            minutes += dt / 60_000.0
            drop += a.level - b.level
        }
        return if (minutes >= MIN_MINUTES && drop >= MIN_DROP) Drain(drop / minutes, minutes) else null
    }
}
