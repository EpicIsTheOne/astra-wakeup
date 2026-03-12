package com.astra.wakeup.brain.memory

interface MemoryStore {
    suspend fun store(item: MemoryItem)
    suspend fun search(query: String, scope: MemoryScope? = null): List<MemoryItem>
    suspend fun update(item: MemoryItem)
    suspend fun delete(id: String)
    suspend fun all(scope: MemoryScope? = null): List<MemoryItem>
}
