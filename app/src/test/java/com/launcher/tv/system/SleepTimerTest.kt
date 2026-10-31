package com.launcher.tv.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test fun stepsCycleFromWhatIsLeft() {
        assertEquals(30, SleepTimer.nextStep(0))
        assertEquals(60, SleepTimer.nextStep(30))
        assertEquals(60, SleepTimer.nextStep(41))   // 41 left: next press = 60
        assertEquals(120, SleepTimer.nextStep(90))
        assertEquals(0, SleepTimer.nextStep(120))   // wraps to off
        assertEquals(0, SleepTimer.nextStep(119))
    }

    @Test fun labels() {
        assertEquals("Off", SleepTimer.label(0))
        assertEquals("45 min left", SleepTimer.label(45))
        assertEquals("1 h left", SleepTimer.label(60))
        assertEquals("1 h 30 min left", SleepTimer.label(90))
    }
}
