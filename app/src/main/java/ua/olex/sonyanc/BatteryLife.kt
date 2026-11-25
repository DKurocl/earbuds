package ua.olex.sonyanc

import ua.olex.sonyanc.SonyProtocol.Mode

/**
 * Listening time. Starts from Sony's WF-1000XM5 playback ratings for the last reported codec,
 * with a two-charge case reserve, and switches
 * to the owner's own measured drain once [BatteryLog] has seen enough real use, so LDAC, DSEE
 * and volume are accounted for. Still an estimate.
 */
object BatteryLife {
    private fun ratedBudMinutes(mode: Mode?) = if (mode == Mode.OFF) 12 * 60 else 8 * 60

    /**
     * Minutes from 100 % to empty for [mode]: measured when [rate] (percent per minute) is known,
     * kept within half to one and a half times the rating so one odd session cannot mislead.
     */
    fun fullChargeMinutes(mode: Mode?, rate: Double? = null, codec: Int? = null): Int {
        val rated = ratedBudMinutes(mode)
        val measured = rate?.takeIf { it > 0 }?.let { (100 / it).toInt().coerceIn(rated / 2, rated * 3 / 2) }
        return measured ?: if (codec == CodecProtocol.LDAC) (if (mode == Mode.OFF) 8 else 5) * 60 else rated
    }

    /** Minutes left in the earbuds: the emptier bud decides. */
    fun budsMinutes(b: SonyProtocol.Battery, mode: Mode?, rate: Double? = null, codec: Int? = null): Int? {
        val level = listOfNotNull(b.left, b.right).minOrNull() ?: return null
        return fullChargeMinutes(mode, rate, codec) * level / 100
    }

    /** Estimates use the current buds when at least one has reported, or the last known pair. */
    internal fun forEstimate(battery: SonyProtocol.Battery, liveParts: Set<Int>): SonyProtocol.Battery {
        if (0 !in liveParts && 1 !in liveParts) return battery
        return battery.copy(
            left = battery.left.takeIf { 0 in liveParts },
            right = battery.right.takeIf { 1 in liveParts },
        )
    }

