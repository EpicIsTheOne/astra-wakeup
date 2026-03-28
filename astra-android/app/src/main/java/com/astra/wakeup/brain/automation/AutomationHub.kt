package com.astra.wakeup.brain.automation

import android.content.Context
import com.astra.wakeup.brain.actions.Action
import com.astra.wakeup.brain.actions.ActionExecutor
import com.astra.wakeup.brain.perception.ContextEvent
import com.astra.wakeup.brain.tasks.TaskAgent
import com.astra.wakeup.brain.tasks.TaskRegistry
import com.astra.wakeup.ui.ApiCalendarClient
import com.astra.wakeup.ui.ContextRuleRepository
import com.astra.wakeup.ui.TriggerType
import kotlinx.coroutines.runBlocking

class AutomationHub(private val context: Context, private val executor: ActionExecutor) {
    private val taskAgent = TaskAgent(executor)

    fun onEvent(event: ContextEvent) {
        // 1) Run matching task agents
        TaskRegistry.all(context).filter { it.trigger.equals(event.type, true) }.forEach { task ->
            runBlocking { taskAgent.run(task) }
        }

        // 2) Run context rules as automation actions (minimal bridge)
        val trigger = runCatching { TriggerType.valueOf(event.type) }.getOrNull()
        if (trigger != null) {
            val repo = ContextRuleRepository(context)
            val rules = repo.getEnabledRulesByTrigger(trigger)
            rules.forEach { rule ->
                rule.actions.forEach { a ->
                    when (a.type.lowercase()) {
                        "speak" -> executor.execute(Action.Speak(a.p1))
                        "show_notification" -> executor.execute(Action.ShowNotification(a.p1))
                        "change_personality" -> executor.execute(Action.ChangePersonality(a.p1))
                    }
                }
            }
        }
    }

    fun state(lastEvent: String, lastDecision: String): AutomationState {
        val contextRules = ContextRuleRepository(context).getRules().size
        val taskRules = TaskRegistry.all(context).size
        val apiUrl = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getString("api_url", "") ?: ""
        val cronRules = if (apiUrl.isBlank()) 0 else ApiCalendarClient.fetch(apiUrl).second.size
        val total = contextRules + taskRules + cronRules
        return AutomationState(
            totalRules = total,
            activeRules = total,
            contextRules = contextRules,
            taskRules = taskRules,
            cronRules = cronRules,
            lastEvent = lastEvent,
            lastDecision = lastDecision
        )
    }
}
