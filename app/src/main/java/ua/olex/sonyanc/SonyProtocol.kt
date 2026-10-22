package ua.olex.sonyanc

/**
 * Sony "MDR" protocol over Bluetooth RFCOMM.
 *
 * Frame on the wire:
 *   0x3E | type | seq | len(4 bytes, BE) | payload | checksum | 0x3C
 * checksum = (type + seq + len bytes + payload bytes) & 0xFF
 * Bytes 0x3C/0x3D/0x3E between the markers are escaped as 0x3D, (b & 0xEF).
 */
object SonyProtocol {
    const val START: Byte = 0x3E
    const val END: Byte = 0x3C
    const val ESC: Byte = 0x3D

    const val TYPE_ACK: Byte = 0x01
    const val TYPE_COMMAND: Byte = 0x0C
    const val TYPE_COMMAND_2: Byte = 0x0E

    fun frame(type: Byte, seq: Int, payload: ByteArray): ByteArray {
        val body = ArrayList<Byte>(payload.size + 7)
        body += type
        body += seq.toByte()
        val n = payload.size
        body += (n ushr 24).toByte(); body += (n ushr 16).toByte()
        body += (n ushr 8).toByte(); body += n.toByte()
        payload.forEach { body += it }
        body += (body.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()

        val out = ArrayList<Byte>(body.size + 4)
        out += START
        for (b in body) {
            if (b == START || b == END || b == ESC) {
                out += ESC; out += (b.toInt() and 0xEF).toByte()
            } else out += b
        }
        out += END
        return out.toByteArray()
    }

    data class Frame(val type: Int, val seq: Int, val payload: ByteArray, val checksumOk: Boolean)

    /** Unescape and parse the bytes between START and END (markers excluded). */
    fun parse(raw: ByteArray): Frame? {
        val un = ArrayList<Byte>(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i] == ESC) {
                if (i + 1 >= raw.size || raw[i + 1].toInt() !in listOf(0x2C, 0x2D, 0x2E)) return null
                un += (raw[i + 1].toInt() or 0x10).toByte(); i += 2
            } else { un += raw[i]; i++ }
        }
        if (un.size < 7) return null
        val len = ((un[2].toInt() and 0xFF) shl 24) or ((un[3].toInt() and 0xFF) shl 16) or
                ((un[4].toInt() and 0xFF) shl 8) or (un[5].toInt() and 0xFF)
        if (len < 0 || len > 4096 || len != un.size - 7) return null
        val sum = un.subList(0, 6 + len).sumOf { it.toInt() and 0xFF } and 0xFF
        return Frame(
            type = un[0].toInt() and 0xFF,
            seq = un[1].toInt() and 0xFF,
            payload = un.subList(6, 6 + len).toByteArray(),
            checksumOk = sum == (un[6 + len].toInt() and 0xFF)
        )
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    fun parseHex(s: String): ByteArray =
        s.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).filter { it.length == 2 }
            .map { it.toInt(16).toByte() }.toByteArray()

    private fun u(b: Byte) = b.toInt() and 0xFF

    // ---- Noise cancelling / ambient sound (V2, WF/WH-1000XM5) -------------------------
    // get:    66 17
    // reply:  67 17 01 <effect on> <0=NC,1=ambient> <focus voice> <ambient level 0..20>
    // notify: 69 17 …   (same layout, sent when changed on the earbuds)
    // set:    68 17 …   (same layout)

    enum class Mode(val label: String, val short: String) {
        ANC("Noise cancelling", "ANC"),
        AMBIENT("Ambient sound", "Ambient"),
        OFF("Noise control off", "Off"),
    }

    data class NcState(val mode: Mode, val voice: Boolean = false, val level: Int = 20)

    const val NC_TYPE: Byte = 0x17
    val NC_GET = byteArrayOf(0x66, NC_TYPE)

    fun ncSet(s: NcState): ByteArray = byteArrayOf(
        0x68.toByte(), NC_TYPE, 0x01.toByte(),
        (if (s.mode == Mode.OFF) 0 else 1).toByte(),
        (if (s.mode == Mode.AMBIENT) 1 else 0).toByte(),
        (if (s.voice) 1 else 0).toByte(),
        s.level.coerceIn(0, 20).toByte(),
    )

    fun parseNc(p: ByteArray): NcState? {
        if (p.size != 7 || p[1] != NC_TYPE || u(p[2]) != 1) return null
        if (u(p[3]) !in 0..1 || u(p[4]) !in 0..1 || u(p[5]) !in 0..1 || u(p[6]) !in 0..20) return null
        if (u(p[0]) != 0x67 && u(p[0]) != 0x69) return null
        val mode = when {
            p[3].toInt() == 0 -> Mode.OFF
            p[4].toInt() == 1 -> Mode.AMBIENT
            else -> Mode.ANC
        }
        return NcState(mode, p[5].toInt() == 1, u(p[6]).coerceIn(0, 20))
    }

    // V2 dual earbuds / case. Unknown and unavailable values remain unknown.
    data class Battery(val left: Int? = null, val right: Int? = null, val case: Int? = null,
                       val leftCharging: Boolean = false, val rightCharging: Boolean = false,
                       val caseCharging: Boolean = false)
    val BATTERY_GETS = listOf(byteArrayOf(0x22, 0x01), byteArrayOf(0x22, 0x09), byteArrayOf(0x22, 0x0A))
    private fun pct(b: Byte): Int? = u(b).takeIf { it in 1..100 }
    fun parseBattery(p: ByteArray, old: Battery): Battery? {
        if (p.size < 4 || u(p[0]) !in listOf(0x23, 0x25)) return null
        return when (u(p[1])) {
            0x01, 0x09 -> if (p.size >= 6 && u(p[3]) in listOf(0,1,2,3,255) && u(p[5]) in listOf(0,1,2,3,255))
                old.copy(left = pct(p[2]) ?: old.left, leftCharging = p[3].toInt() == 1,
                    right = pct(p[4]) ?: old.right, rightCharging = p[5].toInt() == 1) else null
            0x0A -> if (p.size >= 4 && u(p[3]) in listOf(0,1,2,3,255))
                old.copy(case = pct(p[2]) ?: old.case, caseCharging = p[3].toInt() == 1) else null
            else -> null
        }
    }

    const val INIT_HEX = "00 00"
}

