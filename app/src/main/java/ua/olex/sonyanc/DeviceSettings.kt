package ua.olex.sonyanc

/** V2 settings exposed only after a valid device reply; writes are followed by read-back. */
object DeviceSettingsProtocol {
    enum class Feature(val title:String,val get:ByteArray) {
        DSEE("DSEE",byteArrayOf(0xE6.toByte(),0x01)),
        AUTO_PAUSE("Pause when removed",byteArrayOf(0xF6.toByte(),0x01));
        fun set(enabled:Boolean):ByteArray = when(this) {
            DSEE -> byteArrayOf(0xE8.toByte(),0x01,if(enabled) 1 else 0)
            AUTO_PAUSE -> byteArrayOf(0xF8.toByte(),0x01,if(enabled) 0 else 1)
        }
    }
    fun parse(p:ByteArray):Pair<Feature,Boolean>? {
        if(p.size!=3 || p[1].toInt()!=1 || p[2].toInt() !in 0..1) return null
        return when(p[0].toInt() and 255) {
            0xE7,0xE9 -> Feature.DSEE to (p[2].toInt()==1)
            0xF7,0xF9 -> Feature.AUTO_PAUSE to (p[2].toInt()==0)
            else -> null
        }
    }
}