    /** Extra listening the case can add by recharging the earbuds. */
    fun caseMinutes(b: SonyProtocol.Battery, mode: Mode?, rate: Double? = null, codec: Int? = null): Int? =
        b.case?.let { 2 * fullChargeMinutes(mode, rate, codec) * it / 100 }

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
data class BatterySample(
    val at: Long, val level: Int?, val charging: Boolean, val mode: Mode?, val session: Int,
    val case: Int? = null, val left: Int? = null, val right: Int? = null,
) {
    companion object {
        internal fun fromReading(at: Long, battery: SonyProtocol.Battery, parts: Set<Int>, mode: Mode?, session: Int): BatterySample? {
            if (parts.isEmpty()) return null
            val left = battery.left.takeIf { 0 in parts }
            val right = battery.right.takeIf { 1 in parts }
            val level = listOfNotNull(left, right).minOrNull()
            val charging = (left != null && battery.leftCharging) || (right != null && battery.rightCharging)
            return BatterySample(at, level, charging, mode, session, battery.case.takeIf { 2 in parts }, left, right)
        }
    }
}

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
        "${it.at},${it.level ?: "-"},${if (it.charging) 1 else 0},${it.mode?.name ?: "-"},${it.session},${it.case ?: "-"},${it.left ?: "-"},${it.right ?: "-"},2"
    }

    fun decode(text: String?): List<BatterySample> = text.orEmpty().split(';').mapNotNull { row ->
        val p = row.split(',')
        if (p.size !in listOf(5, 6, 8, 9) || (p.size == 9 && p[8] != "2")) return@mapNotNull null
        fun marker(index: Int) = p.getOrNull(index)?.toIntOrNull()?.takeIf { it in 1..100 }
        val at = p[0].toLongOrNull() ?: return@mapNotNull null
        val session = p[4].toIntOrNull() ?: return@mapNotNull null
        val left = marker(6)
        val right = marker(7)
        val case = marker(5).takeUnless { p.size == 8 && (left != null || right != null) }
        // Earlier eight-field records repeated the cached earbud minimum on case-only packets.
        val level = if (p.size == 8) listOfNotNull(left, right).minOrNull() else marker(1)
        if (level == null && case == null) return@mapNotNull null
        BatterySample(at, level, p[2] == "1", Mode.entries.firstOrNull { it.name == p[3] }, session, case, left, right)
    }

    fun recent(samples: List<BatterySample>, now: Long): List<BatterySample> {
        val range = (now - KEEP_MS)..now
        if (samples.size <= MAX && samples.all { it.at in range }) return samples
        return samples.filter { it.at in range }.takeLast(MAX)
    }

    /** Appends a reading; repeats of the same level within ten minutes add nothing. */
    fun add(samples: List<BatterySample>, s: BatterySample): List<BatterySample> {
        // Bud and case notifications can alternate within the same read. Check the most recent
        // comparable packet so stable charge does not rewrite history for every pair of replies.
        val caseOnly = s.level == null
        val budsOnly = s.case == null
        for (index in samples.indices.reversed()) {
            val previous = samples[index]
            if (previous.session != s.session || previous.mode != s.mode ||
                s.at - previous.at !in 0 until 10 * 60_000L) break
            val otherPart = (caseOnly && previous.case == null) || (budsOnly && previous.level == null)
            if (otherPart) continue
            if (previous.level == s.level && previous.case == s.case &&
                previous.left == s.left && previous.right == s.right && previous.charging == s.charging) return samples
            break
        }
        return recent(samples + s, s.at)
    }

    private fun bucket(mode: Mode?) = when (mode) {
        null -> null
        Mode.OFF -> 1
        else -> 0
    }

    private fun intervals(
        samples: List<BatterySample>,
        level: (BatterySample) -> Int? = BatterySample::level,
        requireSameParts: Boolean = true,
    ) = samples.asSequence()
        .filter { level(it) != null }
        .zipWithNext()
        .filter { (a, b) ->
            a.session == b.session && !a.charging && !b.charging && b.at - a.at in 1..MAX_GAP &&
                (!requireSameParts || ((a.left != null) == (b.left != null) && (a.right != null) == (b.right != null)))
        }

    /** Estimated connected minutes for the last week and the portion attributed to noise cancelling. */
    data class Week(val minutes: Int, val anc: Int)

    /**
     * Estimate the observed connection time from consecutive readings in the same non-charging
     * session at most [MAX_GAP] apart. The first reading supplies the interval's mode.
     */
    fun week(samples: List<BatterySample>, now: Long): Week {
        var all = 0L; var anc = 0L
        val start = now - 7 * 24 * 60 * 60_000L
        for ((a, b) in intervals(samples)) {
            val dt = (minOf(now, b.at) - maxOf(start, a.at)).coerceAtLeast(0)
            all += dt
            if (a.mode == Mode.ANC) anc += dt
        }
        return Week((all / 60_000L).toInt(), (anc / 60_000L).toInt())
    }

    /** Percent per minute for [mode]'s bucket, or null until there is enough real use. */
    fun rate(samples: List<BatterySample>, mode: Mode?): Double? = drain(samples, mode)?.rate

    internal data class EarbudDrain(val left: Drain, val right: Drain) {
        fun minutesLeft(battery: SonyProtocol.Battery, mode: Mode?): Int? {
            val leftMinutes = battery.left?.let { BatteryLife.fullChargeMinutes(mode, left.rate) * it / 100 }
            val rightMinutes = battery.right?.let { BatteryLife.fullChargeMinutes(mode, right.rate) * it / 100 }
            return listOfNotNull(leftMinutes, rightMinutes).minOrNull()
        }
    }

    internal fun perEarbudDrain(samples: List<BatterySample>, mode: Mode?): EarbudDrain? {
        val left = measure(samples, mode, BatterySample::left, requireSameParts = false) ?: return null
        val right = measure(samples, mode, BatterySample::right, requireSameParts = false) ?: return null
        return EarbudDrain(left, right)
    }

    /** The measured drain and how much use it rests on, or null until there is enough. */
    fun drain(samples: List<BatterySample>, mode: Mode?): Drain? = measure(samples, mode, BatterySample::level)

    private fun measure(
        samples: List<BatterySample>, mode: Mode?, level: (BatterySample) -> Int?, requireSameParts: Boolean = true,
    ): Drain? {
        var minutes = 0.0
        var drop = 0
        val modeBucket = bucket(mode) ?: return null
        for ((a, b) in intervals(samples, level, requireSameParts)) {
            val start = level(a) ?: continue
            val end = level(b) ?: continue
            if (end > start || bucket(a.mode) != modeBucket || bucket(b.mode) != modeBucket) continue
            minutes += (b.at - a.at) / 60_000.0
            drop += start - end
        }
        return if (minutes >= MIN_MINUTES && drop >= MIN_DROP) Drain(drop / minutes, minutes) else null
    }
}

