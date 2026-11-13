package ua.olex.sonyanc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmphasizedEasingTest {
    @Test fun endsAndTheJoinOfTheTwoSegments() {
        assertEquals(0f, EmphasizedEasing.transform(0f), 0f)
        assertEquals(1f, EmphasizedEasing.transform(1f), 0f)
        // The path passes through (0.166666, 0.4), where the two cubics meet.
        assertEquals(0.4f, EmphasizedEasing.transform(0.166666f), 0.002f)
    }

    @Test fun monotonicAndFastEarly() {
        var last = 0f
        for (i in 1..100) {
            val v = EmphasizedEasing.transform(i / 100f)
            assertTrue("not monotonic at $i", v >= last - 1e-4f)
            last = v
        }
        // Emphasized decelerate: most of the way is covered in the first third.
        assertTrue(EmphasizedEasing.transform(0.33f) > 0.8f)
    }
}
