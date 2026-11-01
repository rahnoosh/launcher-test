package com.launcher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SkyPhaseTest {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.of(2026, 10, 31)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val rise = at(6, 36)
    private val set = at(17, 46)

    @Test
    fun followsTheSun() {
        assertEquals(SkyPhase.NIGHT, skyPhase(at(2, 0), rise, set, zone))
        assertEquals(SkyPhase.DAWN, skyPhase(at(6, 50), rise, set, zone))
        assertEquals(SkyPhase.DAY, skyPhase(at(12, 0), rise, set, zone))
        assertEquals(SkyPhase.DUSK, skyPhase(at(17, 30), rise, set, zone))
        assertEquals(SkyPhase.NIGHT, skyPhase(at(21, 0), rise, set, zone))
        // Without weather: 6:45 and 18:15.
        assertEquals(SkyPhase.DUSK, skyPhase(at(18, 30), null, null, zone))
    }
}
