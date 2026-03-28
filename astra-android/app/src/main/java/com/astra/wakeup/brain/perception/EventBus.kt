package com.astra.wakeup.brain.perception

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val _events = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    fun publish(event: ContextEvent) {
        _events.tryEmit(event)
    }
}
