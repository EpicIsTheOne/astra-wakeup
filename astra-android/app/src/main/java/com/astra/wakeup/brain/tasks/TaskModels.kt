package com.astra.wakeup.brain.tasks

import com.astra.wakeup.brain.actions.Action

data class TaskStep(
    val id: String,
    val action: Action,
    val delayMs: Long = 0L
)

data class Task(
    val id: String,
    val description: String,
    val trigger: String,
    val steps: List<TaskStep>,
    val priority: Int = 50
)
