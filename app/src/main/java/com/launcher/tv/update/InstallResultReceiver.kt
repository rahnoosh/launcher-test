package com.launcher.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Gets the package installer's answer: shows its confirm screen, or reports success/failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "HomeBase updated", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(
                    context,
                    "This update is signed with a different key than the installed HomeBase. Reinstall once from the APK (see README).",
                    Toast.LENGTH_LONG,
                ).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // you chose Cancel
            else -> Toast.makeText(
                context, "Update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"), Toast.LENGTH_LONG,
            ).show()
        }
    }
}
