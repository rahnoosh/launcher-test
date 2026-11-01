package com.launcher.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManagerTest {
    @Test fun readsVersionJsonFromCi() {
        val info = UpdateManager.parseVersion("""{"versionCode": 71, "versionName": "0.71", "commit": "abc", "notes": "Teams screen"}""")!!
        assertEquals(71, info.versionCode)
        assertEquals("0.71", info.versionName)
        assertEquals("Teams screen", info.notes)
        assertNull(UpdateManager.parseVersion("not json"))
        assertNull(UpdateManager.parseVersion("""{"versionName": "x"}"""))
    }
}
