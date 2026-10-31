package com.launcher.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.launcher.tv.ui.HomeBaseTheme
import com.launcher.tv.ui.HomeScreen
import com.launcher.tv.ui.HomeViewModel

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeBaseTheme { HomeScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
