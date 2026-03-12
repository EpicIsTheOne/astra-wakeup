package com.astra.wakeup.brain.automation

import android.content.Context
import com.astra.wakeup.brain.actions.Action
import com.astra.wakeup.brain.actions.ActionExecutor
import com.astra.wakeup.brain.perception.ContextEvent
import com.astra.wakeup.brain.tasks.TaskAgent
import com.astra.wakeup.brain.tasks.TaskRegistry
import com.astra.wakeup.ui.ContextRuleRepository
import com.astra.wakeup.ui.TriggerType
import kotlinx.coroutines.runBlocking

class AutomationHub(private val context: Context, private val executor: ActionExecutor) {
    private val taskAgent = TaskAgent(executor)

    fun onEvent(event: ContextEvent) {
        // 1) Run matching task agents
        TaskRegistry.defaults().filter { it.trigger.equals(event.type, true) }.forEach { task ->
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
        val taskRules = TaskRegistry.defaults().size
        // cron rules represented via backend; keep lightweight count from saved cache
        val cronRules = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getInt("cron_rules_count", 0)
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
