package com.launcher.tv.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.launcher.tv.MainActivity
import com.launcher.tv.alerts.AlertOverlay
import com.launcher.tv.alerts.LiveAlert
import com.launcher.tv.alerts.LiveAlertEngine
import com.launcher.tv.player.Watch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Google TV doesn't let third-party apps become the default launcher, so this accessibility
 * service sends the user to HomeBase instead:
 *  1. it catches the Home key on the remote, and
 *  2. if the stock launcher still comes to the front (e.g. backing out of an app), it opens HomeBase over it.
 *
 * It also runs the live sports alerts: a card in the corner over whatever is playing.
 * While a card is showing, OK switches to that game and Back dismisses it.
 */
class HomeButtonService : AccessibilityService() {

    private val stockLaunchers = setOf(
        "com.google.android.apps.tv.launcherx",   // Google TV home
        "com.google.android.tvlauncher",          // older Android TV home
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome",                        // Xiaomi PatchWall
    )

    private var lastLaunch = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: AlertOverlay? = null
    private var swallowNextUp = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val o = AlertOverlay(this)
        overlay = o
        scope.launch { LiveAlertEngine(this@HomeButtonService) { alert -> o.show(alert) }.run() }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val o = overlay
        if (o != null && (o.isShowing || swallowNextUp)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        swallowNextUp = true
                    } else {
                        swallowNextUp = false
                        o.current?.let { watch(it) }
                        o.hide()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        swallowNextUp = true
                    } else {
                        swallowNextUp = false
                        o.hide()
                    }
                    return true
                }
            }
        }
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

    override fun onDestroy() {
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun watch(alert: LiveAlert) {
        scope.launch {
            when (val r = Watch.game(this@HomeButtonService, alert.event, interactive = false)) {
                is Watch.Result.OpenedTiviMate -> r.why?.let { Toast.makeText(this@HomeButtonService, it, Toast.LENGTH_LONG).show() }
                else -> Unit
            }
        }
    }

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
