package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.sonyanc.DeviceSettingsProtocol.Feature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PortableSettingsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun aReadAfterReconnectKeepsOtherRememberedSettings() {
        Prefs.cacheFeature(ctx, Feature.DSEE, true)
        Prefs.cacheFeature(ctx, Feature.AUTO_PAUSE, false)
        Prefs.cacheFeature(ctx, Feature.DSEE, false)
        assertEquals(mapOf(Feature.DSEE to false, Feature.AUTO_PAUSE to false), Prefs.cachedFeatures(ctx))
        Prefs.replaceDevice(ctx, "00:11:22:33:44:55", "New pair")
        assertTrue(Prefs.cachedFeatures(ctx).isEmpty())
    }

    @Test fun backupRoundTripRestoresPortableSettingsWithoutReplacingThePairedDevice() {
        val profile = PersonalProfile(SonyProtocol.NcState(SonyProtocol.Mode.ANC), null, mapOf(Feature.DSEE to true))
        Prefs.savePersonalProfile(ctx, 1, profile)
        Prefs.setCaseReminder(ctx, true)
        Prefs.setDisplayName(ctx, "My earbuds")
        Prefs.setHapticTake(ctx, Haptics.Cue.entries.first().name, 1)
        val backup = SettingsBackup.encode(ctx)
        Prefs.replaceDevice(ctx, "00:11:22:33:44:66", "New pair")
        Prefs.setCaseReminder(ctx, false)
        Prefs.setDisplayName(ctx, "Changed")
        SettingsBackup.restore(ctx, backup.inputStream())

        assertEquals(profile, Prefs.p(ctx).getString("profile_1", null)?.let(PersonalProfile::decode))
        assertTrue(Prefs.caseReminder(ctx))
        assertEquals("My earbuds", Prefs.displayName(ctx))
        assertEquals("00:11:22:33:44:66", Prefs.deviceAddress(ctx))
        SettingsBackup.restore(ctx, """{"version":1,"settings":{"haptics":false}}""".byteInputStream())
        assertFalse(Prefs.haptics(ctx))
        assertEquals("My earbuds", Prefs.displayName(ctx))
    }
}