/** How long a full charge lasted (noise cancelling & ambient) in one ISO week, from [hours] of real use. */
data class WeekHealth(val week: Long, val minutes: Int, val hours: Int)

/**
 * Battery health over months: one measured full-charge figure per week (up to a year), and a
 * quiet verdict for the Battery page. Built only from BatteryLog, never from extra reads.
 */
object BatteryHealth {
    const val MAX_WEEKS = 52
    private const val MIN_HOURS = 1
    /** A drop this large against the best early weeks, over at least [MIN_SPAN] weeks, is worth saying. */
    private const val NOTABLE = 0.10
    private const val MIN_SPAN = 4

    /** Monday-based week number since the epoch (UTC days / 7, shifted so weeks start on Monday). */
    fun weekOf(at: Long): Long = Math.floorDiv(Math.floorDiv(at, 86_400_000L) + 3, 7L)

    fun encode(h: List<WeekHealth>) = h.joinToString(";") { "${it.week},${it.minutes},${it.hours}" }
    fun decode(text: String?): List<WeekHealth> = text.orEmpty().split(';').mapNotNull { row ->
        val p = row.split(',').map { it.toLongOrNull() ?: return@mapNotNull null }
        if (p.size != 3 || p[1] <= 0) null else WeekHealth(p[0], p[1].toInt(), p[2].toInt())
    }

    fun recent(history: List<WeekHealth>, week: Long): List<WeekHealth> {
        val window = (week - MAX_WEEKS + 1)..week
        return if (history.size <= MAX_WEEKS && history.all { it.week in window }) history
        else history.filter { it.week in window }.takeLast(MAX_WEEKS)
    }

    /** Replaces this week's figure with the latest measurement, once it rests on enough use. */
    fun record(history: List<WeekHealth>, week: Long, drain: Drain?): List<WeekHealth> {
        val recent = recent(history, week)
        drain ?: return recent
        val hours = (drain.minutes / 60).toInt()
        if (hours < MIN_HOURS) return recent
        val minutes = BatteryLife.fullChargeMinutes(Mode.ANC, drain.rate)
        return (recent.filter { it.week != week } + WeekHealth(week, minutes, hours)).sortedBy { it.week }
    }

    sealed interface Verdict {
        /** Too little history to compare yet. */
        data class Tracking(val since: Long, val minutes: Int) : Verdict
        data class Steady(val since: Long, val minutes: Int) : Verdict
        /** [percent] shorter than the best of the first weeks tracked. */
        data class Declined(val since: Long, val minutes: Int, val baseline: Int, val percent: Int) : Verdict
    }

    fun verdict(history: List<WeekHealth>): Verdict? {
        if (history.isEmpty()) return null
        val first = history.first()
        val now = history.last()
        if (history.size < 2 || now.week - first.week < MIN_SPAN) return Verdict.Tracking(first.week, now.minutes)
        val baseline = history.take(3).maxOf { it.minutes }
        val change = (baseline - now.minutes).toDouble() / baseline
        return if (change >= NOTABLE) Verdict.Declined(first.week, now.minutes, baseline, (change * 100).toInt())
        else Verdict.Steady(first.week, now.minutes)
    }
}
