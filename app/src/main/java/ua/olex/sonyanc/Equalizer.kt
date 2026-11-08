package ua.olex.sonyanc

/** V2 wire facts cross-checked against Gadgetbridge; no vendor app or account is used. */
object EqualizerProtocol {
    data class State(val preset:Int,val bands:List<Int>)
    val presets=linkedMapOf(0x00 to "Off",0x10 to "Bright",0x11 to "Excited",0x12 to "Mellow",0x13 to "Relaxed",0x14 to "Vocal",0x15 to "Treble boost",0x16 to "Bass boost",0x17 to "Speech",0xA0 to "Custom",0xA1 to "Custom 1",0xA2 to "Custom 2")
    val get=byteArrayOf(0x56,0x00)
    fun parse(payload:ByteArray):State? {
        if(payload.size<4 || (payload[0].toInt() and 255) !in listOf(0x57,0x59) || payload[1].toInt()!=0) return null
        val preset=payload[2].toInt() and 255
        val count=payload[3].toInt() and 255
        if(preset !in presets || count !in listOf(0,6) || payload.size!=4+count) return null
        val bands=payload.drop(4).map {it.toInt() and 255}
        if(bands.any{it !in 0..20}) return null
        return State(preset,bands.map{it-10})
    }
    fun preset(code:Int):ByteArray {
        require(code in presets)
        return byteArrayOf(0x58,0x00,code.toByte(),0x00)
    }
    fun custom(bands:List<Int>):ByteArray {
        require(bands.size==6 && bands.all{it in -10..10})
        return byteArrayOf(0x58,0x00,0xA0.toByte(),0x06)+bands.map{(it+10).toByte()}.toByteArray()
    }
}
