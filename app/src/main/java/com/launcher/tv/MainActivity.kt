package com.launcher.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.launcher.tv.data.ChangeBus
import com.launcher.tv.data.Profiles
import com.launcher.tv.ui.HomeBaseTheme
import com.launcher.tv.ui.HomeScreen
import com.launcher.tv.ui.HomeViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    /** The profile this screen was built for; another one means rebuilding it. */
    private var profile: String = Profiles.MAIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = Profiles.activeId(this)
        setContent { HomeBaseTheme { HomeScreen(vm) } }
        lifecycleScope.launch {
            ChangeBus.events.collect { if (it == Profiles.CHANGED) rebuildIfProfileChanged() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (rebuildIfProfileChanged()) return
        vm.onResume()
    }

    /** A new person: drop the old screen state and build it again from their data. */
    private fun rebuildIfProfileChanged(): Boolean {
        if (Profiles.activeId(this) == profile) return false
        viewModelStore.clear()
        recreate()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Remotes with a Profile button open Who's watching.
        if (event.keyCode == KeyEvent.KEYCODE_PROFILE_SWITCH) {
            if (event.action == KeyEvent.ACTION_UP) vm.askProfiles()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
