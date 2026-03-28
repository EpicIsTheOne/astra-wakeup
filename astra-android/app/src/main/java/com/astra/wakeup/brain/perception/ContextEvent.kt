package com.astra.wakeup.brain.perception

data class ContextEvent(
    val type: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val data: Map<String, String> = emptyMap()
)
