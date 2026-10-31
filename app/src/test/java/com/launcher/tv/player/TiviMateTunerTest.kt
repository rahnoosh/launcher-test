package com.launcher.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TiviMateTunerTest {
    @Test fun channelNumbersBecomeDigitKeys() {
        assertEquals("input keyevent 8 9 10 11", TiviMateTuner.command("1234"))   // KEYCODE_1..KEYCODE_4
        assertEquals("input keyevent 11 9", TiviMateTuner.command("0042"))        // leading zeros dropped
        assertEquals("input keyevent 7", TiviMateTuner.command("0"))
        assertEquals("input keyevent 8 9", TiviMateTuner.command("No. 12"))
        assertNull(TiviMateTuner.command("ESPN"))
    }
}
