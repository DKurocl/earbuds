package ua.olex.sonyanc

/** Sony V2 audio codec inquiry. Describes the reported codec, never a preferred codec. */
object CodecProtocol {
    const val LDAC = 0x10
    val get = byteArrayOf(0x12, 0x02)

    data class State(val code: Int) {
        val label: String get() = when(code) {
            0x00 -> "Not reported"
            0x01 -> "SBC"
            0x02 -> "AAC"
            LDAC -> "LDAC"
            0x20 -> "aptX"
            0x21 -> "aptX HD"
            else -> "Unknown (0x%02X)".format(code)
        }
        val isKnown: Boolean get() = code in setOf(0x01, 0x02, 0x10, 0x20, 0x21)
    }

    fun parse(payload: ByteArray): State? {
        if(payload.size != 3 || (payload[0].toInt() and 255) !in listOf(0x13, 0x15) || payload[1].toInt() != 0x02) return null
        return State(payload[2].toInt() and 255)
    }
}
