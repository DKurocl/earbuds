package ua.olex.sonyanc

import org.junit.Assert.assertNull
import org.junit.Test

class ModeControlTest {
    @Test fun deferredAutomationCannotReplaceAnInterveningManualSelection() {
        ModeControl.cancelModes()
        val beforeManual = ModeControl.revision
        try {
            ModeControl.request(SonyProtocol.NcState(SonyProtocol.Mode.OFF))
            assertNull(ModeControl.requestIfUnchanged(SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT), beforeManual))
        } finally {
            ModeControl.cancelModes()
        }
    }

    @Test fun automationCannotSubmitAfterItsConnectionOrTimerIsInvalidated() {
        ModeControl.cancelModes()
        val revision = ModeControl.revision
        assertNull(ModeControl.requestIfUnchanged(SonyProtocol.NcState(SonyProtocol.Mode.ANC), revision) { false })
    }
}
