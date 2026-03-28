package com.astra.wakeup.brain.reasoning

import com.astra.wakeup.brain.actions.Action

data class Decision(
    val reason: String,
    val actions: List<Action>,
    val priority: Int = 50
)
