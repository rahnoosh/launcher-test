package com.launcher.tv.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class PhoneServerTest {
    private fun raw(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test fun parsesRequestsAndWritesResponses() {
        var seen: PhoneServer.Request? = null
        val server = PhoneServer { r ->
            seen = r
            PhoneServer.Response.json(200, """{"echo":${PhoneServer.quote(r.text)}}""", mapOf("Set-Cookie" to "hb=x"))
        }
        val port = server.start(listOf(0))
        try {
            val body = """{"pin":"123456"}"""
            val resp = raw(
                port,
                "POST /api/login?a=1&b=two%20words HTTP/1.1\r\nHost: 192.168.1.40:8686\r\nX-HomeBase: 1\r\n" +
                    "Cookie: other=1; hb=tok123\r\nContent-Length: ${body.length}\r\n\r\n$body",
            )
            assertTrue(resp, resp.startsWith("HTTP/1.1 200 OK"))
            assertTrue(resp.contains("Set-Cookie: hb=x"))
            assertTrue(resp.contains("""{"echo":"{\"pin\":\"123456\"}"}"""))
            val r = seen!!
            assertEquals("POST", r.method)
            assertEquals("/api/login", r.path)
            assertEquals(mapOf("a" to "1", "b" to "two words"), r.query)
            assertEquals("1", r.header("X-HomeBase"))
            assertEquals("tok123", r.cookie("hb"))
        } finally {
            server.stop()
        }
        assertFalse(server.isRunning)
    }

    @Test fun handlerErrorsBecome500() {
        val server = PhoneServer { error("boom") }
        val port = server.start(listOf(0))
        try {
            val resp = raw(port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(resp, resp.startsWith("HTTP/1.1 500"))
            assertTrue(resp.contains("boom"))
        } finally {
            server.stop()
        }
    }

    @Test fun quoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", PhoneServer.quote("a\"b\\c\nd"))
    }
}

class PhoneAuthTest {
    private class Mem : KeyValue {
        val map = HashMap<String, String?>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { map[key] = value }
    }

    @Test fun pinLoginTokensAndLockout() {
        var now = 1_000_000L
        val auth = PhoneAuth(Mem()) { now }
        val pin = auth.pin
        assertEquals(6, pin.length)
        assertEquals(pin, auth.pin) // stable

        val token = auth.login(pin, "10.0.0.2")
        assertNotNull(token)
        assertTrue(auth.valid(token))
        assertFalse(auth.valid("nope"))
        assertFalse(auth.valid(null))

        repeat(5) { assertNull(auth.login("000000", "10.0.0.9")) }
        assertTrue(auth.isLocked("10.0.0.9"))
        assertNull(auth.login(pin, "10.0.0.9"))            // locked even with the right PIN
        assertNotNull(auth.login(pin, "10.0.0.3"))         // other phones unaffected
        now += 61_000
        assertNotNull(auth.login(pin, "10.0.0.9"))         // lock expires

        val newPin = auth.newPin()
        assertNotEquals("", newPin)
        assertFalse(auth.valid(token))                     // new PIN signs everyone out
    }
}
