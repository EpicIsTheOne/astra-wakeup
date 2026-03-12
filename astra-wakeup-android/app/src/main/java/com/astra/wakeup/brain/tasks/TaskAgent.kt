package com.astra.wakeup.brain.tasks

import com.astra.wakeup.brain.actions.ActionExecutor
import kotlinx.coroutines.delay

class TaskAgent(private val executor: ActionExecutor) {
    suspend fun run(task: Task) {
        task.steps.forEach { step ->
            if (step.delayMs > 0) delay(step.delayMs)
            executor.execute(step.action)
        }
    }
}
