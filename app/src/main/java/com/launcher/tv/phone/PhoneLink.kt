package com.launcher.tv.phone

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Runs the phone page server inside the HomeBase process (started with the app and the
 * Home-button service) and knows the address + PIN to show on the TV.
 */
object PhoneLink {
    private val PORTS = listOf(8686, 8687, 8688, 8689, 0)
    @Volatile private var server: PhoneServer? = null
    @Volatile private var auth: PhoneAuth? = null

    private fun prefs(c: Context) = c.getSharedPreferences("phone_page", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on) start(c) else stop()
    }

    fun start(c: Context) {
        if (server?.isRunning == true || !enabled(c)) return
        val app = c.applicationContext
        thread(name = "phone-page-start", isDaemon = true) {
            synchronized(this) {
                if (server?.isRunning == true) return@thread
                runCatching {
                    val api = PhoneApi(app, auth(app)) { app.assets.open("phone/index.html").use { it.readBytes() } }
                    server = PhoneServer(api::handle).also { it.start(PORTS) }
                }
            }
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun isRunning() = server?.isRunning == true

    fun auth(c: Context): PhoneAuth = auth ?: synchronized(this) {
        auth ?: PhoneAuth(object : KeyValue {
            override fun get(key: String) = prefs(c).getString(key, null)
            override fun put(key: String, value: String?) { prefs(c).edit().putString(key, value).apply() }
        }).also { auth = it }
    }

    fun pin(c: Context) = auth(c).pin
    fun newPin(c: Context) = auth(c).newPin()

    /** "http://192.168.1.40:8686", or null when not running / no network. */
    fun address(): String? {
        val port = server?.port?.takeIf { it > 0 } ?: return null
        val ip = lanAddress() ?: return null
        return "http://$ip:$port"
    }

    /** Link for the QR code: opening it signs the phone in with the PIN. */
    fun loginLink(c: Context): String? = address()?.let { "$it/?pin=${pin(c)}" }

    private fun lanAddress(): String? = runCatching {
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
        (addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull())?.hostAddress
    }.getOrNull()
}
