package ua.olex.sonyanc

import org.junit.Assert.*
import org.junit.Test

class ConnectionUseTest {
    @Test fun closingTheLastScreenLeavesNoBackgroundOwner() {
        val use=ConnectionUse();use.acquire(true)
        assertTrue(use.active);assertTrue(use.foreground)
        use.release(true);assertFalse(use.active);assertFalse(use.foreground)
    }
    @Test fun widgetActionsNeverEnableForegroundPolling() {
        val use=ConnectionUse();use.acquire(false)
        assertTrue(use.active);assertFalse(use.foreground)
        use.release(false);assertFalse(use.active)
    }
    @Test fun aWidgetMayFinishAfterTheScreenClosesAndThenReleasesEverything() {
        val use=ConnectionUse();use.acquire(true);use.acquire(false);use.release(true)
        assertTrue(use.active);assertFalse(use.foreground)
        use.release(false);assertFalse(use.active)
    }
    @Test fun rapidReentryAndDuplicateReleaseDoNotLeakOwners() {
        val use=ConnectionUse()
        repeat(100){use.acquire(true);use.release(true);use.release(true);assertFalse(use.active)}
        use.acquire(true);use.acquire(true);use.release(true);assertTrue(use.foreground)
        use.release(true);assertFalse(use.active)
    }
}
