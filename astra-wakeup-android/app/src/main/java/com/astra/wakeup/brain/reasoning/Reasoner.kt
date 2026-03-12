package com.astra.wakeup.brain.reasoning

import com.astra.wakeup.brain.actions.Action
import com.astra.wakeup.brain.memory.MemoryItem
import com.astra.wakeup.brain.memory.MemoryScope
import com.astra.wakeup.brain.memory.MemoryStore
import com.astra.wakeup.brain.perception.ContextEvent
import java.util.UUID

class Reasoner(private val memory: MemoryStore) {
    suspend fun decide(event: ContextEvent): Decision {
        memory.store(
            MemoryItem(
                id = UUID.randomUUID().toString(),
                scope = MemoryScope.SHORT_TERM,
                category = "event",
                content = "${event.type}:${event.data}",
                tags = listOf("event", event.type.lowercase())
            )
        )

        val actions = mutableListOf<Action>()
        when (event.type) {
            "PHONE_UNLOCK" -> {
                val recent = memory.search("wake", MemoryScope.SHORT_TERM)
                if (recent.isNotEmpty()) actions += Action.Speak("Welcome back. Your day briefing is ready.")
            }
            "CHARGING_CHANGED" -> actions += Action.Log("info", "Charging event observed")
            "HEADPHONE_CHANGED" -> actions += Action.ShowNotification("Headphone context updated")
            "TIME_TICK" -> {
                // tiny heartbeat ping for now
                actions += Action.Log("debug", "time tick")
            }
        }
        if (actions.isEmpty()) actions += Action.Log("debug", "No decision actions for ${event.type}")
        return Decision(reason = "event=${event.type}", actions = actions, priority = 50)
    }
}
