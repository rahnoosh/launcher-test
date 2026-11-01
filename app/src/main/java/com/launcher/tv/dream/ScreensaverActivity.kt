package com.launcher.tv.dream

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.launcher.tv.ui.HomeBaseTheme
import kotlinx.coroutines.launch

/** Screensaver preview (and a manual "ambient mode"). Any key closes it. */
class ScreensaverActivity : ComponentActivity() {
    private val openedAt = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val data = ScreensaverData(this)
        lifecycleScope.launch { data.run() }
        setContent {
            val d by data.state.collectAsState()
            HomeBaseTheme { Screensaver(com.launcher.tv.data.Spoilers.apply(this@ScreensaverActivity, d)) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Ignore the tail of the key press that opened it.
        if (event.action == KeyEvent.ACTION_UP && System.currentTimeMillis() - openedAt > 800) finish()
        return true
    }
}
