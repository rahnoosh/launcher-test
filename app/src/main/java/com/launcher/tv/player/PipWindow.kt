package com.launcher.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What's playing in the picture-in-picture window, so the home screen can offer Full screen and Stop. */
object PipWindow {
    private val _title = MutableStateFlow<String?>(null)
    /** Title of what's playing in the window, or null when there's no window. */
    val title: StateFlow<String?> = _title.asStateFlow()

    internal fun set(title: String?) {
        _title.value = title
    }
}
