package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class SettingsCoverageTest {
    private val fixtures=mapOf(
        AdvancedSettings.Key.TOUCH to "F7 03 02 00 20",
        AdvancedSettings.Key.PANEL to "D7 D2 00 00",
        AdvancedSettings.Key.CYCLE to "FB 03 01 35 01 00 01",
        AdvancedSettings.Key.CHAT to "F7 0C 00 01",
        AdvancedSettings.Key.CHAT_CONFIG to "FB 0C 00 01",
        AdvancedSettings.Key.POWER to "27 05 10 00",
        AdvancedSettings.Key.SIDETONE to "F7 08 00 07 09",
        AdvancedSettings.Key.GESTURES to "F7 0F 00",
        AdvancedSettings.Key.QUALITY to "E7 00 00",
        AdvancedSettings.Key.GUIDANCE to "47 01 00 01",
        AdvancedSettings.Key.GUIDANCE_VOLUME to "47 20 00")
    @Test fun everyExposedChoiceHasAValidatedWriteAndMatchingReadback() {
        assertEquals(AdvancedSettings.Key.entries.toSet(),fixtures.keys)
        fixtures.forEach {(key,hex)->
            val original=AdvancedSettings.parse(key.type.toInt(),SonyProtocol.parseHex(hex))!!.second
            key.fields.forEach {field->field.choices.forEach {choice->
                val wanted=original.toMutableList().apply{this[field.index]=choice.code}
                val frame=key.set(wanted)
                assertEquals(key.getCode+2,frame[0].toInt() and 255)
                assertEquals(key.subtype,frame[1].toInt() and 255)
                original.indices.filter{it!=field.index}.forEach{assertEquals(original[it],wanted[it])}
                val reply=(listOf(key.getCode+1,key.subtype)+wanted).map{it.toByte()}.toByteArray()
                assertEquals(key to wanted,AdvancedSettings.parse(key.type.toInt(),reply))
                val changed=key.getCode+3
                reply[0]=changed.toByte()
                assertEquals(key to wanted,AdvancedSettings.parse(key.type.toInt(),reply))
            }}
        }
    }
    @Test fun randomOrTruncatedDeviceMessagesCannotUnlockControls() {
        fixtures.forEach {(key,hex)->
            val p=SonyProtocol.parseHex(hex)
            for(size in 0 until p.size) assertNull(AdvancedSettings.parse(key.type.toInt(),p.copyOf(size)))
            assertNull(AdvancedSettings.parse(key.type.toInt(),p+byteArrayOf(0)))
            assertNull(AdvancedSettings.parse(if(key.type.toInt()==0x0C) 0x0E else 0x0C,p))
        }
    }
    @Test fun partialBatteryFreshnessDoesNotMarkCachedOtherSideLive() {
        assertEquals(setOf(1),SonyProtocol.liveBatteryParts(SonyProtocol.parseHex("23 09 00 02 64 03 64 64")))
        assertEquals(setOf(2),SonyProtocol.liveBatteryParts(SonyProtocol.parseHex("25 0A 5D 00 64")))
        assertTrue(SonyProtocol.liveBatteryParts(SonyProtocol.parseHex("23 09 00 00 00 00")).isEmpty())
    }
    @Test fun allEqualizerPresetsAndBandBoundariesAreAccepted() {
        EqualizerProtocol.presets.keys.forEach {preset->
            val reply=byteArrayOf(0x57,0,preset.toByte(),0)
            assertEquals(preset,EqualizerProtocol.parse(reply)!!.preset)
            assertEquals(preset,EqualizerProtocol.preset(preset)[2].toInt() and 255)
        }
        val extrema=listOf(-10,10,-10,10,-10,10)
        val set=EqualizerProtocol.custom(extrema).apply{this[0]=0x57}
        assertEquals(extrema,EqualizerProtocol.parse(set)!!.bands)
        assertThrows(IllegalArgumentException::class.java){EqualizerProtocol.custom(List(6){11})}
        assertThrows(IllegalArgumentException::class.java){EqualizerProtocol.preset(255)}
    }    @Test fun editsMergeWithFreshValuesAndAmbientAliasesCompareEqual() {
        val key=AdvancedSettings.Key.TOUCH
        assertEquals(listOf(2,0x10,0x20),AdvancedSettings.patch(key,listOf(2,0x35,0x20),1,0x10))
        assertTrue(AdvancedSettings.equivalent(key,listOf(2,0,0x20),listOf(2,0x35,0x20)))
        assertNull(AdvancedSettings.patch(key,listOf(2,0,0x20),0,0))
        assertNull(AdvancedSettings.patch(key,listOf(2,0,0x20),1,0x80))
    }
    @Test fun connectionQualityUsesTheInquiredSubtypeForItsWrite() {
        val key=AdvancedSettings.Key.QUALITY
        assertEquals(key to listOf(1),AdvancedSettings.parse(0x0C,SonyProtocol.parseHex("E7 02 01")))
        assertEquals("E8 02 00",SonyProtocol.hex(key.set(listOf(0),2)))
        assertThrows(IllegalArgumentException::class.java){key.set(listOf(0),3)}
    }

}
