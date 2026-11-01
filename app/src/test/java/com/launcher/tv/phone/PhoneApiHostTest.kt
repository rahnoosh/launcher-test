package com.launcher.tv.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneApiHostTest {
    @Test fun onlyTheBoxAddressIsAccepted() {
        assertTrue(PhoneApi.hostAllowed("192.168.1.40:8686"))
        assertTrue(PhoneApi.hostAllowed("10.0.0.5"))
        assertTrue(PhoneApi.hostAllowed("localhost:8686"))
        assertTrue(PhoneApi.hostAllowed("homebase.local:8686"))
        assertTrue(PhoneApi.hostAllowed("[fe80::1]:8686"))
        assertFalse(PhoneApi.hostAllowed("evil.example.com"))
        assertFalse(PhoneApi.hostAllowed("192.168.1.40.evil.com"))
        assertFalse(PhoneApi.hostAllowed(null))
    }
}
