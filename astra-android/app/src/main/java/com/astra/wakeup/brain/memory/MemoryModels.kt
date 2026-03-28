package com.astra.wakeup.brain.memory

enum class MemoryScope { SHORT_TERM, LONG_TERM, WORKING }

data class MemoryItem(
    val id: String,
    val scope: MemoryScope,
    val category: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)
