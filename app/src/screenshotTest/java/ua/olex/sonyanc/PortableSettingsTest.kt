package ua.olex.sonyanc

import android.content.Intent
import kotlinx.coroutines.runBlocking
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

    @Test fun turningEqualizerOffPreservesTheLastConfirmedPresetForThisPair() {
        assertEquals(0xA0, Prefs.lastEnabledEqualizerPreset(ctx))
        Prefs.cacheEqualizer(ctx, EqualizerProtocol.State(0xA2, emptyList()))
        Prefs.cacheEqualizer(ctx, EqualizerProtocol.State(0, emptyList()))
        assertEquals(0xA2, Prefs.lastEnabledEqualizerPreset(ctx))
        assertEquals(0, Prefs.cachedEqualizer(ctx)?.preset)
        Prefs.replaceDevice(ctx, "00:11:22:33:44:77", "New pair")
        assertEquals(0xA0, Prefs.lastEnabledEqualizerPreset(ctx))
    }

    @Test fun downloadedGraphicEqFitsSixBandsAndSavedSoundsFollowThePair() {
        val curve = "GraphicEQ: 40 -3; 63 2; 400 4; 1000 1; 2500 -2; 6300 2; 16000 11; 20000 8"
        val bands = listOf(2, 4, 1, -2, 2, 10)
        assertEquals(bands, EqualizerFile.read(curve))
        assertNull(EqualizerFile.read("GraphicEQ: 100 0; 100 2"))
        assertNull(EqualizerFile.read("GraphicEQ: 20 NaN; 100 2"))
        Prefs.saveSound(ctx, 1, bands)
        Prefs.saveFirmware(ctx, "5.0.2")
        assertEquals(bands, Prefs.savedSound(ctx, 1))
        assertEquals("5.0.2", Prefs.lastFirmware(ctx))
        Prefs.replaceDevice(ctx, "00:11:22:33:44:55", "New pair")
        assertNull(Prefs.savedSound(ctx, 1))
        assertNull(Prefs.lastFirmware(ctx))
    }

    @Test fun soundPressureIgnoresUnavailablePacketsAndDoseUsesAcousticEnergy() {
        val reply = byteArrayOf(0x5B, 0x01, 80, 0)
        assertTrue(ListeningPressure.isReply(reply, 1))
        assertFalse(ListeningPressure.isReply(reply, 3))
        assertFalse(ListeningPressure.isReply(reply.take(3).toByteArray(), 1))
        assertEquals(80, ListeningPressure.level(reply))
        reply[2] = 0xFF.toByte()
        assertNull(ListeningPressure.level(reply))
        assertEquals(1.0, ExposureLog.fraction(80, 80, 40 * 3600.0), 1e-12)
        assertEquals(1.0, ExposureLog.fraction(90, 90, 4 * 3600.0), 1e-12)
        val now = 200 * ExposureLog.HOUR_MS
        val log = ExposureLog.decode("32:0.4;33:0.1;201:0.5", now)
        ExposureLog.add(log, now, 0.3)
        assertEquals(mapOf(33L to .1, 200L to .3), ExposureLog.decode(ExposureLog.encode(log), now))
    }

    @Test fun blankNamesDoNotReplaceTheDeviceLabel() {
        Prefs.setDisplayName(ctx, "  ")
        assertNull(Prefs.displayName(ctx))
        Prefs.saveDevice(ctx, "00:11:22:33:44:55", "")
        assertNull(Prefs.deviceName(ctx))
        Prefs.setDisplayName(ctx, "My earbuds")
        assertEquals("My earbuds", Prefs.displayName(ctx))
    }

    @Test fun oldBatteryHistoryExpiresEvenWithoutANewReading() {
        val now = System.currentTimeMillis()
        val mode = SonyProtocol.Mode.ANC
        val older = now - BatteryLog.KEEP_MS - 120 * 60_000L
        val oldLog = listOf(
            BatterySample(older, 95, false, mode, 1),
            BatterySample(older + 60 * 60_000L, 80, false, mode, 1),
        )
        val oldHealth = listOf(WeekHealth(BatteryHealth.weekOf(now) - BatteryHealth.MAX_WEEKS, 420, 8))
        Prefs.p(ctx).edit()
            .putString("battery_log", BatteryLog.encode(oldLog))
            .putString("battery_health", BatteryHealth.encode(oldHealth)).apply()
        assertNotNull(BatteryLog.rate(oldLog, mode))
        assertNull(Prefs.drainRate(ctx, mode))
        assertTrue(Prefs.batteryLog(ctx).isEmpty())
        assertTrue(Prefs.batteryHealth(ctx).isEmpty())
        assertFalse(Prefs.p(ctx).contains("battery_log"))
        assertFalse(Prefs.p(ctx).contains("battery_health"))
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

    @Test fun failedAmbientConfirmationDoesNotArmACountdown() = runBlocking {
        Headphones.init(ctx)
        ModeControl.cancelModes()
        Prefs.clearTimer(ctx)
        try {
            assertNull(AmbientTimer.start(ctx, 5, SonyProtocol.NcState(SonyProtocol.Mode.OFF)))
            assertEquals(0L, Prefs.timerUntil(ctx))
        } finally {
            ModeControl.cancelModes()
            AmbientTimer.cancel(ctx)
        }
    }

    @Test fun aReplacedTimerIgnoresThePreviousAlarmAndNotificationAction() {
        val now = System.currentTimeMillis()
        val previous = now - 60_000
        val current = now + 60_000
        Prefs.setTimer(ctx, current, SonyProtocol.Mode.OFF)
        val receiver = AmbientTimerReceiver()
        receiver.onReceive(ctx, Intent().putExtra(AmbientTimer.EXTRA_UNTIL, previous))
        receiver.onReceive(ctx, Intent(LiveTimer.ACTION_STAY).putExtra(AmbientTimer.EXTRA_UNTIL, previous))
        assertEquals(current, AmbientTimer.until(ctx))
        assertEquals(SonyProtocol.Mode.OFF, AmbientTimer.returnMode(ctx))

        receiver.onReceive(ctx, Intent(LiveTimer.ACTION_STAY).putExtra(AmbientTimer.EXTRA_UNTIL, current))
        assertNull(AmbientTimer.until(ctx))
    }
}
