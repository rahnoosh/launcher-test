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
import com.launcher.tv.alerts.OverlayBus
import com.launcher.tv.alerts.OverlayCard
import com.launcher.tv.player.Watch
import com.launcher.tv.system.SleepTimer
import com.launcher.tv.ui.GameActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * It also runs the live sports alerts (a card in the corner over whatever is playing; while it shows,
 * OK switches to that game and Back dismisses it) and the sleep timer.
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
        running = true
        val o = AlertOverlay(this)
        overlay = o
        scope.launch {
            LiveAlertEngine(this@HomeButtonService) { alert ->
                val card = if (alert.kicker == "FINAL") {
                    // Game over: OK opens its page (box score + highlights) instead of a channel.
                    OverlayCard.from(alert, hint = "OK  highlights & stats     BACK  dismiss") {
                        startActivity(GameActivity.intent(this@HomeButtonService, alert.event).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } else {
                    OverlayCard.from(alert) { watch(alert) }
                }
                o.show(card)
            }.run()
        }
        scope.launch {
            while (isActive) {
                checkSleepTimer()
                delay(15_000)
            }
        }
        scope.launch { OverlayBus.requests.collect { o.show(it.card, it.durationMs) } }
    }

    private fun checkSleepTimer() {
        val end = SleepTimer.endsAt(this)
        if (end <= 0) return
        val left = end - System.currentTimeMillis()
        if (left <= 0) {
            SleepTimer.clear(this)
            overlay?.hide()
            // Screen off / standby. With HDMI-CEC on, the TV follows.
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else if (left <= 65_000 && !SleepTimer.warned(this)) {
            SleepTimer.setWarned(this)
            overlay?.show(
                OverlayCard(
                    kicker = "SLEEP TIMER",
                    title = "Going to sleep in 1 minute",
                    detail = "Press OK for 30 more minutes",
                    teams = emptyList(),
                    badge = "Zz",
                    hint = "OK  +30 min     BACK  dismiss",
                    onOk = { SleepTimer.extend(this, 30) },
                ),
                durationMs = 55_000,
            )
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val o = overlay
        // Cards without an OK action (e.g. "pick this channel in TiviMate") never take keys.
        if (o != null && ((o.isShowing && o.current?.onOk != null) || swallowNextUp)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        swallowNextUp = true
                    } else {
                        swallowNextUp = false
                        val action = o.current?.onOk
                        o.hide()
                        action?.invoke()
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
        running = false
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

    companion object {
        /** True while the service is enabled and connected (needed for alerts and the sleep timer). */
        @Volatile var running = false
            private set
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
