package ua.olex.sonyanc

/**
 * Sony "MDR" protocol over Bluetooth RFCOMM (reverse-engineered, see Gadgetbridge).
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

    /** Decoded incoming frame. */
    data class Frame(val type: Int, val seq: Int, val payload: ByteArray, val checksumOk: Boolean)

    /** Unescape and parse the bytes between START and END (markers excluded). */
    fun parse(raw: ByteArray): Frame? {
        val un = ArrayList<Byte>(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i] == ESC && i + 1 < raw.size) {
                un += (raw[i + 1].toInt() or 0x10).toByte(); i += 2
            } else { un += raw[i]; i++ }
        }
        if (un.size < 7) return null
        val len = ((un[2].toInt() and 0xFF) shl 24) or ((un[3].toInt() and 0xFF) shl 16) or
                ((un[4].toInt() and 0xFF) shl 8) or (un[5].toInt() and 0xFF)
        if (un.size < 6 + len + 1) return null
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

    /**
     * Payloads for the "V1" family (WH-1000XM3 / XM4, WF-1000XM3 / XM4).
     * Layout: 68 02 <on?11:00> 02 <anc:02 / ambient:00> 01 <voice> <ambient level 0..20>
     * XM5-generation devices use protocol V2 with different payloads — use the raw field
     * in the app to experiment.
     */
    enum class Mode(val label: String, val payloadHex: String) {
        ANC("Шумозаглушення", "68 02 11 02 02 01 00 14"),
        AMBIENT("Прозорість", "68 02 11 02 00 01 00 14"),
        OFF("Вимк", "68 02 00 02 00 01 00 14"),
    }

    /** "Get protocol info" — the headphones usually want this first. */
    const val INIT_HEX = "00 00"
}
