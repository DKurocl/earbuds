package ua.olex.sonyanc

/** Strict, read-gated MDR V2 settings. Unknown payloads never become writable controls. */
object AdvancedSettings {
    data class Choice(val code:Int,val label:String)
    data class Field(val label:String,val index:Int,val choices:List<Choice>)
    private fun choices(vararg pairs:Pair<Int,String>)=pairs.map{Choice(it.first,it.second)}
    private val toggle=choices(0 to "On",1 to "Off")
    private val touch=choices(0 to "Noise control",0x10 to "Volume",0x20 to "Playback",0xFF to "Off")
    enum class Key(val title:String,val getCode:Int,val subtype:Int,val type:Byte=SonyProtocol.TYPE_COMMAND) {
        TOUCH("Touch controls",0xF6,0x03),
        // The whole touch panel on or off (Sony's "touch sensor", D6 D2). Two bytes: the first says
        // how the second reads, 0 = MDR V2 (second byte 0 = on), 1 = V1 (second byte 1 = on).
        PANEL("Touch panel",0xD6,0xD2),
        CYCLE("Noise control cycle",0xFA,0x03),
        CHAT("Speak-to-Chat",0xF6,0x0C),
        CHAT_CONFIG("Conversation detection",0xFA,0x0C),
        POWER("Automatic power off",0x26,0x05),
        SIDETONE("Hear my voice in calls",0xF6,0x08),
        GESTURES("Head gestures",0xF6,0x0F),
        QUALITY("Bluetooth quality",0xE6,0x00),
        GUIDANCE("Voice guidance",0x46,0x01,SonyProtocol.TYPE_COMMAND_2),
        GUIDANCE_VOLUME("Voice guidance volume",0x46,0x20,SonyProtocol.TYPE_COMMAND_2);
        val get get()=byteArrayOf(getCode.toByte(),subtype.toByte())
        val fields get():List<Field> = when(this) {
            TOUCH -> listOf(Field("Left",1,touch),Field("Right",2,touch))
            PANEL -> listOf(Field("State",1,choices(0 to "0",1 to "1")))
            CYCLE -> listOf(Field("Modes",4,choices(1 to "ANC · Ambient · Off",2 to "ANC · Ambient",3 to "ANC · Off",4 to "Ambient · Off")))
            CHAT, SIDETONE, GESTURES, GUIDANCE -> listOf(Field("Enabled",0,toggle))
            CHAT_CONFIG -> listOf(Field("Sensitivity",0,choices(0 to "Auto",1 to "High",2 to "Low")),Field("Resume music",1,choices(0 to "Short",1 to "Standard",2 to "Long",3 to "Manually")))
            POWER -> listOf(Field("When removed",0,choices(0x10 to "Power off automatically",0x11 to "Stay on")))
            QUALITY -> listOf(Field("Priority",0,choices(0 to "Sound quality",1 to "Stable connection")))
            GUIDANCE_VOLUME -> listOf(Field("Volume",0,choices(254 to "−2",255 to "−1",0 to "Default",1 to "+1",2 to "+2")))
        }
        fun valid(v:List<Int>):Boolean = when(this) {
            // Either side may report a function this app doesn't name (newer firmware): it is shown as
            // "Device setting" and kept as is; only named functions are ever written (see patch).
            TOUCH -> v.size==3 && v[0]==2
            PANEL -> v.size==2 && v[0] in 0..1 && v[1] in 0..1
            CYCLE -> v.size==5 && v[0]==1 && v[1] in listOf(0,0x35) && v[2]==1 && v[3]==0 && v[4] in 1..4
            CHAT -> v.size==2 && v[0] in 0..1 && v[1]==1
            CHAT_CONFIG -> v.size==2 && v[0] in 0..2 && v[1] in 0..3
            POWER -> v.size==2 && v[0] in listOf(0x10,0x11) && v[1]==0
            SIDETONE -> v.size==3 && v[0] in 0..1 // preserve both reported volumes on writes
            GUIDANCE -> v.size==2 && v[0] in 0..1
            GESTURES,QUALITY -> v.size==1 && v[0] in 0..1
            GUIDANCE_VOLUME -> v.size==1 && v[0] in listOf(254,255,0,1,2)
        }
        fun set(values:List<Int>,wireSubtype:Int=subtype):ByteArray {
            require(wireSubtype==subtype || (this==QUALITY && wireSubtype==2))
            require(valid(values))
            val body=if(this==GUIDANCE) values.take(1) else values
            return (listOf(getCode+2,wireSubtype)+body).map{it.toByte()}.toByteArray()
        }
    }
    const val PLAYBACK=0x20
    /** Whether the touch panel is on, from its two reported bytes (see [Key.PANEL]). */
    fun panelOn(v:List<Int>)=if(v[0]==0) v[1]==0 else v[1]==1
    /** The second byte that turns the panel on or off, in the encoding the earbuds reported. */
    fun panelCode(v:List<Int>,on:Boolean)=if(v[0]==0) (if(on) 0 else 1) else (if(on) 1 else 0)
    fun parse(type:Int,p:ByteArray):Pair<Key,List<Int>>? {
        if(p.size<3) return null
        val op=p[0].toInt() and 255;val subtype=p[1].toInt() and 255
        val key=Key.entries.firstOrNull{it.type.toInt()==type && (it.subtype==subtype || (it==Key.QUALITY && subtype==2)) && op in listOf(it.getCode+1,it.getCode+3)} ?: return null
        val value=p.drop(2).map{it.toInt() and 255}
        return if(key.valid(value)) key to value else null
    }
    /** The codes to try for a touch choice: noise control is 0x35 on firmware that reports it, 0 on older. */
    fun touchCodes(current:List<Int>,code:Int):List<Int> =
        if(code!=0) listOf(code) else if(current.drop(1).contains(0x35)) listOf(0x35,0) else listOf(0,0x35)
    fun patch(key:Key,current:List<Int>,index:Int,code:Int,wire:Int?=null):List<Int>? {
        if(!key.valid(current)) return null
        val field=key.fields.firstOrNull{it.index==index} ?: return null
        if(field.choices.none{it.code==code}) return null
        val chosen=wire?.takeIf{key==Key.TOUCH && code==0 && it in listOf(0,0x35)}
            ?: if(key==Key.TOUCH && code==0 && current.drop(1).contains(0x35)) 0x35 else code
        val next=current.toMutableList().apply{this[index]=chosen}
        if(key==Key.TOUCH && index in 1..2) {
            val other=3-index
            // The earbuds take each function on one side only: choosing what the other earbud has
            // swaps the two, as Sony's app does.
            fun same(a:Int,b:Int)=(if(a==0x35) 0 else a)==(if(b==0x35) 0 else b)
            if(chosen!=0xFF && same(current[other],chosen)) next[other]=current[index]
            // And the WF-1000XM5 keeps Playback on one earbud (calls are answered with it): a change
            // that leaves neither side with it is refused, so Playback moves to the other earbud.
            // Seen on the owner's earbuds: FF FF and 35 FF were refused, 35 20 and FF 20 accepted.
            if(PLAYBACK in current.drop(1) && PLAYBACK !in next.drop(1)) next[other]=PLAYBACK
        }
        return next.takeIf{key.valid(it)}
    }
    fun equivalent(key:Key,left:List<Int>?,right:List<Int>):Boolean {
        if(left==null) return false
        fun canonical(v:List<Int>)=v.mapIndexed{i,n->if(key==Key.TOUCH && i>0 && n==0x35) 0 else n}
        return canonical(left)==canonical(right)
    }
    fun firmware(p:ByteArray):String? {
        if(p.size<4 || p[0].toInt()!=5 || p[1].toInt()!=2 || (p[2].toInt() and 255)!=p.size-3) return null
        return p.copyOfRange(3,p.size).toString(Charsets.US_ASCII).takeIf{it.matches(Regex("[0-9A-Za-z._-]{1,40}"))}
    }
}
