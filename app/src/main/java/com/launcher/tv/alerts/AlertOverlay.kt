package com.launcher.tv.alerts

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.launcher.tv.data.TeamInfo

/** What the corner card shows, and what OK does while it's up. */
data class OverlayCard(
    val kicker: String,
    val title: String,
    val detail: String,
    val teams: List<TeamInfo>,
    /** Short label for the round badge when there are no teams ("UFC", "Zz"). */
    val badge: String,
    val hint: String = "OK  watch     BACK  dismiss",
    val onOk: (() -> Unit)? = null,
) {
    companion object {
        fun from(a: LiveAlert, hint: String = "OK  watch     BACK  dismiss", onOk: () -> Unit) =
            OverlayCard(a.kicker, a.title, a.detail, a.teams, a.event.league.take(3).uppercase(), hint, onOk)
    }
}

/**
 * The pop-up card in the top-right corner. Drawn as an accessibility overlay, so it appears over
 * any app (TiviMate included) without extra permissions and never takes focus from the video.
 */
class AlertOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    var current: OverlayCard? = null
        private set

    val isShowing: Boolean get() = view != null

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    fun show(card: OverlayCard, durationMs: Long = 12_000L) {
        hide()
        current = card
        val v = buildCard(service, card)
        val lp = WindowManager.LayoutParams(
            dp(380), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(28); y = dp(24)
        }
        runCatching { wm.addView(v, lp) }.onSuccess {
            view = v
            v.alpha = 0f; v.translationX = dp(40).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(250).start()
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ hide() }, durationMs)
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        current = null
    }

    companion object {
        /** The card itself; public so screenshot tests can render it. */
        fun buildCard(context: android.content.Context, card: OverlayCard): View = CardBuilder(context).build(card)
    }
}

private class CardBuilder(private val service: android.content.Context) {
    fun build(a: OverlayCard): View {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(238, 18, 22, 30))
                setStroke(dp(1), Color.argb(40, 255, 255, 255))
            }
            elevation = dp(8).toFloat()
        }
        val crests = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val teams = a.teams.ifEmpty { listOf(TeamInfo(a.badge, a.badge)) }
        teams.take(2).forEachIndexed { i, t ->
            crests.addView(crest(t), LinearLayout.LayoutParams(dp(38), dp(38)).apply { if (i > 0) topMargin = dp(6) })
        }
        root.addView(crests)

        val text = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        text.addView(label(a.kicker, 11f, Color.rgb(229, 160, 13), bold = true).apply { letterSpacing = 0.12f })
        text.addView(label(a.title, 18f, Color.WHITE, bold = true).apply { maxLines = 2 })
        if (a.detail.isNotBlank()) text.addView(label(a.detail, 13f, Color.argb(190, 255, 255, 255)))
        text.addView(label(a.hint, 11f, Color.argb(130, 255, 255, 255)).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return root
    }

    private fun crest(t: TeamInfo): View = TextView(service).apply {
        text = t.abbr
        gravity = Gravity.CENTER
        setTextColor(parse(t.altColor) ?: Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (t.abbr.length > 3) 9f else 11f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(parse(t.color) ?: Color.rgb(60, 66, 80))
        }
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun parse(hex: String?): Int? = hex?.let { runCatching { Color.parseColor(if (it.startsWith("#")) it else "#$it") }.getOrNull() }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
}
