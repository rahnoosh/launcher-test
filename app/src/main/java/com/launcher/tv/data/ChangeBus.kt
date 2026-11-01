package com.launcher.tv.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "Something changed" signals inside the app process, so the home screen updates when the
 * Teams screen or the phone page changes teams, providers or settings.
 */
object ChangeBus {
    const val FOLLOWS = "follows"
    const val PROVIDERS = "providers"
    const val SETTINGS = "settings"

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    fun emit(topic: String) {
        _events.tryEmit(topic)
    }
}
