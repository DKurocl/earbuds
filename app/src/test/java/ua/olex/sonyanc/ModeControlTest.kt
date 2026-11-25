package ua.olex.sonyanc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun newerChoiceSupersedesFeedbackAcrossSurfaces() = runBlocking {
        ModeControl.cancelModes()
        try {
            val widget = ModeControl.request(SonyProtocol.NcState(SonyProtocol.Mode.AMBIENT))
            val app = ModeControl.request(SonyProtocol.NcState(SonyProtocol.Mode.OFF))
            assertFalse(widget.await())
            assertFalse(ModeControl.isCurrent(widget))
            assertTrue(ModeControl.isCurrent(app))
        } finally {
            ModeControl.cancelModes()
        }
    }
}
