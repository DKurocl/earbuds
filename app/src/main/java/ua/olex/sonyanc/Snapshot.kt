package ua.olex.sonyanc

/** Pure, Android-free helpers for cached state shown before the earbuds answer. */
object Snapshot {
    /** Battery cache: six comma-separated integers, -1 for an unknown level. */
    fun encodeBattery(b: SonyProtocol.Battery): String = listOf(
        b.left ?: -1, b.right ?: -1, b.case ?: -1,
        if (b.leftCharging) 1 else 0, if (b.rightCharging) 1 else 0, if (b.caseCharging) 1 else 0,
    ).joinToString(",")

    fun decodeBattery(raw: String?): SonyProtocol.Battery? {
        val v = raw?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        if (v.size != 6) return null
        if (v.take(3).any { it != -1 && it !in 1..100 } || v.drop(3).any { it !in 0..1 }) return null
        fun level(i: Int) = v[i].takeIf { it != -1 }
        if (level(0) == null && level(1) == null && level(2) == null) return null
        return SonyProtocol.Battery(level(0), level(1), level(2), v[3] == 1, v[4] == 1, v[5] == 1)
    }

    /** Short English age label: "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
    fun ago(nowMs: Long, thenMs: Long): String? {
        if (thenMs <= 0L) return null
        val minutes = ((nowMs - thenMs).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            minutes < 48 * 60 -> "yesterday"
            else -> "${minutes / (24 * 60)} days ago"
        }
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
}
