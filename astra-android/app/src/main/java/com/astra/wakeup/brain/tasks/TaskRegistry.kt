package com.astra.wakeup.brain.tasks

import com.astra.wakeup.brain.actions.Action

object TaskRegistry {
    fun defaults(): List<Task> = listOf(
        Task(
            id = "morning_routine",
            description = "Morning Routine",
            trigger = "PHONE_UNLOCK",
            steps = listOf(
                TaskStep("greet", Action.Speak("Good morning, chaos prince.")),
                TaskStep("summary", Action.ShowNotification("Open Astra for your day summary."), delayMs = 300),
                TaskStep("coach", Action.ChangePersonality("coach"), delayMs = 300)
            ),
            priority = 80
        )
    )

    fun all(context: android.content.Context): List<Task> = defaults() + TaskStorage.list(context)
}
