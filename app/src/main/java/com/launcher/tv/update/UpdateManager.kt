package com.launcher.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.launcher.tv.BuildConfig
import com.launcher.tv.data.Http
import com.launcher.tv.data.HttpException
import com.launcher.tv.data.obj
import com.launcher.tv.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * Self-update: CI publishes every signed build of the default branch to a rolling GitHub release
 * ("homebase-latest") as HomeBase.apk + version.json. HomeBase compares version codes, downloads the
 * APK and hands it to Android's package installer (which asks you to confirm).
 */
object UpdateManager {
    data class Info(val versionCode: Int, val versionName: String, val notes: String)

    data class State(
        val checking: Boolean = false,
        val available: Info? = null,
        /** 0..1 while downloading. */
        val progress: Float? = null,
        val error: String? = null,
        val lastChecked: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val lock = Mutex()

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    private fun base() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/homebase-latest"

    /** Checks at most every 6 hours unless [force]. Returns the newer version, if any. */
    suspend fun check(force: Boolean = false): Info? {
        val s = _state.value
        if (!force && System.currentTimeMillis() - s.lastChecked < 6 * 3600_000L) return s.available
        _state.update { it.copy(checking = true, error = null) }
        return try {
            val info = parseVersion(Http.get("${base()}/version.json"))
            val newer = info?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            _state.update { it.copy(checking = false, available = newer, lastChecked = System.currentTimeMillis()) }
            newer
        } catch (e: HttpException) {
            // 404: nothing published yet (no signing key set up in CI).
            _state.update { it.copy(checking = false, lastChecked = System.currentTimeMillis(), error = if (e.code == 404) null else "HTTP ${e.code}") }
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(checking = false, error = e.message) }
            null
        }
    }

    fun parseVersion(json: String): Info? = runCatching {
        val o = Json.parseToJsonElement(json).obj() ?: return null
        Info(
            versionCode = o.str("versionCode")?.toIntOrNull() ?: return null,
            versionName = o.str("versionName") ?: "?",
            notes = o.str("notes").orEmpty(),
        )
    }.getOrNull()

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Downloads and installs the newest build. Returns a message for the user, or null once the
     * installer has been started (Android then asks to confirm).
     */
    suspend fun install(context: Context): String? = lock.withLock {
        val info = _state.value.available ?: check(force = true) ?: return "HomeBase is up to date (${currentVersion})"
        if (!canInstall(context)) {
            openInstallPermission(context)
            return "Allow HomeBase to install apps (the switch that just opened), then choose Update again"
        }
        return try {
            val apk = download(context)
            withContext(Dispatchers.IO) { startInstall(context, apk) }
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "Update ${info.versionName} failed: ${e.message}"
        } finally {
            _state.update { it.copy(progress = null) }
        }
    }

    private suspend fun download(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "HomeBase.apk")
        _state.update { it.copy(progress = 0f) }
        Http.client.newCall(Request.Builder().url("${base()}/HomeBase.apk").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "")
            val body = resp.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) _state.update { it.copy(progress = done.toFloat() / total) }
                    }
                }
            }
        }
        file
    }

    private fun startInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("HomeBase.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }
}
