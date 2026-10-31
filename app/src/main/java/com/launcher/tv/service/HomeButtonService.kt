package com.launcher.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.launcher.tv.MainActivity

/**
 * Google TV doesn't let third-party apps become the default launcher, so this
 * accessibility service sends the user to HomeBase instead:
 *  1. it catches the Home key on the remote, and
 *  2. if the stock launcher still comes to the front (e.g. backing out of an app), it opens HomeBase over it.
 */
class HomeButtonService : AccessibilityService() {

    private val stockLaunchers = setOf(
        "com.google.android.apps.tv.launcherx",   // Google TV home
        "com.google.android.tvlauncher",          // older Android TV home
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome",                        // Xiaomi PatchWall
    )

    private var lastLaunch = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) openHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() in stockLaunchers
        ) {
            openHome()
        }
    }

    override fun onInterrupt() = Unit

    private fun openHome() {
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 500) return // Home key + stock launcher event arrive together
        lastLaunch = now
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}
