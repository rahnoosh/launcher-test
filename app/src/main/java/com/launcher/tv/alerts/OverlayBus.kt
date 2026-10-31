package com.launcher.tv.alerts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Lets the app put a card on screen through the accessibility service (which can draw over other apps). */
object OverlayBus {
    data class Request(val card: OverlayCard, val durationMs: Long)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests

    fun show(card: OverlayCard, durationMs: Long = 12_000L): Boolean = _requests.tryEmit(Request(card, durationMs))
}
