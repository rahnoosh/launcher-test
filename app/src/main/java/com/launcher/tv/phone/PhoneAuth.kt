package com.launcher.tv.phone

import java.security.SecureRandom

/** Tiny key-value store (SharedPreferences on the box, a map in tests). */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * PIN login for the phone page. The 6-digit PIN is shown on the TV; a correct PIN gets a random
 * token stored as a cookie. Five wrong PINs from one address lock it out for a minute.
 */
class PhoneAuth(private val store: KeyValue, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    private val failures = HashMap<String, Pair<Int, Long>>()

    val pin: String
        @Synchronized get() = store.get("pin") ?: newPin()

    /** New PIN; signs out every phone. */
    @Synchronized
    fun newPin(): String {
        val p = (100_000 + random.nextInt(900_000)).toString()
        store.put("pin", p)
        store.put("tokens", "")
        return p
    }

    private fun tokens(): List<String> = store.get("tokens").orEmpty().split(',').filter { it.isNotBlank() }

    @Synchronized
    fun isLocked(ip: String): Boolean {
        val (count, last) = failures[ip] ?: return false
        if (clock() - last > 60_000L) { failures.remove(ip); return false }
        return count >= 5
    }

    /** Returns a session token, or null for a wrong PIN / locked-out address. */
    @Synchronized
    fun login(pinAttempt: String, ip: String): String? {
        if (isLocked(ip)) return null
        if (pinAttempt.trim() != pin) {
            val (count, _) = failures[ip] ?: (0 to 0L)
            failures[ip] = (count + 1) to clock()
            return null
        }
        failures.remove(ip)
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        store.put("tokens", (tokens() + token).takeLast(20).joinToString(","))
        return token
    }

    @Synchronized
    fun valid(token: String?): Boolean = !token.isNullOrBlank() && token in tokens()
}
