package com.launcher.tv.player

import android.content.Context
import com.launcher.tv.data.AppsRepository
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EXPERIMENTAL one-press tuning for TiviMate.
 *
 * TiviMate can't be told which channel to open, but it switches channel when you type a number.
 * The remote has no number keys, so HomeBase connects to the box's own ADB over the network
 * (127.0.0.1:5555, the same "network debugging" used to install it) and types the number:
 *   input keyevent KEYCODE_1 KEYCODE_2 KEYCODE_3 KEYCODE_4
 * The first time, the TV asks "Allow network debugging?" — tick "Always allow".
 */
object TiviMateTuner {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    private fun prefs(c: Context) = c.getSharedPreferences("tivimate_tuner", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    /** Wait after opening TiviMate before typing (it needs to be on screen). */
    fun delayMs(c: Context) = prefs(c).getLong("delay_ms", 3_500L)

    /** "1234" -> "input keyevent 8 9 10 11" (KEYCODE_0 is 7). Null if there are no digits. */
    fun command(number: String): String? {
        val digits = number.filter { it.isDigit() }.trimStart('0').ifEmpty { if (number.any { it.isDigit() }) "0" else "" }
        if (digits.isEmpty()) return null
        return "input keyevent " + digits.map { 7 + (it - '0') }.joinToString(" ")
    }

    /** Connects once and runs a harmless command; this is what triggers the TV's "Allow debugging?" prompt. */
    suspend fun test(context: Context): Result<Unit> = runCatching {
        val out = shell(context, "echo homebase-ok")
        check(out.contains("homebase-ok")) { "Unexpected reply: $out" }
    }

    /** Opens TiviMate and types the channel number. */
    suspend fun tune(context: Context, number: String): Result<Unit> = runCatching {
        val cmd = command(number) ?: error("This channel has no number")
        check(AppsRepository(context).launch(AppsRepository.TIVIMATE)) { "TiviMate isn't installed" }
        delay(delayMs(context))
        shell(context, cmd)
        Unit
    }

    private suspend fun shell(context: Context, cmd: String): String = withContext(Dispatchers.IO) {
        // Long socket timeout: the first connection waits for you to accept the prompt on the TV.
        Dadb.create(HOST, PORT, keyPair(context), 5_000, 45_000).use { adb ->
            val r = adb.shell(cmd)
            if (r.exitCode != 0) error(r.allOutput.ifBlank { "exit ${r.exitCode}" })
            r.allOutput
        }
    }

    private fun keyPair(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Human-readable reason for common failures. */
    fun explain(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("ECONNREFUSED", true) || m.contains("Connection refused", true) ->
                "Network debugging is off. Run 'adb tcpip 5555' from a computer (see README), then try again."
            m.contains("timed out", true) || m.contains("timeout", true) ->
                "No answer from the box. If the TV asked 'Allow debugging?', choose Always allow and try again."
            else -> m
        }
    }
}
